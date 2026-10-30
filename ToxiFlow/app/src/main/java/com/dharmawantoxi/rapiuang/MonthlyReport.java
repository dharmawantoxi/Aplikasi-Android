package com.dharmawantoxi.rapiuang;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Immutable snapshot and printable content for exactly one calendar month. */
final class MonthlyReport {
    enum Style {
        TITLE, SUBTITLE, SECTION, METRIC_INCOME, METRIC_EXPENSE, METRIC_BALANCE,
        META, CATEGORY, TRANSACTION_HEADER, STRONG, BODY, CAPTION
    }

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
    final List<SavingsMovement> savingsMovements;
    final BigInteger savingsOpeningBalance;
    final BigInteger savingsOpeningEntry;
    final String savingsOpeningEntryDate;
    final BigInteger savingsAdded;
    final BigInteger savingsWithdrawn;
    final BigInteger savingsClosingBalance;
    final BigInteger cashOpeningBalance;
    final BigInteger cashOpeningEntry;
    final String cashOpeningEntryDate;
    final BigInteger cashAdded;
    final BigInteger cashSpent;
    final BigInteger cashClosingBalance;
    final FinanceMath.Summary summary;
    final List<FinanceMath.CategoryTotal> categories;
    final FinanceInsights insights;
    final AppLanguage language;

    private String tr(String indonesian, String english) { return language.text(indonesian, english); }

