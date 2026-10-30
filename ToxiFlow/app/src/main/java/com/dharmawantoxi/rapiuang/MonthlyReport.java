package com.dharmawantoxi.rapiuang;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Immutable snapshot and printable content for exactly one calendar month. */
final class MonthlyReport {
    enum Style { TITLE, SUBTITLE, SECTION, STRONG, BODY, CAPTION }

    static final class Paragraph {
        final String text;
        final Style style;
        final float spaceBefore;
        final boolean keepWithNext;

        Paragraph(String text, Style style, float spaceBefore, boolean keepWithNext) {
            this.text = text;
            this.style = style;
            this.spaceBefore = spaceBefore;
            this.keepWithNext = keepWithNext;
        }
    }

    final int year;
    final int month;
    final List<Transaction> transactions;
    final FinanceMath.Summary summary;
    final List<FinanceMath.CategoryTotal> categories;
    final FinanceInsights insights;

    private MonthlyReport(int year, int month, List<Transaction> all) {
        if (year < Dates.MIN_YEAR || year > Dates.MAX_YEAR || month < 0 || month > 11)
            throw new IllegalArgumentException("Periode tidak valid");
        this.year = year;
        this.month = month;
        List<Transaction> selected = FinanceMath.forMonth(all, year, month);
        // Date and insertion order make the printed history easy to follow. Transactions are immutable.
        Collections.sort(selected, new Comparator<Transaction>() {
            @Override public int compare(Transaction a, Transaction b) {
                int date = a.date.compareTo(b.date);
                return date != 0 ? date : Long.compare(a.id, b.id);
            }
        });
        transactions = Collections.unmodifiableList(selected);
        summary = FinanceMath.summarize(transactions);
        categories = Collections.unmodifiableList(FinanceMath.expenseCategories(transactions));
        insights = FinanceInsights.forMonth(year, month, all);
    }

    static MonthlyReport forMonth(int year, int month, List<Transaction> all) {
        return new MonthlyReport(year, month, all);
    }

    String period() { return Dates.monthLabel(year, month); }
    String fileName() { return "ToxiFlow-Laporan-" + Dates.monthKey(year, month) + ".pdf"; }

    /** Photo pages follow the same date and insertion order as the transaction history. */
    List<Transaction> photoTransactions() {
        List<Transaction> photos = new ArrayList<>();
        for (Transaction t : transactions) if (t.hasPhoto) photos.add(t);
        return Collections.unmodifiableList(photos);
    }

    /** Each paragraph is wrapped and split across pages by the Android print renderer. */
    List<Paragraph> paragraphs() { return paragraphs(true); }

