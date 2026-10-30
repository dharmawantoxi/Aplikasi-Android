package com.dharmawantoxi.rapiuang;

/** A transaction is a positive, whole-rupiah amount and an explicit direction. */
public final class Transaction {
    public static final String INCOME = "income";
    public static final String EXPENSE = "expense";

    public final long id;
    public final String type;
    public final long amount;
    public final String title;
    public final String category;
    public final String date; // Local calendar date in ISO yyyy-MM-dd; never a UTC timestamp.
    public final String note;

    public Transaction(long id, String type, long amount, String title, String category,
                       String date, String note) {
        this.id = id;
        this.type = type;
        this.amount = amount;
        this.title = title;
        this.category = category;
        this.date = date;
        this.note = note;
    }
}
