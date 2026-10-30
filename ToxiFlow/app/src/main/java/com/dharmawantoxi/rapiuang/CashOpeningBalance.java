package com.dharmawantoxi.rapiuang;

/** One-time baseline for available cash that existed before the app started tracking it. */
public final class CashOpeningBalance {
    public final long amount;
    public final String date;

    CashOpeningBalance(long amount, String date) {
        this.amount = amount;
        this.date = date;
    }
}
