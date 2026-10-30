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

    public static boolean validForType(String type, String category) {
        if (!Transaction.INCOME.equals(type) && !Transaction.EXPENSE.equals(type)) return false;
        List<String> categories = Arrays.asList(forType(type));
        return categories.contains(category);
    }
}