    private MonthlyReport(int year, int month, List<Transaction> all,
                          List<SavingsMovement> allSavingsMovements,
                          SavingsOpeningBalance openingSavings, CashOpeningBalance openingCash,
                          AppLanguage language) {
        this.language = language;
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
        insights = FinanceInsights.forMonth(year, month, all, language);

        String selectedMonth = Dates.monthKey(year, month);
        BigInteger opening = BigInteger.ZERO;
        BigInteger added = BigInteger.ZERO;
        BigInteger withdrawn = BigInteger.ZERO;
        for (Transaction transaction : all) {
            BigInteger portion = BigInteger.valueOf(transaction.savingsAmount);
            boolean income = Transaction.INCOME.equals(transaction.type);
            String transactionMonth = transaction.date.substring(0, 7);
            if (transactionMonth.compareTo(selectedMonth) < 0)
                opening = income ? opening.add(portion) : opening.subtract(portion);
            else if (transactionMonth.equals(selectedMonth)) {
                if (income) added = added.add(portion);
                else withdrawn = withdrawn.add(portion);
            }
        }
        List<SavingsMovement> selectedMovements = new ArrayList<>();
        for (SavingsMovement movement : allSavingsMovements) {
            String movementMonth = movement.date.substring(0, 7);
            BigInteger amount = BigInteger.valueOf(movement.amount);
            boolean deposit = SavingsMovement.DEPOSIT.equals(movement.type);
            if (movementMonth.compareTo(selectedMonth) < 0)
                opening = deposit ? opening.add(amount) : opening.subtract(amount);
            else if (movementMonth.equals(selectedMonth)) {
                selectedMovements.add(movement);
                if (deposit) added = added.add(amount);
                else withdrawn = withdrawn.add(amount);
            }
        }
        BigInteger openingEntry = BigInteger.ZERO;
        String openingEntryDate = null;
        if (openingSavings != null) {
            String openingMonth = openingSavings.date.substring(0, 7);
            BigInteger openingAmount = BigInteger.valueOf(openingSavings.amount);
            if (openingMonth.compareTo(selectedMonth) < 0) opening = opening.add(openingAmount);
            else if (openingMonth.equals(selectedMonth)) {
                openingEntry = openingAmount;
                openingEntryDate = openingSavings.date;
            }
        }
        Collections.sort(selectedMovements, new Comparator<SavingsMovement>() {
            @Override public int compare(SavingsMovement a, SavingsMovement b) {
                int date = a.date.compareTo(b.date);
                return date != 0 ? date : Long.compare(a.id, b.id);
            }
        });
        savingsMovements = Collections.unmodifiableList(selectedMovements);
        savingsOpeningBalance = opening;
        savingsOpeningEntry = openingEntry;
        savingsOpeningEntryDate = openingEntryDate;
        savingsAdded = added;
        savingsWithdrawn = withdrawn;
        savingsClosingBalance = opening.add(openingEntry).add(added).subtract(withdrawn);

        BigInteger cashOpening = BigInteger.ZERO;
        BigInteger cashIn = BigInteger.ZERO;
        BigInteger cashOut = BigInteger.ZERO;
        for (Transaction transaction : all) {
            BigInteger portion = BigInteger.valueOf(transaction.amount).subtract(BigInteger.valueOf(transaction.savingsAmount));
            boolean income = Transaction.INCOME.equals(transaction.type);
            String transactionMonth = transaction.date.substring(0, 7);
            if (transactionMonth.compareTo(selectedMonth) < 0)
                cashOpening = income ? cashOpening.add(portion) : cashOpening.subtract(portion);
            else if (transactionMonth.equals(selectedMonth)) {
                if (income) cashIn = cashIn.add(portion);
                else cashOut = cashOut.add(portion);
            }
        }
        for (SavingsMovement movement : allSavingsMovements) {
            String movementMonth = movement.date.substring(0, 7);
            BigInteger amount = BigInteger.valueOf(movement.amount);
            boolean withdrawal = SavingsMovement.WITHDRAWAL.equals(movement.type);
            if (movementMonth.compareTo(selectedMonth) < 0)
                cashOpening = withdrawal ? cashOpening.add(amount) : cashOpening.subtract(amount);
            else if (movementMonth.equals(selectedMonth)) {
                if (withdrawal) cashIn = cashIn.add(amount);
                else cashOut = cashOut.add(amount);
            }
        }
        BigInteger cashEntry = BigInteger.ZERO;
        String cashEntryDate = null;
        if (openingCash != null) {
            String openingMonth = openingCash.date.substring(0, 7);
            BigInteger openingAmount = BigInteger.valueOf(openingCash.amount);
            if (openingMonth.compareTo(selectedMonth) < 0) cashOpening = cashOpening.add(openingAmount);
            else if (openingMonth.equals(selectedMonth)) {
                cashEntry = openingAmount;
                cashEntryDate = openingCash.date;
            }
        }
        cashOpeningBalance = cashOpening;
        cashOpeningEntry = cashEntry;
        cashOpeningEntryDate = cashEntryDate;
        cashAdded = cashIn;
        cashSpent = cashOut;
        cashClosingBalance = cashOpening.add(cashEntry).add(cashIn).subtract(cashOut);
    }

    static MonthlyReport forMonth(int year, int month, List<Transaction> all) {
        return forMonth(year, month, all, Collections.<SavingsMovement>emptyList(), AppLanguage.INDONESIAN);
    }

    static MonthlyReport forMonth(int year, int month, List<Transaction> all, AppLanguage language) {
        return forMonth(year, month, all, Collections.<SavingsMovement>emptyList(), language);
    }

    static MonthlyReport forMonth(int year, int month, List<Transaction> all,
                                  List<SavingsMovement> savingsMovements, AppLanguage language) {
        return forMonth(year, month, all, savingsMovements, null, language);
    }

    static MonthlyReport forMonth(int year, int month, List<Transaction> all,
                                  List<SavingsMovement> savingsMovements,
                                  SavingsOpeningBalance openingSavings, AppLanguage language) {
        return forMonth(year, month, all, savingsMovements, openingSavings, null, language);
    }

    static MonthlyReport forMonth(int year, int month, List<Transaction> all,
                                  List<SavingsMovement> savingsMovements,
                                  SavingsOpeningBalance openingSavings, CashOpeningBalance openingCash,
                                  AppLanguage language) {
        return new MonthlyReport(year, month, all, savingsMovements, openingSavings, openingCash, language);
    }

