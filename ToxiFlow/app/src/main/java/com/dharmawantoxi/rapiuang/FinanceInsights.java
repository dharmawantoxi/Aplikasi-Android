package com.dharmawantoxi.rapiuang;

import java.math.BigInteger;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Deterministic, offline observations based only on recorded transactions. */
final class FinanceInsights {
    static final int LOOKBACK_MONTHS = 3;
    static final int MAX_RECURRING_SHOWN = 3;

    static final class Group {
        final String title;
        final String category;
        final BigInteger total;
        final int count;
        final int monthsSeen;

        Group(Accumulator source) {
            title = source.representative.title.trim();
            category = source.representative.category;
            total = source.total;
            count = source.count;
            monthsSeen = Integer.bitCount(source.monthMask);
        }
    }

    final FinanceMath.Summary summary;
    final Transaction largestExpense;
    final Transaction largestIncome;
    final FinanceMath.CategoryTotal largestExpenseCategory;
    final Group largestIncomeSource;
    final List<Group> recurringExpenses;
    final int windowMonths;
    final List<String> suggestions;
    final AppLanguage language;

    private FinanceInsights(FinanceMath.Summary summary, Transaction largestExpense, Transaction largestIncome,
                            FinanceMath.CategoryTotal largestExpenseCategory, Group largestIncomeSource,
                            List<Group> recurringExpenses, int windowMonths, List<String> suggestions,
                            AppLanguage language) {
        this.language = language;
        this.summary = summary;
        this.largestExpense = largestExpense;
        this.largestIncome = largestIncome;
        this.largestExpenseCategory = largestExpenseCategory;
        this.largestIncomeSource = largestIncomeSource;
        this.recurringExpenses = Collections.unmodifiableList(recurringExpenses);
        this.windowMonths = windowMonths;
        this.suggestions = Collections.unmodifiableList(suggestions);
    }

    static FinanceInsights forMonth(int year, int month, List<Transaction> all) {
        return forMonth(year, month, all, AppLanguage.INDONESIAN);
    }

    static FinanceInsights forMonth(int year, int month, List<Transaction> all, AppLanguage language) {
        if (year < Dates.MIN_YEAR || year > Dates.MAX_YEAR || month < 0 || month > 11)
            throw new IllegalArgumentException("Periode tidak valid");
        List<Transaction> selected = FinanceMath.forMonth(all, year, month);
        FinanceMath.Summary summary = FinanceMath.summarize(selected);
        List<FinanceMath.CategoryTotal> categories = FinanceMath.expenseCategories(selected);
        FinanceMath.CategoryTotal topCategory = categories.isEmpty() ? null : categories.get(0);
        Map<Key, Accumulator> expenseGroups = new HashMap<>();
        Map<Key, Accumulator> incomeGroups = new HashMap<>();
        Transaction biggestExpense = null, biggestIncome = null;
        for (Transaction t : selected) {
            if (Transaction.EXPENSE.equals(t.type)) {
                add(expenseGroups, t);
                if (preferTransaction(t, biggestExpense)) biggestExpense = t;
            } else if (Transaction.INCOME.equals(t.type)) {
                add(incomeGroups, t);
                if (preferTransaction(t, biggestIncome)) biggestIncome = t;
            }
        }

        // Only groups that occur in the selected month can be reported; past amounts never affect
        // the current month's totals. Months are calendar months ending at the selected month.
        List<String> keys = lookbackKeys(year, month);
        for (Transaction t : all) {
            if (!Transaction.EXPENSE.equals(t.type)) continue;
            int monthOffset = -1;
            for (int i = 0; i < keys.size(); i++) {
                if (t.date.startsWith(keys.get(i) + "-")) { monthOffset = i; break; }
            }
            if (monthOffset < 0) continue;
            Accumulator group = expenseGroups.get(new Key(t.title, t.category));
            if (group != null) group.monthMask |= 1 << monthOffset;
        }

        List<Accumulator> incomes = new ArrayList<>(incomeGroups.values());
        Collections.sort(incomes, GROUP_ORDER);
        Group topIncomeSource = incomes.isEmpty() ? null : new Group(incomes.get(0));
        List<Accumulator> repeated = new ArrayList<>();
        for (Accumulator group : expenseGroups.values()) {
            if (group.count >= 2 || Integer.bitCount(group.monthMask) >= 2) repeated.add(group);
        }
        Collections.sort(repeated, GROUP_ORDER);
        List<Group> recurring = new ArrayList<>();
        for (Accumulator group : repeated) recurring.add(new Group(group));
        List<String> advice = makeSuggestions(selected, summary, topCategory, recurring, language);
        return new FinanceInsights(summary, biggestExpense, biggestIncome, topCategory, topIncomeSource,
                recurring, keys.size(), advice, language);
    }

    /** Compact evidence, not a prediction that a payment will happen again. */
    String recurringEvidence(Group group) {
        if (language == AppLanguage.ENGLISH) {
            return group.count + (group.count == 1 ? " time" : " times") + " this month · recorded in "
                    + group.monthsSeen + " of " + windowMonths + (windowMonths == 1 ? " month checked" : " months checked");
        }
        return group.count + " kali bulan ini · tercatat dalam " + group.monthsSeen + " dari "
                + windowMonths + " bulan yang diperiksa";
    }

