package com.dharmawantoxi.rapiuang;

/** A partial or final payment against a debt or receivable. */
public final class DebtPayment {
    public final long id;
    public final long recordId;
    public final long amount;
    public final String date;
    public final String note;
    public final String createdAt;

    public DebtPayment(long id, long recordId, long amount, String date, String note, String createdAt) {
        this.id = id;
        this.recordId = recordId;
        this.amount = amount;
        this.date = date;
        this.note = note == null ? "" : note;
        this.createdAt = createdAt;
    }
}