    String period() { return Dates.monthLabel(year, month, language); }
    String fileName() { return tr("ToxiFlow-Laporan-", "ToxiFlow-Report-") + Dates.monthKey(year, month) + ".pdf"; }

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
        add(lines, tr("Laporan bulanan", "Monthly report"), Style.TITLE, 0, true);
        add(lines, tr("Periode: ", "Period: ") + period(), Style.SUBTITLE, 2, false);
        add(lines, tr("Ringkasan periode", "Period summary"), Style.SECTION, 16, true);
        add(lines, tr("Pemasukan", "Income") + ": " + Money.format(summary.income), Style.METRIC_INCOME, 3, false);
        add(lines, tr("Pengeluaran", "Expenses") + ": " + Money.format(summary.expense), Style.METRIC_EXPENSE, 2, false);
        add(lines, tr("Selisih periode", "Period balance") + ": " + Money.format(summary.balance), Style.METRIC_BALANCE, 2, false);
        add(lines, tr("Jumlah transaksi", "Transactions") + ": " + transactions.size(), Style.META, 3, false);
        add(lines, tr("Selisih hanya menghitung bulan ini, tanpa saldo awal atau transaksi bulan lain.", "The period balance includes only this month, without an opening balance or transactions from other months."),
                Style.CAPTION, 4, false);

        add(lines, tr("Pergerakan dana tersedia", "Available-funds activity"), Style.SECTION, 16, true);
        add(lines, tr("Saldo awal dana tersedia: ", "Opening available funds: ") + Money.format(cashOpeningBalance), Style.BODY, 3, false);
        if (cashOpeningEntry.signum() > 0)
            add(lines, tr("Saldo awal dana dicatat: ", "Starting cash balance entered: ") + Money.format(cashOpeningEntry)
                    + " · " + Dates.display(cashOpeningEntryDate, language), Style.BODY, 1, false);
        add(lines, tr("Masuk ke dana tersedia: ", "Added to available funds: ") + Money.format(cashAdded), Style.BODY, 1, false);
        add(lines, tr("Keluar dari dana tersedia: ", "Spent from available funds: ") + Money.format(cashSpent), Style.BODY, 1, false);
        add(lines, tr("Saldo akhir dana tersedia: ", "Closing available funds: ") + Money.format(cashClosingBalance), Style.STRONG, 2, false);
        add(lines, tr("Tidak termasuk pokok hutang/piutang.", "Excludes debt/receivable principal."), Style.CAPTION, 1, false);

        add(lines, tr("Pergerakan tabungan", "Savings activity"), Style.SECTION, 16, true);
        add(lines, tr("Saldo awal tabungan: ", "Opening savings balance: ") + Money.format(savingsOpeningBalance), Style.BODY, 3, false);
        if (savingsOpeningEntry.signum() > 0)
            add(lines, tr("Saldo awal dicatat: ", "Starting balance entered: ") + Money.format(savingsOpeningEntry)
                    + tr(" · ", " · ") + Dates.display(savingsOpeningEntryDate, language), Style.BODY, 1, false);
        add(lines, tr("Ditambahkan ke tabungan: ", "Added to savings: ") + Money.format(savingsAdded), Style.BODY, 1, false);
        add(lines, tr("Diambil dari tabungan: ", "Withdrawn from savings: ") + Money.format(savingsWithdrawn), Style.BODY, 1, false);
        add(lines, tr("Saldo akhir tabungan: ", "Closing savings balance: ") + Money.format(savingsClosingBalance), Style.STRONG, 2, false);
        int savingsAllocationCount = 0;
        for (Transaction transaction : transactions) if (transaction.savingsAmount > 0) savingsAllocationCount++;
        int savingsActivityCount = savingsAllocationCount + savingsMovements.size()
                + (savingsOpeningEntry.signum() > 0 ? 1 : 0);
        add(lines, tr("Jumlah aktivitas tabungan: ", "Savings activities: ") + savingsActivityCount, Style.META, 2, false);
        if (savingsActivityCount == 0)
            add(lines, tr("Belum ada alokasi atau perpindahan tabungan pada periode ini.", "No savings allocations or transfers in this period."), Style.CAPTION, 1, false);
        for (SavingsMovement movement : savingsMovements)
            add(lines, Dates.display(movement.date, language) + " · "
                    + (SavingsMovement.DEPOSIT.equals(movement.type)
                    ? tr("Pindah ke tabungan", "Transfer to savings") : tr("Pindah ke dana tersedia", "Transfer to available funds"))
                    + " · " + Money.format(movement.amount), Style.BODY, 1, false);