    private static List<String> makeSuggestions(List<Transaction> selected, FinanceMath.Summary summary,
                                                FinanceMath.CategoryTotal category, List<Group> recurring,
                                                AppLanguage language) {
        List<String> result = new ArrayList<>();
        if (selected.isEmpty()) {
            result.add(language.text("Mulai catat pemasukan dan pengeluaran agar pola keuangan dapat terlihat.",
                    "Start recording income and expenses to see your financial patterns."));
            return result;
        }
        if (summary.income.signum() == 0 && summary.expense.signum() > 0) {
            result.add(language.text("Belum ada pemasukan tercatat bulan ini. Pastikan catatan sudah lengkap sebelum menyimpulkan kondisi keuangan.",
                    "No income is recorded this month. Make sure your records are complete before drawing conclusions."));
        } else if (summary.expense.signum() == 0 && summary.income.signum() > 0) {
            result.add(language.text("Belum ada pengeluaran tercatat bulan ini. Lengkapi pencatatan agar ringkasan lebih bermakna.",
                    "No expenses are recorded this month. Complete your records for a more meaningful summary."));
        } else if (summary.balance.signum() < 0) {
            result.add(language.text("Pengeluaran tercatat melebihi pemasukan sebesar ", "Recorded expenses exceed income by ")
                    + Money.format(summary.balance.abs()) + language.text(
                    ". Tinjau transaksi dan pastikan catatan sudah lengkap.", ". Review your transactions and make sure your records are complete."));
        } else if (summary.balance.signum() > 0) {
            result.add(language.text("Selisih periode tercatat ", "The recorded balance for this period is ")
                    + Money.format(summary.balance) + language.text(
                    ". Jika catatan sudah lengkap, pertimbangkan menyisihkan sebagian sesuai kondisi dan kebutuhan.",
                    ". If your records are complete, consider setting some aside according to your circumstances and needs."));
        } else {
            result.add(language.text("Pemasukan dan pengeluaran tercatat seimbang. Periksa apakah semua transaksi sudah dicatat.",
                    "Recorded income and expenses are balanced. Check whether all transactions have been recorded."));
        }
        if (category != null) {
            result.add(language.text("Tinjau kategori ", "Review the category ") + Categories.label(category.category, language)
                    + " (" + Money.format(category.amount) + language.text(
                    ") untuk melihat pos yang mungkin dapat disesuaikan dengan kebutuhan.",
                    ") for items that could be adjusted to suit your needs."));
        }
        if (!recurring.isEmpty()) {
            Group top = recurring.get(0);
            result.add(language.text("Periksa pengeluaran berulang “", "Review the recurring expense “") + top.title
                    + "” (" + Categories.label(top.category, language) + language.text(
                    ") dan pertimbangkan apakah jumlah serta frekuensinya masih sesuai kebutuhan.",
                    ") and consider whether its amount and frequency still suit your needs."));
        }
        return result;
    }

    private static List<String> lookbackKeys(int year, int month) {
        List<String> keys = new ArrayList<>();
        int selected = year * 12 + month;
        int firstSupported = Dates.MIN_YEAR * 12;
        for (int offset = 0; offset < LOOKBACK_MONTHS && selected - offset >= firstSupported; offset++) {
            int value = selected - offset;
            keys.add(Dates.monthKey(value / 12, value % 12));
        }
        return keys;
    }

    private static void add(Map<Key, Accumulator> groups, Transaction t) {
        Key key = new Key(t.title, t.category);
        Accumulator group = groups.get(key);
        if (group == null) {
            group = new Accumulator(key);
            groups.put(key, group);
        }
        group.total = group.total.add(BigInteger.valueOf(t.amount));
        group.count++;
        if (preferRepresentative(t, group.representative)) group.representative = t;
    }

    private static boolean preferTransaction(Transaction candidate, Transaction current) {
        if (current == null) return true;
        if (candidate.amount != current.amount) return candidate.amount > current.amount;
        return preferRepresentative(candidate, current);
    }

    private static boolean preferRepresentative(Transaction candidate, Transaction current) {
        if (current == null) return true;
        int date = candidate.date.compareTo(current.date);
        if (date != 0) return date > 0;
        if (candidate.id != current.id) return candidate.id > current.id;
        int title = candidate.title.compareTo(current.title);
        if (title != 0) return title < 0;
        return candidate.category.compareTo(current.category) < 0;
    }

    private static final Comparator<Accumulator> GROUP_ORDER = new Comparator<Accumulator>() {
        @Override public int compare(Accumulator a, Accumulator b) {
            int total = b.total.compareTo(a.total);
            if (total != 0) return total;
            int title = a.key.title.compareTo(b.key.title);
            return title != 0 ? title : a.key.category.compareTo(b.key.category);
        }
    };

    private static final class Accumulator {
        final Key key;
        BigInteger total = BigInteger.ZERO;
        int count;
        int monthMask;
        Transaction representative;
        Accumulator(Key key) { this.key = key; }
    }

    private static final class Key {
        final String title;
        final String category;
        Key(String title, String category) {
            this.title = normalize(title);
            this.category = normalize(category);
        }
        @Override public boolean equals(Object value) {
            if (!(value instanceof Key)) return false;
            Key other = (Key) value;
            return title.equals(other.title) && category.equals(other.category);
        }
        @Override public int hashCode() { return 31 * title.hashCode() + category.hashCode(); }
    }

    /** NFKC, case-insensitive and whitespace-normalized, but no fuzzy guesses about different names. */
    private static String normalize(String text) {
        String value = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        StringBuilder result = new StringBuilder();
        boolean space = false;
        for (int i = 0; i < value.length();) {
            int cp = value.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                space = result.length() > 0;
            } else {
                if (space) result.append(' ');
                result.appendCodePoint(cp);
                space = false;
            }
        }
        return result.toString();
    }
}
