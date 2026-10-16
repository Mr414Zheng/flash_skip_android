"""实际 Release 报告独立于 Debug 回归，缺失和伪变体必须阻断。"""
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from check_test_results import check_tests, check_artifacts


class ReleaseChecksTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.reports = self.root / "app/build/test-results/testReleaseUnitTest"
        self.reports.mkdir(parents=True)
        self.inventory = self.root / ".ci/required-tests-release.json"
        self.inventory.parent.mkdir()
        self.class_name = "com.zhenghuaizhi.flashskip.platform.accessibility.ReleaseDiagnosticBoundaryTest"
        self.inventory.write_text(json.dumps({"variant": "release", "required_cases": {self.class_name: ["gate"]}}))
        self.report = self.reports / f"TEST-{self.class_name}.xml"
        self.suite = ET.Element("testsuite", name=self.class_name, tests="1", failures="0", errors="0", skipped="0")
        self.case = ET.SubElement(self.suite, "testcase", classname=self.class_name, name="gate")
        self.output = ET.SubElement(self.suite, "system-out")
        self.output.text = "build-variant=release; debug=false"
        self.write()

    def write(self):
        ET.ElementTree(self.suite).write(self.report)

    def check(self):
        return check_tests(self.reports, self.inventory, "release")

    def test_actual_release_identity_passes(self):
        self.assertEqual(([], 1), self.check())

    def test_missing_release_reports_cannot_reuse_debug(self):
        debug = self.root / "app/build/test-results/testDebugUnitTest"
        debug.mkdir()
        self.report.rename(debug / self.report.name)
        with self.assertRaises(ValueError):
            self.check()

    def test_missing_release_case_is_rejected(self):
        self.case.set("name", "unrelated")
        self.write()
        self.assertTrue(self.check()[0])

    def test_duplicate_release_identity_is_rejected(self):
        ET.SubElement(self.suite, "testcase", classname=self.class_name, name="gate")
        self.suite.set("tests", "2")
        self.write()
        self.assertTrue(self.check()[0])

    def test_failure_error_and_skip_are_rejected(self):
        for status in ("failure", "error", "skipped"):
            with self.subTest(status=status):
                child = ET.SubElement(self.case, status)
                self.write()
                self.assertTrue(self.check()[0])
                self.case.remove(child)

    def test_wrong_runtime_variant_is_rejected(self):
        for output in ("build-variant=debug; debug=true", "build-variant=release; debug=true", ""):
            with self.subTest(output=output):
                self.output.text = output
                self.write()
                self.assertTrue(self.check()[0])

    def test_release_marker_in_unrelated_class_is_insufficient(self):
        self.suite.set("name", "Unrelated")
        self.write()
        self.assertTrue(self.check()[0])

    def test_wrong_inventory_variant_is_rejected(self):
        document = json.loads(self.inventory.read_text())
        document["variant"] = "debug"
        self.inventory.write_text(json.dumps(document))
        with self.assertRaises(ValueError):
            self.check()

    def test_empty_release_suite_is_rejected(self):
        self.suite.remove(self.case)
        self.suite.set("tests", "0")
        self.write()
        self.assertTrue(self.check()[0])

    def test_cli_selects_release_inventory_and_report(self):
        script = Path(__file__).resolve().parents[1] / "check_test_results.py"
        result = subprocess.run([sys.executable, str(script), "--root", str(self.root), "--variant", "release"], capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("release JUnit", result.stdout)

    def test_release_artifacts_and_lint_are_required(self):
        names = ["app/build/reports/tests/testReleaseUnitTest/index.html", "app/build/reports/lint-results-release.html", "app/build/outputs/apk/release/app-release-unsigned.apk"]
        for name in names:
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("fixture")
        lint = self.root / "app/build/reports/lint-results-release.xml"
        lint.write_text('<issues><issue id="ExistingWarning" severity="Warning" /></issues>')
        self.assertEqual(([], {"ExistingWarning": 1}), check_artifacts(self.root, "release"))
        (self.root / names[-1]).unlink()
        self.assertTrue(check_artifacts(self.root, "release")[0])
        lint.write_text('<issues><issue id="Failure" severity="Error" /></issues>')
        self.assertTrue(check_artifacts(self.root, "release")[0])


if __name__ == "__main__":
    unittest.main()
