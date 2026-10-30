package com.dharmawantoxi.rapiuang;

/** One-time baseline for savings that existed before the app started tracking it. */
public final class SavingsOpeningBalance {
    public final long amount;
    public final String date;

    SavingsOpeningBalance(long amount, String date) {
        this.amount = amount;
        this.date = date;
    }
}
