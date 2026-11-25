#!/usr/bin/env python3
"""Uji ringkasan log Gradle dan deteksi kegagalan sementara tanpa jaringan."""
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import gradle_log_report as report  # noqa: E402

SUCCESS = """
> Task :app:preBuild UP-TO-DATE
> Task :app:compileDebugJavaWithJavac
BUILD SUCCESSFUL in 51s
"""

COMPILE_ERROR = """
> Task :app:compileDebugJavaWithJavac FAILED
e: file:///home/runner/work/app/src/main/java/com/example/App.kt:12:5 Unresolved reference: foo
FAILURE: Build failed with an exception.
* What went wrong:
Execution failed for task ':app:compileDebugJavaWithJavac'.
"""

NETWORK_ERROR = """
Downloading https://services.gradle.org/distributions/gradle-8.13-bin.zip
Exception in thread "main" java.io.IOException: Could not get resource 'https://dl.google.com/dl/android/maven2/...'
Caused by: java.io.EOFException: Unexpected end of file from server
"""

WRAPPER_DOWNLOAD = """
Exception in thread "main" java.net.SocketTimeoutException: Read timed out
"""


class GradleLogReportTests(unittest.TestCase):
    def test_success_log_has_no_annotations(self):
        code, lines = report.report(SUCCESS)
        self.assertEqual(code, 0)
        self.assertEqual(lines, [])

    def test_compile_error_is_reported_as_error_exit(self):
        code, lines = report.report(COMPILE_ERROR)
        self.assertEqual(code, 1)
        self.assertTrue(any("Unresolved reference" in line for line in lines))
        self.assertTrue(all(line.startswith("::error::") for line in lines))

    def test_network_failure_is_marked_transient(self):
        for log in (NETWORK_ERROR, WRAPPER_DOWNLOAD):
            with self.subTest(log=log[:40]):
                code, lines = report.report(log)
                self.assertEqual(code, 3)
                self.assertTrue(lines[0].startswith("::warning::"))
                self.assertGreater(len(lines), 1)

    def test_annotations_are_deduplicated_and_capped(self):
        log = "\n".join(["e: duplicate"] * 5 + [f"error: unique {i}" for i in range(20)])
        code, lines = report.report(log)
        self.assertEqual(code, 1)
        self.assertLessEqual(len(lines), report.MAX_ANNOTATIONS)
        self.assertEqual(len(set(lines)), len(lines))

    def test_newlines_and_percent_are_escaped(self):
        self.assertEqual(report.escape("50%\nbaris"), "50%25%0Abaris")

    def test_unknown_failure_can_be_forced(self):
        with tempfile.TemporaryDirectory() as directory:
            log = Path(directory) / "gradle.log"
            log.write_text(SUCCESS, encoding="utf-8")
            self.assertEqual(report.main([str(log)]), 0)
            self.assertEqual(report.main([str(log), "--require-failure"]), 1)

    def test_missing_log_is_an_error(self):
        self.assertEqual(report.main(["/nonexistent/gradle.log"]), 1)


if __name__ == "__main__":
    unittest.main()
