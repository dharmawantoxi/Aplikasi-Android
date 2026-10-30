package com.dharmawantoxi.rapiuang;

import java.io.StringWriter;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

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

    private static List<String> reportText(MonthlyReport report) {
        List<String> text = new ArrayList<>();
        for (MonthlyReport.Paragraph paragraph : report.paragraphs()) text.add(paragraph.text);
        return text;
    }

    private static void testMonthlyReport(List<Transaction> records, List<Transaction> huge) {
        List<Transaction> mutable = new ArrayList<>(records);
        // Records on either side of the year boundary must never leak into another report.
        mutable.add(t(10, Transaction.INCOME, 1000, "Akhir tahun", "Gaji", "2025-12-31", ""));
        mutable.add(t(11, Transaction.EXPENSE, 2000, "Tahun baru", "Belanja", "2026-01-01", ""));
        mutable.add(t(12, Transaction.EXPENSE, 3000, "Tahun depan", "Belanja", "2027-01-01", ""));
        MonthlyReport report = MonthlyReport.forMonth(2026, 9, mutable);
        mutable.clear();
        eq("Oktober 2026", report.period(), "print period");
        eq("ToxiFlow-Laporan-2026-10.pdf", report.fileName(), "print filename");
        eq(4, report.transactions.size(), "print snapshot month isolation");
        eq(2L, report.transactions.get(0).id, "print ordered by date and insertion");
        eq(3L, report.transactions.get(1).id, "same-day print order");
        eq(5L, report.transactions.get(3).id, "latest transaction last");
        eq(new BigInteger("500000"), report.summary.income, "print monthly income");
        eq(new BigInteger("130000"), report.summary.expense, "print monthly expense");
        eq(new BigInteger("370000"), report.summary.balance, "print monthly difference, not all-time balance");
        eq(new BigInteger("110000"), report.categories.get(0).amount, "print category total");
        eq(5L, report.insights.largestExpense.id, "print insights remain fixed after source list changes");
        try { report.transactions.clear(); throw new AssertionError("Print snapshot must be immutable"); }
        catch (UnsupportedOperationException expected) { checks++; }
        List<String> text = reportText(report);
        check(text.contains("Periode: Oktober 2026"), "print content contains period");
        check(text.contains("Pemasukan: Rp500.000"), "print content contains income");
        check(text.contains("Pengeluaran: Rp130.000"), "print content contains expense");
        check(text.contains("Selisih periode: Rp370.000"), "print labels difference as period only");
        check(text.contains("Belanja: Rp110.000"), "print content contains category total");
        check(text.contains("Nama: Belanja sayur"), "print content contains transaction");
        check(text.contains("Nominal: − Rp80.000"), "print content contains direction and amount");
        check(text.contains("Catatan: Pasar"), "print content contains note");
        check(!text.contains("Nama: Gaji lama"), "print content excludes other month");

        MonthlyReport empty = MonthlyReport.forMonth(2026, 1, records);
        eq(0, empty.transactions.size(), "empty month has no transactions");
        eq(BigInteger.ZERO, empty.summary.balance, "empty report zero difference");
        check(reportText(empty).contains("Belum ada transaksi pada periode ini."), "empty report message");
        check(reportText(empty).contains("Belum ada pengeluaran pada periode ini."), "empty expense message");
        eq(new BigInteger("18446744073709551614"), MonthlyReport.forMonth(2026, 9, huge).summary.income,
                "print totals do not overflow long");
        List<Transaction> boundary = Arrays.asList(
                t(1, Transaction.EXPENSE, 1, "a", "Belanja", "2025-12-31", ""),
                t(2, Transaction.EXPENSE, 2, "b", "Belanja", "2026-01-01", ""),
                t(3, Transaction.EXPENSE, 3, "c", "Belanja", "2026-01-31", ""),
                t(4, Transaction.EXPENSE, 4, "d", "Belanja", "2026-02-01", ""),
                t(5, Transaction.EXPENSE, 5, "e", "Belanja", "2027-01-01", ""));
        MonthlyReport january = MonthlyReport.forMonth(2026, 0, boundary);
        eq(2, january.transactions.size(), "print includes both month boundaries only");
        eq(new BigInteger("-5"), january.summary.balance, "print negative difference");
        check(reportText(january).contains("Selisih periode: −Rp5"), "negative print difference formatted");
        char[] longNote = new char[300]; Arrays.fill(longNote, 'x');
        String note = "baris pertama\n" + new String(longNote) + " 🧾";
        MonthlyReport longText = MonthlyReport.forMonth(2026, 9, Collections.singletonList(
                t(7, Transaction.EXPENSE, 123, "Judul\nlanjutan", "Belanja", "2026-10-09", note)));
        check(reportText(longText).contains("Nama: Judul\nlanjutan"), "multi-line title retained");
        check(reportText(longText).contains("Catatan: " + note), "long multi-line Unicode note retained");
        try { MonthlyReport.forMonth(2026, 12, records); throw new AssertionError("Invalid print month"); }
        catch (IllegalArgumentException expected) { checks++; }
    }

    private static void testFinanceInsights() {
        List<Transaction> records = Arrays.asList(
                t(1, Transaction.EXPENSE, 120, "  Internet ", "Tagihan", "2026-01-10", ""),
                t(2, Transaction.EXPENSE, 280, "internet", "Tagihan", "2026-01-20", ""),
                t(3, Transaction.EXPENSE, 900000, "INTERNET", "Tagihan", "2025-12-29", ""),
                t(4, Transaction.EXPENSE, 100, "Internet", "Tagihan", "2025-11-15", ""),
                t(5, Transaction.EXPENSE, 9000000, "Internet", "Tagihan", "2025-10-20", ""),
                t(6, Transaction.EXPENSE, 500, "Belanja besar", "Belanja", "2026-01-25", ""),
                t(7, Transaction.EXPENSE, 200, "Kopi", "Makan & minum", "2026-01-01", ""),
                t(8, Transaction.EXPENSE, 100, "KOPI", "Makan & minum", "2026-01-31", ""),
                t(9, Transaction.EXPENSE, 700, "Internet", "Belanja", "2026-01-15", ""),
                t(10, Transaction.EXPENSE, 7777777, "Lama saja", "Lainnya", "2025-12-15", ""),
                t(11, Transaction.EXPENSE, 8888888, "Internet", "Tagihan", "2026-02-01", ""),
                t(20, Transaction.INCOME, 1000, "Gaji", "Gaji", "2026-01-01", ""),
                t(21, Transaction.INCOME, 600, "usaha kecil", "Usaha", "2026-01-02", ""),
                t(22, Transaction.INCOME, 600, "Usaha  Kecil", "Usaha", "2026-01-30", ""));
        FinanceInsights insights = FinanceInsights.forMonth(2026, 0, records);
        eq(new BigInteger("2200"), insights.summary.income, "insight uses selected-month income");
        eq(new BigInteger("1900"), insights.summary.expense, "insight uses selected-month expense");
        eq(9L, insights.largestExpense.id, "largest single expense excludes previous and future months");
        eq(20L, insights.largestIncome.id, "largest single income");
        eq("Belanja", insights.largestExpenseCategory.category, "largest expense category");
        eq(new BigInteger("1200"), insights.largestExpenseCategory.amount, "expense category total");
        eq("Usaha  Kecil", insights.largestIncomeSource.title, "income grouped by normalized name and category");
        eq(new BigInteger("1200"), insights.largestIncomeSource.total, "largest income source aggregates this month");
        eq(2, insights.largestIncomeSource.count, "income source count");
        eq(2, insights.recurringExpenses.size(), "repeat groups exclude unrelated and past-only expenses");
        FinanceInsights.Group internet = insights.recurringExpenses.get(0);
        eq("internet", internet.title, "representative uses latest selected transaction");
        eq("Tagihan", internet.category, "same title in other category remains separate");
        eq(new BigInteger("400"), internet.total, "recurring total excludes previous-month amounts");
        eq(2, internet.count, "repeat count is selected month only");
        eq(3, internet.monthsSeen, "repeat window crosses year and ignores month outside window");
        eq(3, insights.windowMonths, "three calendar months examined");
        eq("2 kali bulan ini · tercatat dalam 3 dari 3 bulan yang diperiksa", insights.recurringEvidence(internet),
                "repeat evidence clearly states count and window");
        eq("KOPI", insights.recurringExpenses.get(1).title, "same-month repeat is also detected");
        eq(1, insights.recurringExpenses.get(1).monthsSeen, "same-month repeat need not occur in previous months");
        eq(3, insights.suggestions.size(), "suggestions cover balance, category and recurring expense");
        check(insights.suggestions.get(0).contains("Rp300"), "positive period suggestion uses period net");
        check(insights.suggestions.get(2).contains("internet"), "recurring suggestion uses highest repeat");
        try { insights.recurringExpenses.clear(); throw new AssertionError("Insights must be immutable"); }
        catch (UnsupportedOperationException expected) { checks++; }

        List<String> printed = reportText(MonthlyReport.forMonth(2026, 0, records));
        check(printed.contains("Sorotan bulanan"), "print includes highlights");
        check(printed.contains("Pengeluaran berulang"), "print includes recurring expenses");
        check(printed.contains("Saran sederhana"), "print includes suggestions");
        check(printed.contains("internet · Tagihan · Total bulan ini: Rp400"), "print recurring amount is current month only");
        check(printed.contains("Sumber pemasukan terbesar: Usaha  Kecil · Usaha · Rp1.200 (2 transaksi bulan ini)"),
                "print largest income source");
        check(!printed.toString().contains("Lama saja"), "past-only transaction not exposed in printed insights");
        check(!printed.toString().contains("Rp9.000.000"), "previous-month amounts not exposed in print");
        check(printed.toString().contains("hingga dua bulan sebelumnya"), "print discloses recurrence lookback");

        FinanceInsights december = FinanceInsights.forMonth(2025, 11, records);
        eq(new BigInteger("900000"), december.recurringExpenses.get(0).total,
                "changing selected month recomputes recurring total");
        eq(1, december.recurringExpenses.get(0).count, "future selected-month entries do not leak backward");
        eq(3, december.recurringExpenses.get(0).monthsSeen, "December considers October and November");
        FinanceInsights empty = FinanceInsights.forMonth(2026, 2, records);
        check(empty.largestExpense == null && empty.largestIncome == null && empty.largestIncomeSource == null,
                "empty month has no largest transactions");
        eq(0, empty.recurringExpenses.size(), "old matches alone do not create a current recurring group");
        eq(1, empty.suggestions.size(), "empty month has one safe suggestion");
        check(empty.suggestions.get(0).contains("Mulai catat"), "empty-month guidance");

        List<Transaction> yearStart = Arrays.asList(
                t(1, Transaction.EXPENSE, 10, "Sewa", "Tagihan", "1900-01-03", ""),
                t(2, Transaction.EXPENSE, 20, "sewa", "Tagihan", "1900-01-07", ""),
                t(3, Transaction.EXPENSE, 40, "Sewa", "Tagihan", "1900-02-01", ""));
        FinanceInsights min = FinanceInsights.forMonth(1900, 0, yearStart);
        eq(1, min.windowMonths, "lookback respects minimum supported year");
        eq(2, min.recurringExpenses.get(0).count, "same-month repeat works in minimum month");
        FinanceInsights feb = FinanceInsights.forMonth(1900, 1, yearStart);
        eq(2, feb.windowMonths, "second supported month has a two-month window");
        eq(2, feb.recurringExpenses.get(0).monthsSeen, "repeat can span two supported months");
        check(feb.suggestions.get(0).contains("Belum ada pemasukan"), "no-income advice avoids claiming a deficit");

        List<Transaction> sameIncomeName = Arrays.asList(
                t(1, Transaction.INCOME, 100, "Bonus", "Gaji", "2026-01-01", ""),
                t(2, Transaction.INCOME, 200, "bonus", "Hadiah", "2026-01-02", ""),
                t(3, Transaction.INCOME, 150, "BONUS", "Gaji", "2026-01-03", ""),
                t(4, Transaction.INCOME, 5000, "Bonus", "Gaji", "2025-12-03", ""));
        FinanceInsights separatedIncome = FinanceInsights.forMonth(2026, 0, sameIncomeName);
        eq(new BigInteger("250"), separatedIncome.largestIncomeSource.total,
                "income source keeps categories separate and excludes previous month");
        eq(2, separatedIncome.largestIncomeSource.count, "income count does not include another category");
        List<Transaction> distinctNames = Arrays.asList(
                t(1, Transaction.EXPENSE, 10, "Listrik", "Tagihan", "2026-01-01", ""),
                t(2, Transaction.EXPENSE, 20, "Listrik rumah", "Tagihan", "2026-01-02", ""));
        eq(0, FinanceInsights.forMonth(2026, 0, distinctNames).recurringExpenses.size(),
                "different names are not guessed to be the same expense");

        List<Transaction> nonconsecutive = Arrays.asList(
                t(1, Transaction.EXPENSE, 20, "Sewa", "Tagihan", "2026-01-05", ""),
                t(2, Transaction.EXPENSE, 100, "sewa", "Tagihan", "2025-11-05", ""));
        FinanceInsights gaps = FinanceInsights.forMonth(2026, 0, nonconsecutive);
        eq(1, gaps.recurringExpenses.get(0).count, "one current transaction can recur across months");
        eq(2, gaps.recurringExpenses.get(0).monthsSeen, "non-consecutive months counted without promising a schedule");
        eq(new BigInteger("20"), gaps.recurringExpenses.get(0).total, "past repeat never increases current total");

        List<Transaction> manyRepeats = new ArrayList<>();
        String[] names = {"A", "B", "C", "D"};
        for (int i = 0; i < names.length; i++) {
            manyRepeats.add(t(i * 2 + 1, Transaction.EXPENSE, (i + 1) * 10, names[i], "Belanja", "2026-01-01", ""));
            manyRepeats.add(t(i * 2 + 2, Transaction.EXPENSE, (i + 1) * 10, names[i], "Belanja", "2026-01-02", ""));
        }
        FinanceInsights four = FinanceInsights.forMonth(2026, 0, manyRepeats);
        eq(4, four.recurringExpenses.size(), "all repeated groups retained in model");
        eq("D", four.recurringExpenses.get(0).title, "recurring groups ranked by current-month total");
        List<String> printedFour = reportText(MonthlyReport.forMonth(2026, 0, manyRepeats));
        check(printedFour.contains("D · Belanja · Total bulan ini: Rp80"), "print includes highest recurring group");
        check(!printedFour.contains("A · Belanja · Total bulan ini: Rp20"), "print limits recurring highlights to top three");
        check(printedFour.contains("Menampilkan 3 dari 4 teratas berdasarkan total bulan ini."), "print discloses highlight limit");
        check(printedFour.contains("Nama: A"), "all transactions remain in the printed report");

        List<Transaction> normalized = Arrays.asList(
                t(1, Transaction.EXPENSE, 20, "  ＩＮＴＥＲＮＥＴ　RUMAH ", "Tagihan", "2026-01-01", ""),
                t(2, Transaction.EXPENSE, 30, "internet   rumah", "Tagihan", "2026-01-02", ""));
        FinanceInsights norm = FinanceInsights.forMonth(2026, 0, normalized);
        eq(1, norm.recurringExpenses.size(), "Unicode width and whitespace normalized");
        eq(new BigInteger("50"), norm.recurringExpenses.get(0).total, "normalized names aggregate accurately");
        List<Transaction> tied = Arrays.asList(
                t(5, Transaction.EXPENSE, 50, "A", "Lainnya", "2026-01-01", ""),
                t(6, Transaction.EXPENSE, 50, "B", "Lainnya", "2026-01-02", ""),
                t(7, Transaction.EXPENSE, 50, "C", "Lainnya", "2026-01-02", ""));
        eq(7L, FinanceInsights.forMonth(2026, 0, tied).largestExpense.id, "ties prefer latest date and id");
        List<Transaction> reversed = new ArrayList<>(tied); Collections.reverse(reversed);
        eq(7L, FinanceInsights.forMonth(2026, 0, reversed).largestExpense.id, "tie result independent of input order");
        List<Transaction> huge = Arrays.asList(
                t(1, Transaction.INCOME, Long.MAX_VALUE, "Gaji", "Gaji", "2026-01-01", ""),
                t(2, Transaction.INCOME, Long.MAX_VALUE, "Gaji", "Gaji", "2026-01-02", ""),
                t(3, Transaction.EXPENSE, Long.MAX_VALUE, "Sewa", "Tagihan", "2026-01-03", ""),
                t(4, Transaction.EXPENSE, Long.MAX_VALUE, "Sewa", "Tagihan", "2026-01-04", ""));
        FinanceInsights enormous = FinanceInsights.forMonth(2026, 0, huge);
        eq(new BigInteger("18446744073709551614"), enormous.largestIncomeSource.total, "income source never overflows");
        eq(new BigInteger("18446744073709551614"), enormous.recurringExpenses.get(0).total, "recurring total never overflows");
        check(enormous.suggestions.get(0).contains("seimbang"), "equal large totals remain balanced");
        FinanceInsights deficit = FinanceInsights.forMonth(2026, 0, Arrays.asList(
                t(1, Transaction.INCOME, 10, "Gaji", "Gaji", "2026-01-01", ""),
                t(2, Transaction.EXPENSE, 20, "Makan", "Makan & minum", "2026-01-01", "")));
        check(deficit.suggestions.get(0).contains("Rp10"), "deficit advice uses exact monthly difference");
        FinanceInsights onlyIncome = FinanceInsights.forMonth(2026, 0, Collections.singletonList(
                t(1, Transaction.INCOME, 10, "Gaji", "Gaji", "2026-01-01", "")));
        check(onlyIncome.suggestions.get(0).contains("Belum ada pengeluaran"), "income-only guidance notes missing expenses");
        try { FinanceInsights.forMonth(2026, 12, records); throw new AssertionError("Invalid insight month"); }
        catch (IllegalArgumentException expected) { checks++; }
    }

    private static void testPhotoPolicyAndExports() throws Exception {
        check(!PhotoPolicy.validStoredJpeg(null), "missing photo is not a replacement JPEG");
        check(!PhotoPolicy.validStoredJpeg(new byte[0]), "empty JPEG rejected");
        check(!PhotoPolicy.validStoredJpeg(new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff}), "truncated JPEG rejected");
        check(!PhotoPolicy.validStoredJpeg(new byte[]{0, 1, 2, 3}), "invalid JPEG markers rejected");
        byte[] minimumMarkers = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xd9};
        check(PhotoPolicy.validStoredJpeg(minimumMarkers), "bounded JPEG markers accepted");
        byte[] limit = new byte[PhotoPolicy.MAX_STORED_BYTES];
        limit[0] = (byte) 0xff; limit[1] = (byte) 0xd8;
        limit[limit.length - 2] = (byte) 0xff; limit[limit.length - 1] = (byte) 0xd9;
        check(PhotoPolicy.validStoredJpeg(limit), "maximum stored byte length accepted");
        byte[] oversized = Arrays.copyOf(limit, limit.length + 1);
        oversized[oversized.length - 2] = (byte) 0xff; oversized[oversized.length - 1] = (byte) 0xd9;
        check(!PhotoPolicy.validStoredJpeg(oversized), "oversized stored JPEG rejected");
        eq(1600, PhotoPolicy.MAX_SIDE, "maximum normalized dimension");
        eq(20L * 1024 * 1024, PhotoPolicy.MAX_SOURCE_BYTES, "source byte limit");
        Transaction withPhoto = new Transaction(31, Transaction.EXPENSE, 1000, "Struk", "Belanja",
                "2026-10-01", "", "2026-10-01 08:00:00", true);
        Transaction noPhoto = new Transaction(32, Transaction.INCOME, 2000, "Bonus", "Gaji",
                "2026-10-02", "");
        check(withPhoto.hasPhoto && !noPhoto.hasPhoto, "attachment metadata is optional on both types");
        StringWriter csv = new StringWriter();
        CsvExport.write(csv, Arrays.asList(withPhoto, noPhoto));
        String result = csv.toString();
        check(result.contains("Foto terlampir (foto tidak disertakan)"), "CSV discloses it omits image data");
        check(result.contains("\"2026-10-01 08:00:00\",\"Ada\"\r\n"), "CSV records photo presence");
        check(result.contains("\"Bonus\",2000,\"\",\"\",\"Tidak\"\r\n"), "CSV records absence of photo");
        Transaction secondPhoto = new Transaction(33, Transaction.INCOME, 3000, "Bukti bonus", "Gaji",
                "2026-10-02", "", "2026-10-02 08:00:00", true);
        Transaction otherMonth = new Transaction(34, Transaction.EXPENSE, 500, "Luar bulan", "Belanja",
                "2026-09-30", "", "2026-09-30 08:00:00", true);
        MonthlyReport report = MonthlyReport.forMonth(2026, 9,
                Arrays.asList(secondPhoto, noPhoto, otherMonth, withPhoto));
        List<String> print = reportText(report);
        eq(2, report.photoTransactions().size(), "only selected-month photos included");
        eq(31L, report.photoTransactions().get(0).id, "attachment order starts with earliest transaction");
        eq(33L, report.photoTransactions().get(1).id, "attachment order follows date and id");
        check(print.contains("Foto: Lampiran 1") && print.contains("Foto: Lampiran 2"),
                "history cross-references each photo page");
        check(!print.contains("Foto: Lampiran 3"), "no photo page for other months or absent photos");
        List<String> withoutPhotos = new ArrayList<>();
        for (MonthlyReport.Paragraph line : report.paragraphs(false)) withoutPhotos.add(line.text);
        eq(2, Collections.frequency(withoutPhotos, "Foto tidak disertakan dalam laporan ini."),
                "photo-free print explains both omitted attachments");
        check(!withoutPhotos.contains("Foto: Lampiran 1"), "opt-out has no dangling attachment references");
        try { report.photoTransactions().clear(); throw new AssertionError("Photos list must be immutable"); }
        catch (UnsupportedOperationException expected) { checks++; }
        check(MonthlyReport.forMonth(2026, 9, Collections.singletonList(noPhoto)).photoTransactions().isEmpty(),
                "unattached transaction adds no photo page");

        PhotoPrintPolicy.Size one = PhotoPrintPolicy.sizeFor(1600, 1600, 1);
        eq(1600, one.width, "one photo keeps original width");
        eq(1600, one.height, "one photo keeps original height");
        for (int count : new int[]{1, 3, 8, 32, 64}) {
            PhotoPrintPolicy.Size size = PhotoPrintPolicy.sizeFor(1600, 1200, count);
            check((long) size.width * size.height * count <= PhotoPrintPolicy.MAX_TOTAL_PIXELS,
                    "image memory budget is bounded for " + count + " photos");
            check(size.width > 0 && size.height > 0 && size.width <= 1600 && size.height <= 1200,
                    "image dimensions are valid for " + count + " photos");
            int sample = PhotoPrintPolicy.decodeSample(1600, 1200, size);
            check(((1600L + sample - 1) / sample) >= size.width
                            && ((1200L + sample - 1) / sample) >= size.height,
                    "decode sample does not undershoot for " + count + " photos");
        }
        PhotoPrintPolicy.Size slender = PhotoPrintPolicy.sizeFor(1, 1600, 64);
        eq(1, slender.width, "one-pixel edge is never rounded to zero");
        check((long) slender.width * slender.height * 64 <= PhotoPrintPolicy.MAX_TOTAL_PIXELS,
                "very narrow image respects pixel budget");
        try { PhotoPrintPolicy.sizeFor(1600, 1600, 65); throw new AssertionError("Too many photos accepted"); }
        catch (IllegalArgumentException expected) { checks++; }
        try { PhotoPrintPolicy.sizeFor(0, 2, 1); throw new AssertionError("Invalid photo dimensions accepted"); }
        catch (IllegalArgumentException expected) { checks++; }

    }

    private static void testCreationTime() throws Exception {
        GregorianCalendar local = new GregorianCalendar(TimeZone.getTimeZone("Asia/Jakarta"), Locale.US);
        local.clear();
        local.set(2026, 9, 30, 23, 5, 7);
        String captured = CreationTime.fromCalendar(local);
        eq("2026-10-30 23:05:07", captured, "creation time uses local clock and seconds");
        check(CreationTime.isValid(captured), "valid creation time");
        check(CreationTime.isValid("2024-02-29 00:00:00"), "leap-day midnight valid");
        check(CreationTime.isValid("2026-12-31 23:59:59"), "last second of day valid");
        check(!CreationTime.isValid(null), "legacy time is unknown");
        check(!CreationTime.isValid(""), "empty time invalid");
        check(!CreationTime.isValid("2026-02-29 10:00:00"), "invalid creation day rejected");
        check(!CreationTime.isValid("2026-01-01 24:00:00"), "hour 24 rejected");
        check(!CreationTime.isValid("2026-01-01 12:60:00"), "minute 60 rejected");
        check(!CreationTime.isValid("2026-01-01 12:01:60"), "second 60 rejected");
        check(!CreationTime.isValid("2026-01-01T12:01:02"), "wrong separator rejected");
        check(!CreationTime.isValid("0000-01-01 12:01:02"), "year zero rejected");
        eq("30 Oktober 2026, 23:05", CreationTime.display(captured), "creation date and time display");
        eq("23:05 · dibuat 30 Okt 2026", CreationTime.listLabel(captured), "time visible first in list");
        eq("Tidak tercatat", CreationTime.display(null), "legacy detail does not invent time");
        eq("Jam pembuatan tidak tercatat", CreationTime.listLabel(null), "legacy list label");
        check(CreationTime.isValid(CreationTime.now()), "current device clock produces a valid timestamp");
        TimeZone previous = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
            eq("30 Oktober 2026, 23:05", CreationTime.display(captured), "display keeps original device time after zone change");
        } finally {
            TimeZone.setDefault(previous);
        }

        Transaction recorded = new Transaction(20, Transaction.EXPENSE, 15000, "Makan", "Makan & minum",
                "2026-09-30", "Catatan", captured);
        Transaction legacy = new Transaction(21, Transaction.INCOME, 50000, "Gaji", "Gaji",
                "2026-09-30", "");
        List<String> printed = reportText(MonthlyReport.forMonth(2026, 8, Arrays.asList(recorded, legacy)));
        check(printed.contains("30 September 2026 · Pengeluaran"), "report groups by transaction date, not creation date");
        check(printed.contains("Dibuat: 30 Oktober 2026, 23:05"), "print includes actual creation date and hour");
        check(printed.contains("Dibuat: Tidak tercatat"), "print labels old time as unavailable");
        StringWriter csv = new StringWriter();
        CsvExport.write(csv, Arrays.asList(recorded, legacy));
        String output = csv.toString();
        check(output.contains("Catatan,Dibuat (waktu perangkat),Foto terlampir (foto tidak disertakan)\r\n"),
                "CSV appends creation time and photo-presence columns");
        check(output.contains("\"Catatan\",\"2026-10-30 23:05:07\",\"Tidak\"\r\n"), "CSV exports full creation timestamp");
        check(output.contains("\"Gaji\",\"Gaji\",50000,\"\",\"\",\"Tidak\"\r\n"), "legacy CSV time is empty");
        StringWriter hostileCsv = new StringWriter();
        CsvExport.write(hostileCsv, Collections.singletonList(new Transaction(22, Transaction.INCOME, 1,
                "a", "Gaji", "2026-09-30", "", "=formula")));
        check(hostileCsv.toString().contains("\"'=formula\""), "CSV creation field neutralizes formulas");
    }

    private static void testPrintPagination() {
        PrintPagination.Cursor cursor = new PrintPagination.Cursor(100, 3);
        PrintPagination.Position first = cursor.place(25);
        eq(0, first.page, "first print page");
        eq(0f, first.top, "first print line starts at top");
        cursor.gap(5);
        PrintPagination.Position exact = cursor.place(70);
        eq(0, exact.page, "exact fit stays on same page");
        eq(30f, exact.top, "gap included in line position");
        PrintPagination.Position next = cursor.place(10);
        eq(1, next.page, "overflow advances to next page");
        eq(0f, next.top, "new print page starts at top");
        cursor.gap(100); // Oversize whitespace cannot create a blank page.
        eq(1, cursor.place(90).page, "oversize gap skipped without blank page");
        eq(2, cursor.place(1).page, "third page created");
        eq(3, cursor.pageCount(), "page count");
        try { cursor.place(100); throw new AssertionError("Page cap must fail, not truncate"); }
        catch (PrintPagination.TooManyPagesException expected) { checks++; }
        try { new PrintPagination.Cursor(100, 2).place(101); throw new AssertionError("Oversize line"); }
        catch (IllegalArgumentException expected) { checks++; }
        PrintPagination.Cursor heading = new PrintPagination.Cursor(100, 2);
        heading.place(85);
        heading.ensure(20);
        eq(1, heading.place(10).page, "heading kept with next line");
        PrintPagination.Cursor gapOnly = new PrintPagination.Cursor(100, 2);
        gapOnly.gap(30);
        eq(0f, gapOnly.place(10).top, "no leading whitespace on page");

        List<int[]> ranges = PrintPagination.normalizeRanges(10, Arrays.asList(
                new int[]{7, 9}, new int[]{2, 4}, new int[]{0, 0}, new int[]{4, 6},
                new int[]{8, Integer.MAX_VALUE}, new int[]{-1, 1}, new int[]{99, 100},
                new int[]{8, 7}, null));
        eq(1, ranges.size(), "overlapping and adjacent ranges merged");
        eq(0, ranges.get(0)[0], "requested ranges clipped to first page");
        eq(9, ranges.get(0)[1], "all-pages end clipped to document");
        List<int[]> separated = PrintPagination.normalizeRanges(6, Arrays.asList(new int[]{5, 99}, new int[]{1, 2}));
        eq(2, separated.size(), "disjoint requested pages remain disjoint");
        eq(1, separated.get(0)[0], "ranges sorted");
        eq(5, separated.get(1)[1], "range end clipped");
        eq(0, PrintPagination.normalizeRanges(0, Collections.singletonList(new int[]{0, 1})).size(), "no document pages");
        eq(0, PrintPagination.normalizeRanges(2, Collections.singletonList(new int[]{3, 5})).size(), "out-of-range selection");
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
        testMonthlyReport(records, huge);
        testFinanceInsights();
        testCreationTime();
        testPhotoPolicyAndExports();
        testPrintPagination();

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
