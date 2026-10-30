#!/usr/bin/env python3
"""Host-side checks for CI's Android test-result annotations."""
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET

SCRIPT = Path(__file__).with_name("report-device-test-failures.py")


class DeviceTestReportChecks(unittest.TestCase):
    def run_report(self, directory):
        return subprocess.run([sys.executable, str(SCRIPT), str(directory)], check=True,
                              text=True, capture_output=True).stdout

    def test_reports_failures_with_escaped_details(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp) / "debug"
            directory.mkdir()
            root = ET.Element("testsuite")
            failed = ET.SubElement(root, "testcase", classname="com.example.Photo", name="rotation%test")
            ET.SubElement(failed, "failure", message="Wrong value").text = "AssertionError\n at Photo.java:80"
            error = ET.SubElement(root, "testcase", classname="com.example.Db", name="save")
            ET.SubElement(error, "error", message="Database unavailable")
            ET.SubElement(root, "testcase", classname="com.example.Other", name="passes")
            ET.ElementTree(root).write(directory / "results.xml", encoding="utf-8")
            output = self.run_report(temp)
            self.assertIn("Found 2 failing Android test(s)", output)
            self.assertIn("com.example.Photo#rotation%25test%0AAssertionError%0A at Photo.java:80", output)
            self.assertIn("com.example.Db#save%0ADatabase unavailable", output)
            self.assertEqual(2, output.count("::error title=Android device test failed::"))

    def test_missing_results_do_not_hide_original_failure(self):
        with tempfile.TemporaryDirectory() as temp:
            output = self.run_report(Path(temp) / "missing")
            self.assertIn("No Android test result XML found", output)
            self.assertNotIn("::error", output)

    def test_namespace_and_annotation_limit(self):
        with tempfile.TemporaryDirectory() as temp:
            root = ET.Element("{urn:junit}testsuite")
            for index in range(12):
                case = ET.SubElement(root, "{urn:junit}testcase", classname="example", name=f"test{index}")
                ET.SubElement(case, "{urn:junit}failure").text = "failed"
            ET.ElementTree(root).write(Path(temp) / "result.xml", encoding="utf-8")
            output = self.run_report(temp)
            self.assertIn("Found 12 failing Android test(s)", output)
            self.assertEqual(10, output.count("::error title=Android device test failed::"))
            self.assertIn("2 additional failure(s)", output)

    def test_long_result_and_broken_xml_are_handled(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            (directory / "broken.xml").write_text("<not-closed", encoding="utf-8")
            root = ET.Element("testsuite")
            case = ET.SubElement(root, "testcase", classname="example", name="long")
            ET.SubElement(case, "failure").text = "x" * 7000
            ET.ElementTree(root).write(directory / "result.xml", encoding="utf-8")
            output = self.run_report(directory)
            self.assertIn("Could not read Android test result", output)
            self.assertIn("truncated; see the full test report artifact", output)
            self.assertEqual(1, output.count("::error title=Android device test failed::"))


if __name__ == "__main__":
    unittest.main()
