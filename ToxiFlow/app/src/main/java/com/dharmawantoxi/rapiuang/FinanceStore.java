package com.dharmawantoxi.rapiuang;

import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/** Private, local-only storage. Nothing is seeded or sent to a server. */
public final class FinanceStore extends SQLiteOpenHelper {
    private static final String DATABASE_NAME = "rapiuang.db";
    private static final String TABLE = "transactions";
    private static final String[] COLUMNS = {"_id", "type", "amount", "title", "category", "date", "note"};
    private static volatile String databaseForUiTests;

    public FinanceStore(Context context) { this(context, currentDatabaseName()); }
    FinanceStore(Context context, String databaseName) { super(context, databaseName, null, 1); }

    private static String currentDatabaseName() {
        String override = databaseForUiTests;
        return override == null ? DATABASE_NAME : override;
    }

    /** Instrumented UI tests use a separate database and never erase the user's records. */
    static void useDatabaseForUiTests(Context context, boolean enabled) {
        if ((context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0)
            throw new IllegalStateException("Penggantian database hanya untuk tes debug.");
        databaseForUiTests = enabled ? "rapiuang-ui-test.db" : null;
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE transactions ("
                + "_id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "type TEXT NOT NULL CHECK(type IN ('income','expense')), "
                + "amount INTEGER NOT NULL CHECK(amount > 0 AND amount <= 999999999999), "
                + "title TEXT NOT NULL CHECK(length(trim(title)) BETWEEN 1 AND 80), "
                + "category TEXT NOT NULL, date TEXT NOT NULL CHECK(length(date) = 10), "
                + "note TEXT NOT NULL DEFAULT '' CHECK(length(note) <= 300))");
        db.execSQL("CREATE INDEX transactions_date ON transactions(date DESC, _id DESC)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Add explicit, non-destructive migrations before increasing the version number.
        throw new IllegalStateException("Migrasi basis data belum tersedia.");
    }

    public long save(Transaction t) {
        FinanceMath.ValidationError error = FinanceMath.validate(t.type, Long.toString(t.amount),
                t.title, t.category, t.date, t.note);
        if (error != null) throw new IllegalArgumentException(error.message);
        ContentValues values = new ContentValues();
        values.put("type", t.type);
        values.put("amount", t.amount);
        values.put("title", t.title.trim());
        values.put("category", t.category);
        values.put("date", t.date);
        values.put("note", t.note.trim());
        SQLiteDatabase db = getWritableDatabase();
        if (t.id <= 0) return db.insertOrThrow(TABLE, null, values);
        int changed = db.update(TABLE, values, "_id = ?", new String[]{Long.toString(t.id)});
        if (changed != 1) throw new IllegalStateException("Transaksi tidak ditemukan. Muat ulang dan coba lagi.");
        return t.id;
    }

    public boolean delete(long id) {
        return getWritableDatabase().delete(TABLE, "_id = ?", new String[]{Long.toString(id)}) == 1;
    }

    public Transaction get(long id) {
        try (Cursor cursor = getReadableDatabase().query(TABLE, COLUMNS, "_id = ?",
                new String[]{Long.toString(id)}, null, null, null)) {
            return cursor.moveToFirst() ? read(cursor) : null;
        }
    }

    /** Returns newest calendar dates first and newest insertion first within each date. */
    public List<Transaction> all() {
        List<Transaction> transactions = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query(TABLE, COLUMNS, null, null,
                null, null, "date DESC, _id DESC")) {
            while (cursor.moveToNext()) transactions.add(read(cursor));
        }
        return transactions;
    }

    private static Transaction read(Cursor c) {
        return new Transaction(c.getLong(0), c.getString(1), c.getLong(2), c.getString(3),
                c.getString(4), c.getString(5), c.getString(6));
    }
}
