#!/usr/bin/env bash
# Used by the manually dispatched release workflow. Do not enable shell tracing.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${DOCSCAN_KEYSTORE_BASE64:?Missing base64-encoded keystore secret}"
umask 077
keydir=$(mktemp -d "${RUNNER_TEMP:-/tmp}/docscan-key.XXXXXXXX")
trap 'rm -rf -- "$keydir"' EXIT
export DOCSCAN_KEYSTORE_PATH="$keydir/release.keystore"
printf '%s' "$DOCSCAN_KEYSTORE_BASE64" | base64 --decode > "$DOCSCAN_KEYSTORE_PATH"
unset DOCSCAN_KEYSTORE_BASE64
[[ -s "$DOCSCAN_KEYSTORE_PATH" ]] || { echo "Decoded keystore is empty." >&2; exit 1; }
tools/build_release.sh "$@"
