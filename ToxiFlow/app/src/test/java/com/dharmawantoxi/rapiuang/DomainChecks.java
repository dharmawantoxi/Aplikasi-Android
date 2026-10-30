package com.dharmawantoxi.rapiuang;

import java.io.StringWriter;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Can run both as a JUnit test (via DomainTest) and with plain javac. */
public final class DomainChecks {
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static void eq(Object expected, Object actual, String message) {
        check(expected.equals(actual), message + ": expected <" + expected + ">, got <" + actual + ">");
    }
    private static void rejects(String amount) {
        try { Money.parseAmount(amount); throw new AssertionError("Expected invalid amount: " + amount); }
        catch (IllegalArgumentException expected) { checks++; }
    }
    private static Transaction t(long id, String type, long amount, String title, String category, String date, String note) {
        return new Transaction(id, type, amount, title, category, date, note);
    }

    public static void main(String[] args) throws Exception {
        eq("Rp0", Money.format(0), "format zero");
        eq("Rp1.234.567", Money.format(1_234_567), "format rupiah");
        eq("−Rp1.234", Money.format(-1234), "format negative");
        eq("−Rp9.223.372.036.854.775.808", Money.format(Long.MIN_VALUE), "format minimum long");
        eq("Rp123.456.789.012.345.678.901", Money.format(new BigInteger("123456789012345678901")), "format big total");
        eq("+ Rp12.000", Money.signed(12000, true), "income sign");
        eq("− Rp12.000", Money.signed(12000, false), "expense sign");
        eq(1L, Money.parseAmount("0001"), "leading zeroes");
        eq(999_999_999_999L, Money.parseAmount("999999999999"), "max value");
        rejects(""); rejects("0"); rejects("-1"); rejects("1.000"); rejects("2,5");
        rejects("1000000000000"); rejects("a100"); rejects(null);

        check(Dates.isValid("2024-02-29"), "leap day is valid");
        check(!Dates.isValid("2023-02-29"), "non-leap day is invalid");
        check(!Dates.isValid("2026-04-31"), "invalid day in month");
        check(!Dates.isValid("2026-13-01"), "invalid month");
        check(!Dates.isValid("1899-12-31"), "lower date bound");
        check(!Dates.isValid("2101-01-01"), "upper date bound");
        check(!Dates.isValid("2026-1-01"), "date must be padded");
        eq("2026-10-30", Dates.iso(2026, 9, 30), "date ISO");
        eq("30 Oktober 2026", Dates.display("2026-10-30"), "Indonesian date");
        eq("Februari 2026", Dates.monthLabel(2026, 1), "month label");
        eq("2026-01", Dates.monthKey(2026, 0), "month key");
        eq("2026-02-28", Dates.dateInMonth(2026, 1, "2026-03-31"), "short month clamps day");
        eq("2024-02-29", Dates.dateInMonth(2024, 1, "2024-03-31"), "leap month clamps day");
        eq("2026-10-08", Dates.dateInMonth(2026, 9, "2026-09-08"), "day stays the same");

        check(FinanceMath.validate(Transaction.EXPENSE, "15000", "  Nasi  ", "Makan & minum", "2026-10-30", "") == null, "valid expense");
        eq("amount", FinanceMath.validate(Transaction.INCOME, "0", "Gaji", "Gaji", "2026-10-30", "").field, "zero rejected");
        eq("title", FinanceMath.validate(Transaction.INCOME, "1", "   ", "Gaji", "2026-10-30", "").field, "blank title rejected");
        eq("category", FinanceMath.validate(Transaction.INCOME, "1", "Gaji", "Belanja", "2026-10-30", "").field, "wrong category rejected");
        eq("date", FinanceMath.validate(Transaction.INCOME, "1", "Gaji", "Gaji", "2023-02-29", "").field, "bad date rejected");
        eq("type", FinanceMath.validate("unknown", "1", "x", "Lainnya", "2026-10-30", "").field, "bad type rejected");
        char[] many = new char[301]; Arrays.fill(many, 'a');
        eq("note", FinanceMath.validate(Transaction.INCOME, "1", "Gaji", "Gaji", "2026-10-30", new String(many)).field, "long note rejected");

        List<Transaction> records = Arrays.asList(
                t(5, Transaction.EXPENSE, 80000, "Belanja sayur", "Belanja", "2026-10-30", "Pasar"),
                t(4, Transaction.EXPENSE, 20000, "Makan siang", "Makan & minum", "2026-10-29", "Warung"),
                t(3, Transaction.INCOME, 500000, "Gaji", "Gaji", "2026-10-01", ""),
                t(2, Transaction.EXPENSE, 30000, "Belanja buku", "Belanja", "2026-10-01", ""),
                t(1, Transaction.INCOME, 200000, "Gaji lama", "Gaji", "2026-09-30", ""));
        List<Transaction> october = FinanceMath.forMonth(records, 2026, 9);
        eq(4, october.size(), "monthly filter");
        FinanceMath.Summary summary = FinanceMath.summarize(october);
        eq(new BigInteger("500000"), summary.income, "income total");
        eq(new BigInteger("130000"), summary.expense, "expense total");
        eq(new BigInteger("370000"), summary.balance, "period difference");
        eq(new BigInteger("570000"), FinanceMath.summarize(records).balance, "all-time balance");
        eq(BigInteger.ZERO, FinanceMath.summarize(Collections.<Transaction>emptyList()).balance, "empty balance");
        List<Transaction> huge = Arrays.asList(t(1, Transaction.INCOME, Long.MAX_VALUE, "a", "Gaji", "2026-10-01", ""),
                t(2, Transaction.INCOME, Long.MAX_VALUE, "b", "Gaji", "2026-10-01", ""));
        eq(new BigInteger("18446744073709551614"), FinanceMath.summarize(huge).income, "totals never overflow long");
        eq(2, FinanceMath.filter(october, Transaction.EXPENSE, "belanja").size(), "case-insensitive filter");
        eq(1, FinanceMath.filter(october, "", "WARUNG").size(), "search notes");
        eq(0, FinanceMath.filter(october, Transaction.INCOME, "belanja").size(), "filter combines type and search");
        List<FinanceMath.CategoryTotal> categories = FinanceMath.expenseCategories(october);
        eq(2, categories.size(), "expense categories exclude income");
        eq("Belanja", categories.get(0).category, "descending category ordering");
        eq(new BigInteger("110000"), categories.get(0).amount, "aggregate same category");
        eq(84, FinanceMath.percent(categories.get(0).amount, summary.expense), "percent rounds down");
        eq(0, FinanceMath.percent(BigInteger.ZERO, BigInteger.ZERO), "empty percentage");
        eq(100, FinanceMath.percent(BigInteger.TEN.pow(50), BigInteger.ONE), "percent clamp without overflow");

        StringWriter csv = new StringWriter();
        CsvExport.write(csv, Arrays.asList(t(8, Transaction.EXPENSE, 123, "=2+3", "Lainnya", "2026-10-30", "A, \"B\"\nline")));
        String data = csv.toString();
        check(data.startsWith("\ufeffTanggal,Jenis"), "UTF-8 BOM and header");
        check(data.contains("\"'=2+3\""), "spreadsheet formula neutralized");
        check(data.contains("\"A, \"\"B\"\"\nline\""), "CSV quotes commas, quotes and newlines");
        eq("\"'  +cmd\"", CsvExport.cell("  +cmd"), "leading-space formula neutralized");
        eq("\"'\ttext\"", CsvExport.cell("\ttext"), "leading control character neutralized");
        eq("\"Normal\"", CsvExport.cell("Normal"), "normal cell unchanged");
        System.out.println("Domain checks passed: " + checks);
    }
}
