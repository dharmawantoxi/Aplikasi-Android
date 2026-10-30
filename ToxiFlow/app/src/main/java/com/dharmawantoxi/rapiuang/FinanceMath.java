package com.dharmawantoxi.rapiuang;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Pure calculations shared by screens, persistence validation and tests. */
public final class FinanceMath {
    public static final class ValidationError {
        public final String field;
        public final String message;
        ValidationError(String field, String message) { this.field = field; this.message = message; }
    }

    public static final class Summary {
        public final BigInteger income;
        public final BigInteger expense;
        public final BigInteger balance;
        Summary(BigInteger income, BigInteger expense) {
            this.income = income;
            this.expense = expense;
            this.balance = income.subtract(expense);
        }
    }

    public static final class CategoryTotal {
        public final String category;
        public final BigInteger amount;
        CategoryTotal(String category, BigInteger amount) { this.category = category; this.amount = amount; }
    }

    private FinanceMath() { }

    public static ValidationError validate(String type, String rawAmount, String title,
                                           String category, String date, String note) {
        if (!Transaction.EXPENSE.equals(type) && !Transaction.INCOME.equals(type))
            return new ValidationError("type", "Pilih jenis transaksi.");
        try { Money.parseAmount(rawAmount); }
        catch (IllegalArgumentException e) { return new ValidationError("amount", e.getMessage()); }
        if (title == null || title.trim().isEmpty())
            return new ValidationError("title", "Isi nama transaksi terlebih dahulu.");
        if (title.trim().length() > 80)
            return new ValidationError("title", "Nama transaksi maksimal 80 karakter.");
        if (!Categories.validForType(type, category))
            return new ValidationError("category", "Pilih kategori yang sesuai.");
        if (!Dates.isValid(date))
            return new ValidationError("date", "Pilih tanggal yang valid (1900–2100).");
        if (note == null || note.length() > 300)
            return new ValidationError("note", "Catatan maksimal 300 karakter.");
        return null;
    }

    public static Summary summarize(List<Transaction> transactions) {
        BigInteger income = BigInteger.ZERO;
        BigInteger expense = BigInteger.ZERO;
        for (Transaction t : transactions) {
            if (Transaction.INCOME.equals(t.type)) income = income.add(BigInteger.valueOf(t.amount));
            else if (Transaction.EXPENSE.equals(t.type)) expense = expense.add(BigInteger.valueOf(t.amount));
        }
        return new Summary(income, expense);
    }

    public static List<Transaction> forMonth(List<Transaction> all, int year, int monthZeroBased) {
        String prefix = Dates.monthKey(year, monthZeroBased) + "-";
        List<Transaction> result = new ArrayList<>();
        for (Transaction t : all) if (t.date.startsWith(prefix)) result.add(t);
        return result;
    }

    public static List<Transaction> filter(List<Transaction> transactions, String type, String query) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<Transaction> result = new ArrayList<>();
        for (Transaction t : transactions) {
            if (type != null && !type.isEmpty() && !type.equals(t.type)) continue;
            if (!q.isEmpty() && !t.title.toLowerCase(Locale.ROOT).contains(q)
                    && !t.category.toLowerCase(Locale.ROOT).contains(q)
                    && !t.note.toLowerCase(Locale.ROOT).contains(q)) continue;
            result.add(t);
        }
        return result;
    }

    public static List<CategoryTotal> expenseCategories(List<Transaction> transactions) {
        Map<String, BigInteger> totals = new HashMap<>();
        for (Transaction t : transactions) {
            if (!Transaction.EXPENSE.equals(t.type)) continue;
            BigInteger old = totals.get(t.category);
            totals.put(t.category, (old == null ? BigInteger.ZERO : old).add(BigInteger.valueOf(t.amount)));
        }
        List<CategoryTotal> result = new ArrayList<>();
        for (Map.Entry<String, BigInteger> entry : totals.entrySet())
            result.add(new CategoryTotal(entry.getKey(), entry.getValue()));
        Collections.sort(result, new Comparator<CategoryTotal>() {
            @Override public int compare(CategoryTotal a, CategoryTotal b) {
                int amountOrder = b.amount.compareTo(a.amount);
                return amountOrder != 0 ? amountOrder : a.category.compareTo(b.category);
            }
        });
        return result;
    }

    /** A bounded integer percentage that remains accurate even for totals above Long.MAX_VALUE. */
    public static int percent(BigInteger part, BigInteger total) {
        if (total.signum() <= 0 || part.signum() <= 0) return 0;
        if (part.compareTo(total) >= 0) return 100;
        int value = part.multiply(BigInteger.valueOf(100)).divide(total).intValue();
        return Math.max(0, Math.min(100, value));
    }
}
