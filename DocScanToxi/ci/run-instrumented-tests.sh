#!/usr/bin/env bash
set -uo pipefail
cd "$(dirname "$0")/.."
log="${RUNNER_TEMP:-/tmp}/docscan-device-tests.log"
./gradlew connectedDebugAndroidTest --no-daemon --stacktrace > "$log" 2>&1
result=$?
cat "$log"
if [ "$result" -ne 0 ]; then
  python3 - "$log" <<'PYLOG'
import sys
lines = open(sys.argv[1], errors="replace").read().splitlines()
primary = [s for s in lines if s.startswith("e: ") or " error:" in s.lower() or " FAILED" in s or "FATAL EXCEPTION" in s]
fallback = [s for s in lines if s.strip().startswith(("java.lang.", "android.", "Execution failed", "Caused by:", "FAILURE:"))]
seen = set()
for line in primary + fallback:
    line = line.strip()[:2000]
    if not line or line in seen:
        continue
    seen.add(line)
    print("::error::" + line.replace("%", "%25"))
    if len(seen) >= 10:
        break
PYLOG
fi
exit "$result"
