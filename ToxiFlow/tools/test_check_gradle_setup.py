#!/usr/bin/env python3
"""Uji pemeriksaan konsistensi Gradle/AGP memakai proyek sementara."""
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import check_gradle_setup as check  # noqa: E402

BUILD = 'plugins {\n    id("com.android.application") version "8.13.2" apply false\n}\n'
CATALOG = '[versions]\nandroidGradlePlugin = "8.13.2"\n'
WRAPPER = (
    "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.13-bin.zip\n"
    "distributionSha256Sum=" + "a" * 64 + "\n"
)


def project(directory: Path, build=BUILD, catalog=CATALOG, wrapper=WRAPPER) -> Path:
    (directory / "gradle" / "wrapper").mkdir(parents=True, exist_ok=True)
    (directory / "build.gradle.kts").write_text(build, encoding="utf-8")
    (directory / "gradle" / "libs.versions.toml").write_text(catalog, encoding="utf-8")
    (directory / "gradle" / "wrapper" / "gradle-wrapper.properties").write_text(wrapper, encoding="utf-8")
    return directory


class SetupCheckTests(unittest.TestCase):
    def test_repository_configuration_is_consistent(self):
        self.assertEqual(check.check(check.ROOT), [])

    def test_version_mismatch_is_reported(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = project(Path(tmp), catalog='[versions]\nandroidGradlePlugin = "8.12.0"\n')
            problems = check.check(root)
            self.assertTrue(any("tidak sinkron" in problem for problem in problems))

    def test_old_gradle_is_reported(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = project(Path(tmp), wrapper=(
                "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.5-bin.zip\n"
                "distributionSha256Sum=" + "b" * 64 + "\n"
            ))
            problems = check.check(root)
            self.assertTrue(any("terlalu lama" in problem for problem in problems))

    def test_missing_checksum_is_reported(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = project(Path(tmp), wrapper=(
                "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.13-bin.zip\n"
            ))
            problems = check.check(root)
            self.assertTrue(any("distributionSha256Sum" in problem for problem in problems))

    def test_missing_files_are_reported_not_crashing(self):
        with tempfile.TemporaryDirectory() as tmp:
            problems = check.check(Path(tmp))
            self.assertTrue(any("build.gradle.kts" in problem for problem in problems))
            self.assertEqual(check.main(["--root", tmp]), 1)


if __name__ == "__main__":
    unittest.main()
