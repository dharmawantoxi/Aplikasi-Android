#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
src="$root/app/src/main/java/com/dharmawantoxi/rapiuang"
tests="$root/app/src/test/java/com/dharmawantoxi/rapiuang"
javac -encoding UTF-8 -d "$work" "$src/Transaction.java" "$src/Categories.java" \
    "$src/Dates.java" "$src/Money.java" "$src/FinanceMath.java" "$src/CsvExport.java" "$tests/DomainChecks.java"
java -cp "$work" com.dharmawantoxi.rapiuang.DomainChecks