        add(lines, tr("Pengeluaran per kategori", "Expenses by category"), Style.SECTION, 16, true);
        if (categories.isEmpty()) {
            add(lines, tr("Belum ada pengeluaran pada periode ini.", "No expenses in this period."), Style.CAPTION, 3, false);
        } else {
            for (FinanceMath.CategoryTotal category : categories)
                add(lines, Categories.label(category.category, language) + ": " + Money.format(category.amount), Style.CATEGORY, 2, false);
        }

        add(lines, tr("Sorotan bulanan", "Monthly insights"), Style.SECTION, 16, true);
        if (insights.largestExpense == null) {
            add(lines, tr("Belum ada pengeluaran pada periode ini.", "No expenses in this period."), Style.CAPTION, 3, false);
        } else {
            Transaction largest = insights.largestExpense;
            add(lines, tr("Pengeluaran terbesar (transaksi): ", "Largest expense (transaction): ") + largest.title + " · " + Categories.label(largest.category, language)
                    + " · " + Dates.display(largest.date, language) + " · " + Money.format(largest.amount), Style.BODY, 3, false);
            FinanceMath.CategoryTotal category = insights.largestExpenseCategory;
            int percent = FinanceMath.percent(category.amount, insights.summary.expense);
            add(lines, tr("Kategori pengeluaran terbesar: ", "Largest expense category: ") + Categories.label(category.category, language) + " · " + Money.format(category.amount)
                    + " (" + percent + tr("% dari pengeluaran)", "% of expenses)"), Style.BODY, 2, false);
        }
        if (insights.largestIncome == null) {
            add(lines, tr("Belum ada pemasukan pada periode ini.", "No income in this period."), Style.CAPTION, 3, false);
        } else {
            Transaction largest = insights.largestIncome;
            add(lines, tr("Pemasukan terbesar (transaksi): ", "Largest income (transaction): ") + largest.title + " · " + Categories.label(largest.category, language)
                    + " · " + Dates.display(largest.date, language) + " · " + Money.format(largest.amount), Style.BODY, 3, false);
            FinanceInsights.Group source = insights.largestIncomeSource;
            add(lines, tr("Sumber pemasukan terbesar: ", "Largest income source: ") + source.title + " · " + Categories.label(source.category, language) + " · "
                    + Money.format(source.total) + " (" + source.count + tr(" transaksi bulan ini)",
                    source.count == 1 ? " transaction this month)" : " transactions this month)"), Style.BODY, 2, false);
            add(lines, tr("Sumber dikelompokkan menurut nama dan kategori yang sama.", "Sources are grouped by the same name and category."), Style.CAPTION, 2, false);
        }

