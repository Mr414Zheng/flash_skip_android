"""用故障输入验证 CI 会阻断缺失、跳过和错误证据。"""

import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from check_specs import check_pr, check_repository, registered_specs
from check_test_results import check_artifacts, check_tests


class SpecificationChecksTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        subprocess.run(["git", "init", "-q", str(self.root)], check=True)
        self.write(".gitignore", "app/build/\n")
        self.write("AGENTS.md", "[规格](specs/README.md)\n")
        self.write("specs/README.md", "# 规格\n\n| SDD-001 / C01 保存 | [保存](save/spec.md) | 已审定 |\n| SDD-002 / C02 规则 | `rules/` | 待编写 |\n")
        self.write("specs/save/spec.md", "# 保存\n\n### AC-01 保存成功\n")
        self.write("specs/save/tasks.md", "- [ ] **T-01 保存**\n")
        self.write(".ci/local-evidence-links.json", "[]\n")
        self.registry = registered_specs(self.root)

    def write(self, path, text):
        target = self.root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text)

    def errors_for(self, text):
        self.write("specs/note.md", text)
        return check_repository(self.root)[0]

    def test_valid_repository_and_planned_directory(self):
        self.assertEqual([], self.errors_for("SDD-001/AC-01；SDD-001/T-01；SDD-002 待编写"))

    def test_unknown_specification(self):
        self.assertTrue(self.errors_for("SDD-999"))

    def test_missing_acceptance_or_task(self):
        for ref in ("SDD-001/AC-99", "SDD-001/T-99", "SDD-002/AC-01"):
            with self.subTest(ref=ref):
                self.assertTrue(self.errors_for(ref))

    def test_broken_link_and_anchor(self):
        for url in ("missing.md", "save/spec.md#missing", "../../../outside.md"):
            with self.subTest(url=url):
                self.assertTrue(self.errors_for(f"[错误]({url})"))
        self.assertEqual([], self.errors_for("[正常](save/spec.md#ac-01-保存成功)"))

    def test_existing_ignored_report_is_not_repository_evidence(self):
        self.write("app/build/report.html", "old report")
        self.assertTrue(self.errors_for("[报告](../app/build/report.html)"))

    def test_exact_historical_exception_without_local_report(self):
        self.write(".ci/local-evidence-links.json", json.dumps([
            {"document": "specs/note.md", "url": "../app/build/report.html", "reason": "历史证据"}
        ]))
        self.assertEqual([], self.errors_for("[历史](../app/build/report.html)"))
        self.assertTrue(self.errors_for("[新错误](../app/build/typo.html)"))

    def test_duplicate_registry(self):
        text = (self.root / "specs/README.md").read_text()
        self.write("specs/README.md", text + "| SDD-001 / C01 重复 | `other/` | 待编写 |\n")
        with self.assertRaises(ValueError):
            check_repository(self.root)

    def test_valid_pr_with_contract(self):
        self.assertEqual([], check_pr("规格引用：SDD-001/AC-01\n验证结果：执行现有测试，75 例全部通过。", self.registry))

    def test_valid_pr_without_contract_change(self):
        self.assertEqual([], check_pr("无契约变化原因：仅添加 CI 配置，不改变产品行为。\n验证结果：本地检查全部通过，远端尚未运行。", self.registry))

    def test_empty_template_and_comment_examples_do_not_pass(self):
        template = "规格引用：\n<!-- SDD-001/AC-01 -->\n无契约变化原因：\n验证结果：\n"
        self.assertEqual(2, len(check_pr(template, self.registry)))
        self.assertTrue(check_pr(None, self.registry))

    def test_pr_missing_results_or_invalid_reference(self):
        for body in (
            "规格引用：SDD-001/AC-01\n验证结果：待填写",
            "规格引用：SDD-001/AC-99\n无契约变化原因：仅更新工作流，不改变生产代码。\n验证结果：本地验证已经全部通过。",
            "规格引用：SDD-001\n验证结果：本地验证已经全部通过。",
            "规格引用：SDD-001/T-01\n验证结果：本地验证已经全部通过。",
        ):
            with self.subTest(body=body):
                self.assertTrue(check_pr(body, self.registry))

    def test_missing_pr_event_exits_nonzero(self):
        script = Path(__file__).resolve().parents[1] / "check_specs.py"
        result = subprocess.run([sys.executable, str(script), "--root", str(self.root), "--event-name", "pull_request"], capture_output=True)
        self.assertEqual(1, result.returncode)


class ReportChecksTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.reports = self.root / "reports"
        self.reports.mkdir()
        self.inventory = self.root / "required.json"
        self.inventory.write_text(json.dumps({"required_cases": {"CaptureTest": ["save[CAPTURED]", "save[AWAITING_CONFIRMATION]"]}}))
        self.report = self.reports / "TEST-CaptureTest.xml"
        self.write_suite()

    def write_suite(self, names=None, status=None):
        names = ["save[CAPTURED]", "save[AWAITING_CONFIRMATION]"] if names is None else names
        suite = ET.Element("testsuite", name="CaptureTest", tests=str(len(names)), failures="0", errors="0", skipped="0")
        for name in names:
            case = ET.SubElement(suite, "testcase", classname="CaptureTest", name=name)
            if status:
                ET.SubElement(case, status)
        ET.ElementTree(suite).write(self.report)

    def check(self):
        return check_tests(self.reports, self.inventory)

    def test_complete_parameterized_cases_pass(self):
        self.assertEqual(([], 2), self.check())

    def test_missing_report(self):
        self.report.unlink()
        with self.assertRaises(ValueError):
            self.check()

    def test_truncated_report(self):
        self.report.write_text("<testsuite>")
        with self.assertRaises(ET.ParseError):
            self.check()

    def test_zero_cases(self):
        self.write_suite(names=[])
        self.assertTrue(self.check()[0])

    def test_removed_parameter_case(self):
        self.write_suite(names=["save[CAPTURED]"])
        self.assertTrue(self.check()[0])

    def test_renamed_case_with_same_total_cannot_hide_removal(self):
        self.write_suite(names=["save[CAPTURED]", "unrelated"])
        self.assertTrue(self.check()[0])

    def test_failure_error_and_skip_even_when_summary_says_zero(self):
        for status in ("failure", "error", "skipped"):
            with self.subTest(status=status):
                self.write_suite(status=status)
                self.assertTrue(self.check()[0])

    def test_nonzero_summary_without_child_failure(self):
        tree = ET.parse(self.report)
        tree.getroot().set("failures", "1")
        tree.write(self.report)
        self.assertTrue(self.check()[0])

    def test_duplicate_cases(self):
        self.write_suite(names=["save[CAPTURED]", "save[CAPTURED]"])
        self.assertTrue(self.check()[0])

    def test_count_mismatch(self):
        tree = ET.parse(self.report)
        tree.getroot().set("tests", "75")
        tree.write(self.report)
        self.assertTrue(self.check()[0])

    def test_empty_inventory(self):
        self.inventory.write_text('{"required_cases": {}}')
        with self.assertRaises(ValueError):
            self.check()

    def test_cli_missing_reports_exits_nonzero(self):
        script = Path(__file__).resolve().parents[1] / "check_test_results.py"
        (self.root / ".ci").mkdir()
        (self.root / ".ci/required-tests.json").write_text(self.inventory.read_text())
        result = subprocess.run([sys.executable, str(script), "--root", str(self.root)], capture_output=True)
        self.assertEqual(1, result.returncode)
        self.assertIn("未生成 JUnit XML", result.stderr.decode())


class ArtifactChecksTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        for name in ("app/build/reports/tests/testDebugUnitTest/index.html", "app/build/reports/lint-results-debug.html", "app/build/outputs/apk/debug/app-debug.apk"):
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("fixture")
        self.lint = self.root / "app/build/reports/lint-results-debug.xml"
        self.lint.write_text('<issues><issue id="ExistingWarning" severity="Warning" /></issues>')

    def test_warning_is_reported_without_blocking(self):
        self.assertEqual(([], {"ExistingWarning": 1}), check_artifacts(self.root))

    def test_lint_error_is_blocking(self):
        for severity in ("Error", "Fatal"):
            with self.subTest(severity=severity):
                self.lint.write_text(f'<issues><issue id="Failure" severity="{severity}" /></issues>')
                self.assertTrue(check_artifacts(self.root)[0])

    def test_missing_html_or_apk(self):
        (self.root / "app/build/reports/lint-results-debug.html").unlink()
        self.assertTrue(check_artifacts(self.root)[0])

    def test_missing_lint_xml(self):
        self.lint.unlink()
        with self.assertRaises(OSError):
            check_artifacts(self.root)


if __name__ == "__main__":
    unittest.main()
