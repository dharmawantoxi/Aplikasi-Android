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
    // Local device date/time captured at creation, yyyy-MM-dd HH:mm:ss; null for older records.
    public final String createdAt;
    // The image itself is loaded separately, so lists never load photo blobs into CursorWindow.
    public final boolean hasPhoto;

    public Transaction(long id, String type, long amount, String title, String category,
                       String date, String note) {
        this(id, type, amount, title, category, date, note, null);
    }

    public Transaction(long id, String type, long amount, String title, String category,
                       String date, String note, String createdAt) {
        this(id, type, amount, title, category, date, note, createdAt, false);
    }

    public Transaction(long id, String type, long amount, String title, String category,
                       String date, String note, String createdAt, boolean hasPhoto) {
        this.id = id;
        this.type = type;
        this.amount = amount;
        this.title = title;
        this.category = category;
        this.date = date;
        this.note = note;
        this.createdAt = createdAt;
        this.hasPhoto = hasPhoto;
    }
}