        add(lines, tr("Pengeluaran berulang", "Recurring expenses"), Style.SECTION, 16, true);
        add(lines, tr("Nama dan kategori sama: minimal dua kali bulan ini atau pada dua bulan dalam rentang yang diperiksa.", "Same name and category: at least twice this month, or in two months within the checked window."),
                Style.CAPTION, 3, false);
        add(lines, tr("Rentang: bulan terpilih dan hingga dua bulan sebelumnya. Pola ini tidak memastikan jadwal rutin.", "Window: the selected month and up to two previous months. This pattern does not guarantee a regular schedule."),
                Style.CAPTION, 2, false);
        if (insights.recurringExpenses.isEmpty()) {
            add(lines, tr("Belum ada pengeluaran berulang yang terdeteksi pada periode ini.", "No recurring expenses were detected in this period."), Style.CAPTION, 3, false);
        } else {
            int shown = Math.min(FinanceInsights.MAX_RECURRING_SHOWN, insights.recurringExpenses.size());
            for (int i = 0; i < shown; i++) {
                FinanceInsights.Group group = insights.recurringExpenses.get(i);
                add(lines, group.title + " · " + Categories.label(group.category, language) + tr(" · Total bulan ini: ", " · Total this month: ") + Money.format(group.total),
                        Style.BODY, 4, false);
                add(lines, insights.recurringEvidence(group), Style.CAPTION, 1, false);
            }
            if (insights.recurringExpenses.size() > shown) {
                add(lines, tr("Menampilkan ", "Showing ") + shown + tr(" dari ", " of ") + insights.recurringExpenses.size()
                        + tr(" teratas berdasarkan total bulan ini.", " top entries by this month’s total."), Style.CAPTION, 3, false);
            }
        }

        add(lines, tr("Saran sederhana", "Simple suggestions"), Style.SECTION, 16, true);
        for (String suggestion : insights.suggestions) add(lines, "• " + suggestion, Style.BODY, 4, false);
        add(lines, tr("Saran otomatis berdasarkan catatan yang tersedia, bukan nasihat keuangan profesional.", "Automatic suggestions are based on available records and are not professional financial advice."),
                Style.CAPTION, 4, false);

        add(lines, tr("Transaksi periode ini", "Transactions in this period"), Style.SECTION, 16, true);
        if (transactions.isEmpty()) {
            add(lines, tr("Belum ada transaksi pada periode ini.", "No transactions in this period."), Style.CAPTION, 3, false);
        } else {
            int photoNumber = 0;
            for (Transaction t : transactions) {
                boolean income = Transaction.INCOME.equals(t.type);
                add(lines, Dates.display(t.date, language) + " · " + (income ? tr("Pemasukan", "Income") : tr("Pengeluaran", "Expense")),
                        Style.TRANSACTION_HEADER, 9, true);
                add(lines, tr("Nama: ", "Name: ") + t.title, Style.BODY, 2, false);
                add(lines, tr("Kategori: ", "Category: ") + Categories.label(t.category, language), Style.BODY, 1, false);
                add(lines, tr("Nominal: ", "Amount: ") + Money.signed(t.amount, income), Style.BODY, 1, false);
                long cashPortion = t.amount - t.savingsAmount;
                if (cashPortion > 0) add(lines, (income ? tr("Masuk ke dana tersedia: ", "Added to available funds: ")
                        : tr("Dibayar dari dana tersedia: ", "Paid from available funds: ")) + Money.format(cashPortion), Style.BODY, 1, false);
                if (t.savingsAmount > 0) add(lines, (income ? tr("Masuk ke tabungan: ", "Added to savings: ")
                        : tr("Diambil dari tabungan: ", "Paid from savings: ")) + Money.format(t.savingsAmount), Style.BODY, 1, false);
                add(lines, tr("Dibuat: ", "Created: ") + CreationTime.display(t.createdAt, language), Style.CAPTION, 1, false);
                if (t.hasPhoto) {
                    photoNumber++;
                    add(lines, includePhotos ? tr("Foto: Lampiran ", "Photo: Appendix ") + photoNumber
                            : tr("Foto tidak disertakan dalam laporan ini.", "Photo not included in this report."), Style.CAPTION, 1, false);
                }
                if (t.note != null && !t.note.isEmpty())
                    add(lines, tr("Catatan: ", "Note: ") + t.note, Style.BODY, 1, false);
            }
        }
        return lines;
    }

    private static void add(List<Paragraph> lines, String text, Style style, float before, boolean keep) {
        lines.add(new Paragraph(text, style, before, keep));
    }
}
