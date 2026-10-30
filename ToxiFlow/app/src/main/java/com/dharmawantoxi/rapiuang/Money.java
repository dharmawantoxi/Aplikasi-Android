package com.dharmawantoxi.rapiuang;

import java.math.BigInteger;

/** Whole Indonesian rupiah, without floating-point arithmetic or locale-dependent separators. */
public final class Money {
    public static final long MAX_TRANSACTION = 999_999_999_999L;
    private Money() { }

    public static long parseAmount(String input) { return parseAmount(input, AppLanguage.INDONESIAN); }

    static long parseAmount(String input, AppLanguage language) {
        if (input == null || !input.matches("[0-9]{1,12}")) {
            throw new IllegalArgumentException(language.text(
                    "Masukkan nominal rupiah tanpa titik atau koma (maksimal 12 digit).",
                    "Enter a whole rupiah amount without dots or commas (maximum 12 digits)."));
        }
        long value = Long.parseLong(input);
        if (value <= 0 || value > MAX_TRANSACTION) {
            throw new IllegalArgumentException(language.text(
                    "Nominal harus lebih besar dari Rp0 dan maksimal Rp999.999.999.999.",
                    "The amount must be greater than Rp0 and no more than Rp999.999.999.999."));
        }
        return value;
    }

    public static String format(long amount) {
        return format(BigInteger.valueOf(amount));
    }

    public static String format(BigInteger amount) {
        boolean negative = amount.signum() < 0;
        String digits = amount.abs().toString();
        StringBuilder grouped = new StringBuilder();
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0 && (digits.length() - i) % 3 == 0) grouped.append('.');
            grouped.append(digits.charAt(i));
        }
        return (negative ? "−" : "") + "Rp" + grouped;
    }

    public static String signed(long positiveAmount, boolean income) {
        return (income ? "+ " : "− ") + format(positiveAmount);
    }
}
