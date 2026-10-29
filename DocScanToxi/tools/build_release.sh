#!/usr/bin/env bash
# Build exactly one release artifact. Never write keys or passwords into the repository.
set -euo pipefail
cd "$(dirname "$0")/.."

usage() { echo "Usage: tools/build_release.sh play-aab|direct-apk" >&2; exit 2; }
[[ $# == 1 ]] || usage
kind="$1"
case "$kind" in play-aab|direct-apk) ;; *) usage ;; esac
for name in DOCSCAN_KEYSTORE_PATH DOCSCAN_STORE_PASSWORD DOCSCAN_KEY_ALIAS DOCSCAN_KEY_PASSWORD DOCSCAN_VERSION_CODE DOCSCAN_VERSION_NAME; do
  if [[ -z "${!name:-}" ]]; then echo "Missing required environment variable: $name" >&2; exit 2; fi
done
[[ -f "$DOCSCAN_KEYSTORE_PATH" ]] || { echo "Keystore does not exist." >&2; exit 2; }
[[ "$DOCSCAN_KEYSTORE_PATH" = /* ]] || { echo "Use an absolute DOCSCAN_KEYSTORE_PATH." >&2; exit 2; }
[[ "$DOCSCAN_VERSION_CODE" =~ ^[1-9][0-9]{0,9}$ ]] && (( 10#$DOCSCAN_VERSION_CODE <= 2100000000 )) || { echo "Invalid DOCSCAN_VERSION_CODE." >&2; exit 2; }
[[ "$DOCSCAN_VERSION_NAME" =~ ^[A-Za-z0-9][A-Za-z0-9._+-]{0,79}$ ]] || { echo "Invalid DOCSCAN_VERSION_NAME." >&2; exit 2; }
[[ "${DOCSCAN_ALLOW_INCOMPLETE_POLICY:-0}" == 0 || "${DOCSCAN_ALLOW_INCOMPLETE_POLICY:-0}" == 1 ]] || { echo "Invalid DOCSCAN_ALLOW_INCOMPLETE_POLICY." >&2; exit 2; }
# A release should not silently omit the public policy link. A deliberate test-only
# build can proceed, but receives a visible NOT FOR PUBLICATION marker.
if ! python3 - <<'PYCHECK'
import xml.etree.ElementTree as ET
from urllib.parse import urlsplit
try:
    root = ET.parse("app/src/main/res/values/strings.xml").getroot()
    element = root.find("string[@name='privacy_policy_url']")
    url = "" if element is None else "".join(element.itertext()).strip()
    parsed = urlsplit(url)
    valid = parsed.scheme == "https" and bool(parsed.hostname) and not parsed.username and not parsed.password and not any(c.isspace() for c in url)
except (OSError, ValueError, ET.ParseError):
    valid = False
raise SystemExit(0 if valid else 1)
PYCHECK
then
  if [[ "${DOCSCAN_ALLOW_INCOMPLETE_POLICY:-0}" != 1 ]]; then
    echo "A public HTTPS privacy_policy_url must be configured before a release build. For testing only, see RELEASE_CHECKLIST.md." >&2
    exit 2
  fi
  echo "WARNING: privacy policy URL missing or invalid; this build is for testing only." >&2
fi

if [[ "$kind" == play-aab ]]; then
  command -v jarsigner >/dev/null || { echo "jarsigner (JDK 17) not found." >&2; exit 2; }
  task=bundleRelease
  source=app/build/outputs/bundle/release/app-release.aab
  output="DocScanToxi-${DOCSCAN_VERSION_NAME}-${DOCSCAN_VERSION_CODE}.aab"
else
  sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  [[ -n "$sdk" && -d "$sdk/build-tools" ]] || { echo "ANDROID_HOME must point to an Android SDK with build-tools." >&2; exit 2; }
  # Use the newest installed build-tools that includes both commands.
  apksigner=""; zipalign=""
  while IFS= read -r dir; do
    if [[ -x "$dir/apksigner" && -x "$dir/zipalign" ]]; then apksigner="$dir/apksigner"; zipalign="$dir/zipalign"; break; fi
  done < <(find "$sdk/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -Vr)
  [[ -n "$apksigner" ]] || { echo "apksigner/zipalign not found in Android SDK build-tools." >&2; exit 2; }
  task=assembleRelease
  source=app/build/outputs/apk/release/app-release.apk
  output="DocScanToxi-${DOCSCAN_VERSION_NAME}-${DOCSCAN_VERSION_CODE}.apk"
fi

# Remove only this target and the helper metadata so a failed build cannot look successful.
mkdir -p release-output
rm -f "release-output/$output" "release-output/$output.sha256" "release-output/$output.mapping.txt" "release-output/$output.TEST_ONLY.txt"
./gradlew testReleaseUnitTest "$task" --no-daemon --stacktrace \
  "-PdocscanVersionCode=$DOCSCAN_VERSION_CODE" "-PdocscanVersionName=$DOCSCAN_VERSION_NAME"
[[ -s "$source" ]] || { echo "Signed release artifact not found: $source" >&2; exit 1; }
if [[ "$kind" == play-aab ]]; then
  verify_log=$(mktemp)
  trap 'rm -f "$verify_log"' EXIT
  LC_ALL=C jarsigner -verify -certs "$source" > "$verify_log" 2>&1 || { cat "$verify_log" >&2; exit 1; }
  if ! grep -q '^jar verified\.' "$verify_log" || grep -qi 'unsigned entries' "$verify_log"; then
    cat "$verify_log" >&2; echo "AAB signature verification failed or entries are unsigned." >&2; exit 1
  fi
  cat "$verify_log"
else
  "$apksigner" verify --verbose --print-certs --min-sdk-version 24 "$source"
  "$zipalign" -c -P 16 -v 4 "$source"
fi
cp "$source" "release-output/$output"
if [[ -f app/build/outputs/mapping/release/mapping.txt ]]; then
  cp app/build/outputs/mapping/release/mapping.txt "release-output/$output.mapping.txt"
fi
(cd release-output && sha256sum "$output" > "$output.sha256")
if [[ "${DOCSCAN_ALLOW_INCOMPLETE_POLICY:-0}" == 1 ]]; then
  echo "TEST ONLY - NOT FOR PUBLICATION. Complete and verify the release checklist before building a production artifact." > "release-output/$output.TEST_ONLY.txt"
fi
echo "Verified $kind output: release-output/$output"
