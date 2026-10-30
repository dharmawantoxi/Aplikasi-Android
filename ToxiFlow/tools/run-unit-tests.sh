#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
src="$root/app/src/main/java/com/dharmawantoxi/rapiuang"
tests="$root/app/src/test/java/com/dharmawantoxi/rapiuang"
javac -encoding UTF-8 -d "$work" "$src/AppLanguage.java" "$src/AmountInput.java" "$src/Transaction.java" "$src/Categories.java" \
    "$src/Dates.java" "$src/CreationTime.java" "$src/PhotoPolicy.java" "$src/SplashTiming.java" "$src/Money.java" "$src/FinanceMath.java" "$src/CsvExport.java" \
    "$src/FinanceInsights.java" "$src/MonthlyReport.java" "$src/PrintPagination.java" "$src/PhotoPrintPolicy.java" \
    "$src/DebtRecord.java" "$src/DebtPayment.java" "$src/DebtMath.java" "$tests/DomainChecks.java"
java -cp "$work" com.dharmawantoxi.rapiuang.DomainChecks
