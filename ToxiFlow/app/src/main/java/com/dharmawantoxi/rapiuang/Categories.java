package com.dharmawantoxi.rapiuang;

import java.util.Arrays;
import java.util.List;

public final class Categories {
    public static final String[] EXPENSE = {
            "Makan & minum", "Belanja", "Transportasi", "Tagihan", "Kesehatan",
            "Hiburan", "Pendidikan", "Lainnya"
    };
    public static final String[] INCOME = {"Gaji", "Usaha", "Hadiah", "Investasi", "Lainnya"};

    private Categories() { }

    public static String[] forType(String type) {
        return Transaction.INCOME.equals(type) ? INCOME.clone() : EXPENSE.clone();
    }

    /** Display names change with the language, but stored category keys never change. */
    static String label(String category, AppLanguage language) {
        if (category == null || language != AppLanguage.ENGLISH) return category;
        switch (category) {
            case "Makan & minum": return "Food & drink";
            case "Belanja": return "Shopping";
            case "Transportasi": return "Transport";
            case "Tagihan": return "Bills";
            case "Kesehatan": return "Health";
            case "Hiburan": return "Entertainment";
            case "Pendidikan": return "Education";
            case "Lainnya": return "Other";
            case "Gaji": return "Salary";
            case "Usaha": return "Business";
            case "Hadiah": return "Gift";
            case "Investasi": return "Investment";
            default: return category; // Keep any older or unknown value visible.
        }
    }

    public static boolean validForType(String type, String category) {
        if (!Transaction.INCOME.equals(type) && !Transaction.EXPENSE.equals(type)) return false;
        List<String> categories = Arrays.asList(forType(type));
        return categories.contains(category);
    }
}
