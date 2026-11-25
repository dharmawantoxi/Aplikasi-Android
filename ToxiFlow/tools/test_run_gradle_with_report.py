#!/usr/bin/env python3
"""Uji runner Gradle dengan gradlew palsu (tanpa Java/Android SDK)."""
import stat
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import run_gradle_with_report as runner  # noqa: E402


def fake_project(directory: Path, bodies: list[str]) -> tuple[Path, Path]:
    """Buat proyek palsu; setiap pemanggilan gradlew memakai isi ``bodies`` berurutan."""
    counter = directory / "calls"
    counter.write_text("0", encoding="utf-8")
    script = directory / "gradlew"
    script.write_text(
        "#!/usr/bin/env bash\n"
        f"count=$(cat {counter})\n"
        f"bodies=(\n"
        + "".join(f"  {body!r}\n" for body in bodies)
        + ")\n"
        f"index=$count\n"
        f"if [ $index -ge ${{#bodies[@]}} ]; then index=$(({{#bodies[@]}}-1)); fi\n"
        'if [ "$index" -lt 0 ]; then index=0; fi\n'
        'printf "%s\\n" "${bodies[$index]}"\n'
        f"echo $((count+1)) > {counter}\n"
        "exit_file=" + str(directory / "exit_codes") + "\n"
        "codes=$(cat \"$exit_file\")\n"
        "code=$(echo \"$codes\" | cut -d' ' -f$((index+1)))\n"
        'exit "${code:-1}"\n',
        encoding="utf-8",
    )
    script.chmod(script.stat().st_mode | stat.S_IEXEC)
    return script, counter


class RunGradleTests(unittest.TestCase):
    def test_success_on_first_attempt(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            (directory / "exit_codes").write_text("0", encoding="utf-8")
            fake_project(directory, ["BUILD SUCCESSFUL in 51s"])
            log = directory / "build.log"
            code = runner.main(["--log", str(log), "--cwd", str(directory), "--", "assembleDebug"])
            self.assertEqual(code, 0)
            self.assertIn("BUILD SUCCESSFUL", log.read_text(encoding="utf-8"))

    def test_network_failure_is_retried_then_succeeds(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            (directory / "exit_codes").write_text("1 0", encoding="utf-8")
            fake_project(directory, [
                "Could not get resource 'https://dl.google.com/android/maven2/x.jar'",
                "BUILD SUCCESSFUL in 48s",
            ])
            log = directory / "build.log"
            code = runner.main(["--log", str(log), "--cwd", str(directory), "--", "assembleDebug"])
            self.assertEqual(code, 0)
            self.assertIn("BUILD SUCCESSFUL", log.read_text(encoding="utf-8"))

    def test_compile_failure_is_not_retried(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            (directory / "exit_codes").write_text("1 0", encoding="utf-8")
            fake_project(directory, [
                "e: Unresolved reference: foo",
                "BUILD SUCCESSFUL in 48s",
            ])
            log = directory / "build.log"
            code = runner.main(["--log", str(log), "--cwd", str(directory), "--", "assembleDebug"])
            self.assertNotEqual(code, 0)
            self.assertIn("Unresolved reference", log.read_text(encoding="utf-8"))

    def test_network_failure_on_last_attempt_still_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            (directory / "exit_codes").write_text("1 1", encoding="utf-8")
            fake_project(directory, [
                "java.net.SocketTimeoutException: Read timed out",
                "java.net.SocketTimeoutException: Read timed out",
            ])
            log = directory / "build.log"
            code = runner.main(["--log", str(log), "--cwd", str(directory), "--", "assembleDebug"])
            self.assertNotEqual(code, 0)

    def test_log_keeps_every_attempt(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            (directory / "exit_codes").write_text("1 0", encoding="utf-8")
            fake_project(directory, [
                "java.net.SocketTimeoutException: Read timed out",
                "BUILD SUCCESSFUL in 48s",
            ])
            log = directory / "build.log"
            code = runner.main(["--log", str(log), "--cwd", str(directory), "--", "assembleDebug"])
            self.assertEqual(code, 0)
            text = log.read_text(encoding="utf-8")
            self.assertIn("Percobaan 1/2", text)
            self.assertIn("Percobaan 2/2", text)
            self.assertIn("Read timed out", text)
            self.assertIn("BUILD SUCCESSFUL", text)

    def test_missing_gradle_task_is_rejected(self):
        with self.assertRaises(SystemExit):
            runner.main(["--log", "/tmp/x.log"])


if __name__ == "__main__":
    unittest.main()
