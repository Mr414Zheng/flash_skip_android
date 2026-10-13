"""检查仓库规格引用和 PR 验收说明；不推断契约是否合理。"""

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path
from urllib.parse import unquote, urlsplit


LINK = re.compile(r"\[[^\]\n]*\]\((<[^>]+>|[^\s)]+)(?:\s+\"[^\"]*\")?\)")
REFERENCE = re.compile(r"\b(SDD-\d{3})/((?:AC|T)-\d{2})\b")


def visible(text):
    return re.sub(r"<!--.*?-->", "", text, flags=re.S)


def registered_specs(root):
    text = (root / "specs/README.md").read_text()
    registry = {}
    for line in text.splitlines():
        match = re.match(r"\| (SDD-\d{3}) / C\d{2} .*?\| ([^|]+)\|", line)
        if not match:
            continue
        identifier, cell = match.groups()
        if identifier in registry:
            raise ValueError(f"重复规格编号：{identifier}")
        link = LINK.search(cell)
        registry[identifier] = root / "specs" / link.group(1) if link else None
    if not registry:
        raise ValueError("规格登记表为空或格式无法识别")
    return registry


def reference_errors(text, registry):
    errors = []
    for identifier in set(re.findall(r"\bSDD-\d{3}\b", text)):
        if identifier not in registry:
            errors.append(f"未知规格编号：{identifier}")
    for identifier, item in REFERENCE.findall(text):
        spec = registry.get(identifier)
        if not spec:
            errors.append(f"规格尚无契约或不存在：{identifier}/{item}")
            continue
        source = spec if item.startswith("AC-") else spec.with_name("tasks.md")
        content = source.read_text() if source.is_file() else ""
        pattern = (rf"^#+\s+{item}\b" if item.startswith("AC-")
                   else rf"^- \[[ x]\] \*\*{item}\b")
        if not re.search(pattern, content, re.M):
            errors.append(f"验收或任务未定义：{identifier}/{item}")
    return errors


def anchors(text):
    result, counts = set(), {}
    for heading in re.findall(r"^#{1,6}\s+(.+?)\s*#*\s*$", text, re.M):
        slug = re.sub(r"[^\w\- ]", "", heading.lower()).replace(" ", "-")
        count = counts.get(slug, 0)
        result.add(f"{slug}-{count}" if count else slug)
        counts[slug] = count + 1
    return result


def check_repository(root):
    """只检查版本控制范围及待加入文件；历史本地证据使用逐链接例外。"""
    root = root.resolve()
    tracked = subprocess.check_output(
        ["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"],
        cwd=root,
    ).decode().split("\0")
    files = {p for p in tracked if p}
    docs = sorted(p for p in files if p == "AGENTS.md" or p.startswith("specs/") and p.endswith(".md"))
    if "specs/README.md" not in files or "AGENTS.md" not in files:
        raise ValueError("缺少规格入口或 AGENTS.md")
    registry = registered_specs(root)
    policy = json.loads((root / ".ci/local-evidence-links.json").read_text())
    exceptions = {(item["document"], item["url"]) for item in policy}
    used = set()
    errors = []
    for name in docs:
        text = visible((root / name).read_text())
        errors.extend(f"{name}: {error}" for error in reference_errors(text, registry))
        for match in LINK.finditer(text):
            raw = match.group(1).strip("<>")
            if (name, raw) in exceptions:
                used.add((name, raw))
                continue
            url = urlsplit(raw)
            if url.scheme in {"https", "http", "mailto"}:
                continue
            target = ((root / name).parent / unquote(url.path)).resolve() if url.path else root / name
            try:
                relative = target.relative_to(root).as_posix()
            except ValueError:
                errors.append(f"{name}: 链接越出仓库：{raw}")
                continue
            exists_in_git = relative in files or any(p.startswith(relative.rstrip("/") + "/") for p in files)
            if url.scheme or not exists_in_git or not target.exists():
                errors.append(f"{name}: 链接未指向仓库文件：{raw}")
            elif url.fragment and target.suffix == ".md" and unquote(url.fragment) not in anchors(target.read_text()):
                errors.append(f"{name}: 标题锚点不存在：{raw}")
    for unused in exceptions - used:
        errors.append(f"未使用的本地证据例外：{unused}")
    return errors, registry, len(docs)


def check_pr(body, registry):
    body = visible(body or "")
    def field(name):
        match = re.search(rf"^{name}[：:][ \t]*(.*)$", body, re.M)
        value = match.group(1).strip() if match else ""
        return "" if value.lower() in {"", "todo", "待填写", "n/a", "无", "..."} else value
    refs, reason = field("规格引用"), field("无契约变化原因")
    errors = reference_errors(body, registry)
    if not any(item.startswith("AC-") for _, item in REFERENCE.findall(refs)) and len(reason) < 8:
        errors.append("PR 须填写完整 SDD/AC 引用，或至少 8 字的无契约变化原因")
    if len(field("验证结果")) < 8:
        errors.append("PR 须填写验证命令、结果及限制；至少 8 字")
    return errors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--event-path", type=Path)
    parser.add_argument("--event-name", default="local")
    args = parser.parse_args()
    try:
        errors, registry, count = check_repository(args.root)
        if args.event_name == "pull_request":
            if not args.event_path:
                raise ValueError("pull_request 缺少事件文件")
            event = json.loads(args.event_path.read_text())
            errors.extend(check_pr(event["pull_request"].get("body"), registry))
        if errors:
            print("\n".join(errors), file=sys.stderr)
            return 1
        print(f"规格检查通过：{count} 份文档，{len(registry)} 个规格编号；PR 检查：{args.event_name == 'pull_request'}")
        return 0
    except (OSError, ValueError, KeyError, subprocess.CalledProcessError) as error:
        print(f"规格检查失败：{error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
