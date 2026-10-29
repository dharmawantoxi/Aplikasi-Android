#!/usr/bin/env python3
"""Exercise release orchestration with fake tools, never real keys or APKs."""
import base64
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class ReleaseScriptsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.project = self.root / "project"
        (self.project / "tools").mkdir(parents=True)
        (self.project / "ci").mkdir()
        shutil.copy2(ROOT / "tools/build_release.sh", self.project / "tools")
        shutil.copy2(ROOT / "ci/build-signed-release-from-secret.sh", self.project / "ci")
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.sdk = self.root / "sdk/build-tools/35.0.0"
        self.sdk.mkdir(parents=True)
        self.runner_temp = self.root / "runner"
        self.runner_temp.mkdir()
        resource_dir = self.project / "app/src/main/res/values"
        resource_dir.mkdir(parents=True)
        (resource_dir / "strings.xml").write_text('<resources><string name="privacy_policy_url">https://example.test/privacy</string></resources>')
        self.key = self.root / "fake.keystore"
        self.key.write_bytes(b"fake keystore - never a real key")
        self.env = dict(os.environ, PATH=f"{self.bin}:{os.environ.get('PATH', '')}",
                        ANDROID_HOME=str(self.sdk.parent.parent), RUNNER_TEMP=str(self.runner_temp),
                        DOCSCAN_KEYSTORE_PATH=str(self.key), DOCSCAN_STORE_PASSWORD="dummy-store-secret",
                        DOCSCAN_KEY_ALIAS="dummy-alias", DOCSCAN_KEY_PASSWORD="dummy-key-secret",
                        DOCSCAN_VERSION_CODE="42", DOCSCAN_VERSION_NAME="1.2.3-rc1",
                        DOCSCAN_ALLOW_INCOMPLETE_POLICY="0")
        self.make_executable(self.project / "gradlew", '''#!/usr/bin/env bash
set -eu
[ -s "$DOCSCAN_KEYSTORE_PATH" ] || exit 7
mkdir -p app/build/outputs/{bundle/release,apk/release,mapping/release}
echo fake-aab > app/build/outputs/bundle/release/app-release.aab
echo fake-apk > app/build/outputs/apk/release/app-release.apk
echo fake-mapping > app/build/outputs/mapping/release/mapping.txt
''')
        self.make_executable(self.bin / "jarsigner", '''#!/usr/bin/env bash
if [ "${FAKE_VERIFY_FAIL:-0}" = 1 ]; then exit 1; fi
if [ "${FAKE_UNSIGNED:-0}" = 1 ]; then echo 'jar is unsigned.'; exit 0; fi
echo 'jar verified.'
''')
        self.make_executable(self.sdk / "apksigner", '''#!/usr/bin/env bash
[[ "$*" == *'--min-sdk-version 24'* ]] || exit 7
[[ "${FAKE_VERIFY_FAIL:-0}" != 1 ]] || exit 1
echo 'Verified'
''')
        self.make_executable(self.sdk / "zipalign", '''#!/usr/bin/env bash
[[ "$*" == *'-P 16'* ]] || exit 7
[[ "${FAKE_ZIPALIGN_FAIL:-0}" != 1 ]] || exit 1
echo 'Aligned'
''')

    @staticmethod
    def make_executable(path, content):
        path.write_text(content)
        path.chmod(0o755)

    def run_script(self, script, kind="play-aab", env=None):
        result = subprocess.run(["bash", str(self.project / script), kind], cwd=self.project,
                                env=env or self.env, text=True, capture_output=True)
        self.assertNotIn("dummy-store-secret", result.stdout + result.stderr)
        self.assertNotIn("dummy-key-secret", result.stdout + result.stderr)
        return result

    def test_both_channels_create_verified_outputs_and_hashes(self):
        for kind, extension in [("play-aab", "aab"), ("direct-apk", "apk")]:
            with self.subTest(kind=kind):
                result = self.run_script("tools/build_release.sh", kind)
                self.assertEqual(result.returncode, 0, result.stderr)
                artifact = self.project / "release-output" / f"DocScanToxi-1.2.3-rc1-42.{extension}"
                self.assertTrue(artifact.is_file())
                checksum = (artifact.parent / f"{artifact.name}.sha256").read_text().split()[0]
                self.assertEqual(checksum, hashlib.sha256(artifact.read_bytes()).hexdigest())
                self.assertTrue((artifact.parent / f"{artifact.name}.mapping.txt").is_file())

    def test_missing_secrets_and_bad_versions_fail_before_build(self):
        for name, value in [("DOCSCAN_STORE_PASSWORD", ""), ("DOCSCAN_VERSION_CODE", "0"),
                            ("DOCSCAN_VERSION_CODE", "2100000001"), ("DOCSCAN_VERSION_CODE", "1;touch BAD"),
                            ("DOCSCAN_VERSION_NAME", "../../bad")]:
            with self.subTest(name=name, value=value):
                result = self.run_script("tools/build_release.sh", env={**self.env, name: value})
                self.assertNotEqual(result.returncode, 0)
                self.assertFalse((self.project / "app/build").exists())

    def test_unsigned_or_invalid_artifacts_do_not_escape(self):
        for kind, setting in [("play-aab", "FAKE_UNSIGNED"), ("play-aab", "FAKE_VERIFY_FAIL"),
                              ("direct-apk", "FAKE_VERIFY_FAIL"), ("direct-apk", "FAKE_ZIPALIGN_FAIL")]:
            with self.subTest(kind=kind, setting=setting):
                result = self.run_script("tools/build_release.sh", kind, {**self.env, setting: "1"})
                self.assertNotEqual(result.returncode, 0)
                self.assertFalse(list((self.project / "release-output").glob("*.sha256")))

    def test_encoded_key_is_temporary_and_removed_on_success_and_failure(self):
        env = {**self.env, "DOCSCAN_KEYSTORE_BASE64": base64.b64encode(self.key.read_bytes()).decode()}
        for fail in (False, True):
            with self.subTest(fail=fail):
                result = self.run_script("ci/build-signed-release-from-secret.sh", env={**env, "FAKE_VERIFY_FAIL": str(int(fail))})
                self.assertEqual(result.returncode == 0, not fail, result.stderr)
                self.assertEqual(list(self.runner_temp.iterdir()), [])

    def test_policy_url_is_required_unless_explicitly_test_only(self):
        resource = self.project / "app/src/main/res/values/strings.xml"
        for url in ("", "http://invalid.test/privacy", "https://user:password@example.test/"):
            with self.subTest(url=url):
                resource.write_text(f'<resources><string name="privacy_policy_url">{url}</string></resources>')
                result = self.run_script("tools/build_release.sh")
                self.assertNotEqual(result.returncode, 0)
                self.assertFalse((self.project / "app/build").exists())
        result = self.run_script("tools/build_release.sh", env={**self.env, "DOCSCAN_ALLOW_INCOMPLETE_POLICY": "1"})
        self.assertEqual(result.returncode, 0, result.stderr)
        marker = self.project / "release-output/DocScanToxi-1.2.3-rc1-42.aab.TEST_ONLY.txt"
        self.assertIn("NOT FOR PUBLICATION", marker.read_text())

    def test_invalid_base64_fails_and_key_is_removed(self):
        env = {**self.env, "DOCSCAN_KEYSTORE_BASE64": "%%%INVALID%%%"}
        result = self.run_script("ci/build-signed-release-from-secret.sh", env=env)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(list(self.runner_temp.iterdir()), [])


if __name__ == "__main__":
    unittest.main(verbosity=2)
