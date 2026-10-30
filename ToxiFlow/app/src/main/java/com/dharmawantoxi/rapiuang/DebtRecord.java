package com.dharmawantoxi.rapiuang;

/** An amount the user owes (debt) or is owed (receivable). */
public final class DebtRecord {
    public static final String DEBT = "debt";
    public static final String RECEIVABLE = "receivable";

    public final long id;
    public final String type;
    public final String person;
    public final long amount;
    public final long remaining;
    public final String date;
    public final String note;
    public final String createdAt;

    public DebtRecord(long id, String type, String person, long amount, long remaining,
                      String date, String note, String createdAt) {
        this.id = id;
        this.type = type;
        this.person = person == null ? "" : person;
        this.amount = amount;
        this.remaining = remaining;
        this.date = date;
        this.note = note == null ? "" : note;
        this.createdAt = createdAt;
    }

    public boolean isSettled() { return remaining == 0; }
}
