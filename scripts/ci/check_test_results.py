"""校验 Gradle JUnit XML、关键用例和构建报告，缺失证据按失败处理。"""

import argparse
from collections import Counter
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET


def check_tests(report_dir, inventory, variant="debug"):
    document = json.loads(inventory.read_text())
    if document.get("variant", "debug") != variant:
        raise ValueError("必需用例清单与被测构建变体不一致")
    required = document["required_cases"]
    expected = {(name, case) for name, cases in required.items() for case in cases}
    if not expected or sum(map(len, required.values())) != len(expected):
        raise ValueError("必需用例清单为空或存在重复")
    reports = sorted(report_dir.glob("TEST-*.xml"))
    if not reports:
        raise ValueError("未生成 JUnit XML；NO-SOURCE 或未执行不能算通过")
    seen, errors = set(), []
    release_identity_seen = False
    for path in reports:
        suite = ET.parse(path).getroot()
        cases = suite.findall("testcase")
        if suite.get("name") == "com.zhenghuaizhi.flashskip.platform.accessibility.ReleaseDiagnosticBoundaryTest":
            output = suite.findtext("system-out", "")
            release_identity_seen = "build-variant=release; debug=false" in output
            if "build-variant=debug" in output or "debug=true" in output:
                errors.append("Release 报告包含错误构建变体")
        if suite.tag != "testsuite" or not cases or int(suite.get("tests", "-1")) != len(cases):
            errors.append(f"{path.name}: 空报告、结构或用例计数不一致")
        for status in ("failures", "errors", "skipped"):
            if int(suite.get(status, "-1")) != 0:
                errors.append(f"{path.name}: {status}={suite.get(status)}")
        for case in cases:
            identity = (case.get("classname"), case.get("name"))
            if not all(identity) or identity in seen:
                errors.append(f"用例身份缺失或重复：{identity}")
            seen.add(identity)
            if any(case.find(tag) is not None for tag in ("failure", "error", "skipped")):
                errors.append(f"失败、错误或跳过的用例：{identity}")
    for missing in sorted(expected - seen):
        errors.append(f"必需用例缺失：{missing}")
    if variant == "release" and not release_identity_seen:
        errors.append("缺少实际 Release 诊断测试的构建身份输出")
    return errors, len(seen)


def check_artifacts(root, variant="debug"):
    task = "testDebugUnitTest" if variant == "debug" else "testReleaseUnitTest"
    apk = "app-debug.apk" if variant == "debug" else "app-release-unsigned.apk"
    required = [
        f"app/build/reports/tests/{task}/index.html",
        f"app/build/reports/lint-results-{variant}.html",
        f"app/build/outputs/apk/{variant}/{apk}",
    ]
    errors = [f"构建产物或报告缺失/为空：{p}" for p in required
              if not (root / p).is_file() or (root / p).stat().st_size == 0]
    lint = ET.parse(root / f"app/build/reports/lint-results-{variant}.xml").getroot()
    if lint.tag != "issues":
        errors.append("Lint 报告结构错误")
    counts = Counter()
    for issue in lint.findall("issue"):
        severity = issue.get("severity")
        if severity in {"Error", "Fatal"} or severity not in {"Warning", "Information", "Ignore"}:
            errors.append(f"Lint 阻断：{issue.get('id')} ({severity})")
        if severity == "Warning":
            counts[issue.get("id")] += 1
    return errors, dict(counts)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--require-artifacts", action="store_true")
    parser.add_argument("--variant", choices=("debug", "release"), default="debug")
    args = parser.parse_args()
    try:
        task = "testDebugUnitTest" if args.variant == "debug" else "testReleaseUnitTest"
        inventory = "required-tests.json" if args.variant == "debug" else "required-tests-release.json"
        errors, count = check_tests(args.root / "app/build/test-results" / task, args.root / ".ci" / inventory, args.variant)
        warnings = {}
        if args.require_artifacts:
            artifact_errors, warnings = check_artifacts(args.root, args.variant)
            errors.extend(artifact_errors)
        if errors:
            print("\n".join(errors), file=sys.stderr)
            return 1
        print(f"{args.variant} JUnit 检查通过：{count} 例，0 失败 / 错误 / 跳过；Lint 警告：{warnings}")
        return 0
    except (OSError, ValueError, KeyError, TypeError, ET.ParseError) as error:
        print(f"报告检查失败：{error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