    List<Paragraph> paragraphs(boolean includePhotos) {
        List<Paragraph> lines = new ArrayList<>();
        add(lines, "Laporan bulanan", Style.TITLE, 0, true);
        add(lines, "Periode: " + period(), Style.SUBTITLE, 2, false);
        add(lines, "Ringkasan periode", Style.SECTION, 16, true);
        add(lines, "Pemasukan: " + Money.format(summary.income), Style.BODY, 3, false);
        add(lines, "Pengeluaran: " + Money.format(summary.expense), Style.BODY, 2, false);
        add(lines, "Selisih periode: " + Money.format(summary.balance), Style.STRONG, 2, false);
        add(lines, "Jumlah transaksi: " + transactions.size(), Style.BODY, 2, false);
        add(lines, "Selisih hanya menghitung bulan ini, tanpa saldo awal atau transaksi bulan lain.",
                Style.CAPTION, 4, false);

        add(lines, "Pengeluaran per kategori", Style.SECTION, 16, true);
        if (categories.isEmpty()) {
            add(lines, "Belum ada pengeluaran pada periode ini.", Style.CAPTION, 3, false);
        } else {
            for (FinanceMath.CategoryTotal category : categories)
                add(lines, category.category + ": " + Money.format(category.amount), Style.BODY, 3, false);
        }

        add(lines, "Sorotan bulanan", Style.SECTION, 16, true);
        if (insights.largestExpense == null) {
            add(lines, "Belum ada pengeluaran pada periode ini.", Style.CAPTION, 3, false);
        } else {
            Transaction largest = insights.largestExpense;
            add(lines, "Pengeluaran terbesar (transaksi): " + largest.title + " · " + largest.category
                    + " · " + Dates.display(largest.date) + " · " + Money.format(largest.amount), Style.BODY, 3, false);
            FinanceMath.CategoryTotal category = insights.largestExpenseCategory;
            int percent = FinanceMath.percent(category.amount, insights.summary.expense);
            add(lines, "Kategori pengeluaran terbesar: " + category.category + " · " + Money.format(category.amount)
                    + " (" + percent + "% dari pengeluaran)", Style.BODY, 2, false);
        }
        if (insights.largestIncome == null) {
            add(lines, "Belum ada pemasukan pada periode ini.", Style.CAPTION, 3, false);
        } else {
            Transaction largest = insights.largestIncome;
            add(lines, "Pemasukan terbesar (transaksi): " + largest.title + " · " + largest.category
                    + " · " + Dates.display(largest.date) + " · " + Money.format(largest.amount), Style.BODY, 3, false);
            FinanceInsights.Group source = insights.largestIncomeSource;
            add(lines, "Sumber pemasukan terbesar: " + source.title + " · " + source.category + " · "
                    + Money.format(source.total) + " (" + source.count + " transaksi bulan ini)", Style.BODY, 2, false);
            add(lines, "Sumber dikelompokkan menurut nama dan kategori yang sama.", Style.CAPTION, 2, false);
        }

        add(lines, "Pengeluaran berulang", Style.SECTION, 16, true);
        add(lines, "Nama dan kategori sama: minimal dua kali bulan ini atau pada dua bulan dalam rentang yang diperiksa.",
                Style.CAPTION, 3, false);
        add(lines, "Rentang: bulan terpilih dan hingga dua bulan sebelumnya. Pola ini tidak memastikan jadwal rutin.",
                Style.CAPTION, 2, false);
        if (insights.recurringExpenses.isEmpty()) {
            add(lines, "Belum ada pengeluaran berulang yang terdeteksi pada periode ini.", Style.CAPTION, 3, false);
        } else {
            int shown = Math.min(FinanceInsights.MAX_RECURRING_SHOWN, insights.recurringExpenses.size());
            for (int i = 0; i < shown; i++) {
                FinanceInsights.Group group = insights.recurringExpenses.get(i);
                add(lines, group.title + " · " + group.category + " · Total bulan ini: " + Money.format(group.total),
                        Style.BODY, 4, false);
                add(lines, insights.recurringEvidence(group), Style.CAPTION, 1, false);
            }
            if (insights.recurringExpenses.size() > shown) {
                add(lines, "Menampilkan " + shown + " dari " + insights.recurringExpenses.size()
                        + " teratas berdasarkan total bulan ini.", Style.CAPTION, 3, false);
            }
        }

        add(lines, "Saran sederhana", Style.SECTION, 16, true);
        for (String suggestion : insights.suggestions) add(lines, "• " + suggestion, Style.BODY, 4, false);
        add(lines, "Saran otomatis berdasarkan catatan yang tersedia, bukan nasihat keuangan profesional.",
                Style.CAPTION, 4, false);

        add(lines, "Transaksi periode ini", Style.SECTION, 16, true);
        if (transactions.isEmpty()) {
            add(lines, "Belum ada transaksi pada periode ini.", Style.CAPTION, 3, false);
        } else {
            int photoNumber = 0;
            for (Transaction t : transactions) {
                boolean income = Transaction.INCOME.equals(t.type);
                add(lines, Dates.display(t.date) + " · " + (income ? "Pemasukan" : "Pengeluaran"),
                        Style.STRONG, 10, true);
                add(lines, "Nama: " + t.title, Style.BODY, 2, false);
                add(lines, "Kategori: " + t.category, Style.BODY, 1, false);
                add(lines, "Nominal: " + Money.signed(t.amount, income), Style.BODY, 1, false);
                add(lines, "Dibuat: " + CreationTime.display(t.createdAt), Style.CAPTION, 1, false);
                if (t.hasPhoto) {
                    photoNumber++;
                    add(lines, includePhotos ? "Foto: Lampiran " + photoNumber
                            : "Foto tidak disertakan dalam laporan ini.", Style.CAPTION, 1, false);
                }
                if (t.note != null && !t.note.isEmpty())
                    add(lines, "Catatan: " + t.note, Style.BODY, 1, false);
            }
        }
        return lines;
    }

    private static void add(List<Paragraph> lines, String text, Style style, float before, boolean keep) {
        lines.add(new Paragraph(text, style, before, keep));
    }
}
