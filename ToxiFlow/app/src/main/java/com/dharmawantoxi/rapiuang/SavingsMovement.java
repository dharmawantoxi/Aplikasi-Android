package com.dharmawantoxi.rapiuang;

/** A manual transfer between available funds and the separate savings balance. */
public final class SavingsMovement {
    public static final String DEPOSIT = "deposit";
    public static final String WITHDRAWAL = "withdrawal";

    public final long id;
    public final String type;
    public final long amount;
    public final String date;
    public final String createdAt;

    SavingsMovement(long id, String type, long amount, String date, String createdAt) {
        this.id = id;
        this.type = type;
        this.amount = amount;
        this.date = date;
        this.createdAt = createdAt;
    }
}
