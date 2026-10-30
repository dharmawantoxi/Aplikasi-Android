package com.dharmawantoxi.rapiuang;

import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/** Private, local-only storage. Nothing is seeded or sent to a server. */
public final class FinanceStore extends SQLiteOpenHelper {
    private static final String DATABASE_NAME = "rapiuang.db";
    private static final String TABLE = "transactions";
    private static final int DATABASE_VERSION = 6;
    private static final String[] COLUMNS = {"_id", "type", "amount", "title", "category", "date", "note", "created_at",
            "savings_amount", "photo IS NOT NULL AS has_photo"};
    enum PhotoAction { KEEP, REPLACE, REMOVE }
    private static volatile String databaseForUiTests;
    interface TimeSource { String now(); }
    private final TimeSource timeSource;

    public FinanceStore(Context context) { this(context, currentDatabaseName()); }
    FinanceStore(Context context, String databaseName) { this(context, databaseName, CreationTime::now); }
    FinanceStore(Context context, String databaseName, TimeSource timeSource) {
        super(context, databaseName, null, DATABASE_VERSION);
        this.timeSource = timeSource;
    }

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
                + "note TEXT NOT NULL DEFAULT '' CHECK(length(note) <= 300), "
                + "created_at TEXT, photo BLOB, "
                + "savings_amount INTEGER NOT NULL DEFAULT 0 CHECK(savings_amount >= 0 AND savings_amount <= amount))");
        db.execSQL("CREATE INDEX transactions_date ON transactions(date DESC, _id DESC)");
        createDebtTables(db);
        createSavingsTable(db);
    }

    private static void createSavingsTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE savings_movements ("
                + "_id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "type TEXT NOT NULL CHECK(type IN ('deposit','withdrawal')), "
                + "amount INTEGER NOT NULL CHECK(amount > 0 AND amount <= 999999999999), "
                + "date TEXT NOT NULL CHECK(length(date) = 10), "
                + "created_at TEXT NOT NULL)");
        db.execSQL("CREATE INDEX savings_movements_date ON savings_movements(date DESC, _id DESC)");
    }

    private static void createDebtTables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE debt_records ("
                + "_id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "type TEXT NOT NULL CHECK(type IN ('debt','receivable')), "
                + "person TEXT NOT NULL CHECK(length(trim(person)) BETWEEN 1 AND 80), "
                + "amount INTEGER NOT NULL CHECK(amount > 0 AND amount <= 999999999999), "
                + "remaining INTEGER NOT NULL CHECK(remaining >= 0 AND remaining <= amount), "
                + "date TEXT NOT NULL CHECK(length(date) = 10), "
                + "note TEXT NOT NULL DEFAULT '' CHECK(length(note) <= 300), "
                + "created_at TEXT NOT NULL)");
        db.execSQL("CREATE INDEX debt_records_date ON debt_records(date DESC, _id DESC)");
        db.execSQL("CREATE TABLE debt_payments ("
                + "_id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "record_id INTEGER NOT NULL, "
                + "amount INTEGER NOT NULL CHECK(amount > 0 AND amount <= 999999999999), "
                + "date TEXT NOT NULL CHECK(length(date) = 10), "
                + "note TEXT NOT NULL DEFAULT '' CHECK(length(note) <= 300), "
                + "created_at TEXT NOT NULL)");
        db.execSQL("CREATE INDEX debt_payments_record ON debt_payments(record_id, _id)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion == 1 && newVersion >= 2) {
            // Existing entries have no reliable creation time; leave this field NULL.
            db.execSQL("ALTER TABLE transactions ADD COLUMN created_at TEXT");
            oldVersion = 2;
        }
        if (oldVersion == 2 && newVersion >= 3) {
            db.execSQL("ALTER TABLE transactions ADD COLUMN photo BLOB");
            oldVersion = 3;
        }
        if (oldVersion == 3 && newVersion >= 4) {
            createDebtTables(db); // Preserve transactions while adding the separate debt ledger.
            oldVersion = 4;
        }
        if (oldVersion == 4 && newVersion >= 5) {
            createSavingsTable(db); // Separate savings ledger; existing transactions remain unchanged.
            oldVersion = 5;
        }
        if (oldVersion == 5 && newVersion >= 6) {
            db.execSQL("ALTER TABLE transactions ADD COLUMN savings_amount INTEGER NOT NULL DEFAULT 0 "
                    + "CHECK(savings_amount >= 0 AND savings_amount <= amount)");
            oldVersion = 6;
        }
        if (oldVersion != newVersion) throw new IllegalStateException("Migrasi basis data belum tersedia.");
    }

    public long save(Transaction t) { return save(t, PhotoAction.KEEP, null); }

    /** Transaction fields and attached photos commit atomically, with savings allocation validated. */
    long save(Transaction t, PhotoAction action, byte[] photo) {
        if (action == null || (action == PhotoAction.REPLACE && !PhotoPolicy.validStoredJpeg(photo))
                || (action != PhotoAction.REPLACE && photo != null))
            throw new IllegalArgumentException("Foto tidak valid atau terlalu besar.");
        FinanceMath.ValidationError error = FinanceMath.validate(t.type, Long.toString(t.amount),
                t.title, t.category, t.date, t.note);
        if (error != null) throw new IllegalArgumentException(error.message);
        if (t.savingsAmount < 0 || t.savingsAmount > t.amount)
            throw new IllegalArgumentException("Bagian tabungan harus antara Rp0 dan nominal transaksi.");
        ContentValues values = new ContentValues();
        values.put("type", t.type);
        values.put("amount", t.amount);
        values.put("savings_amount", t.savingsAmount);
        values.put("title", t.title.trim());
        values.put("category", t.category);
        values.put("date", t.date);
        values.put("note", t.note.trim());
        if (action == PhotoAction.REPLACE) values.put("photo", photo);
        else if (action == PhotoAction.REMOVE) values.putNull("photo");
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            BigInteger savingsBefore = savingsBalance(db);
            if (t.id > 0) {
                try (Cursor cursor = db.query(TABLE, new String[]{"type", "savings_amount"}, "_id = ?",
                        new String[]{Long.toString(t.id)}, null, null, null)) {
                    if (!cursor.moveToFirst()) throw new IllegalStateException("Transaksi tidak ditemukan. Muat ulang dan coba lagi.");
                    BigInteger oldAllocation = BigInteger.valueOf(cursor.getLong(1));
                    savingsBefore = Transaction.INCOME.equals(cursor.getString(0))
                            ? savingsBefore.subtract(oldAllocation) : savingsBefore.add(oldAllocation);
                }
            }
            if (Transaction.EXPENSE.equals(t.type)
                    && savingsBefore.compareTo(BigInteger.valueOf(t.savingsAmount)) < 0)
                throw new IllegalArgumentException("Saldo tabungan tidak cukup untuk bagian pengeluaran ini.");
            long id;
            if (t.id <= 0) {
                String createdAt = timeSource.now();
                if (!CreationTime.isValid(createdAt)) throw new IllegalStateException("Jam perangkat tidak valid.");
                values.put("created_at", createdAt);
                id = db.insertOrThrow(TABLE, null, values);
            } else {
                // Never overwrite the creation time when a transaction is edited.
                int changed = db.update(TABLE, values, "_id = ?", new String[]{Long.toString(t.id)});
                if (changed != 1) throw new IllegalStateException("Transaksi tidak ditemukan. Muat ulang dan coba lagi.");
                id = t.id;
            }
            db.setTransactionSuccessful();
            return id;
        } finally { db.endTransaction(); }
    }

    /** Create debt received (adds cash) or receivable lent (uses cash), outside income/expense. */
    long saveDebtRecord(String type, String person, long amount, String date, String note) {
        if (note == null) note = "";
        DebtMath.validateNew(type, person, amount, date, note);
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            String createdAt = timeSource.now();
            if (!CreationTime.isValid(createdAt)) throw new IllegalStateException("Jam perangkat tidak valid.");
            ContentValues values = new ContentValues();
            values.put("type", type);
            values.put("person", person.trim());
            values.put("amount", amount);
            values.put("remaining", amount);
            values.put("date", date);
            values.put("note", note.trim());
            values.put("created_at", createdAt);
            long id = db.insertOrThrow("debt_records", null, values);
            db.setTransactionSuccessful();
            return id;
        } finally { db.endTransaction(); }
    }

    /** Record a partial or final repayment/collection atomically with the outstanding amount. */
    long saveDebtPayment(long recordId, long amount, String date, String note) {
        if (note == null) note = "";
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            String type;
            long remaining;
            try (Cursor cursor = db.query("debt_records", new String[]{"type", "remaining"},
                    "_id = ?", new String[]{Long.toString(recordId)}, null, null, null)) {
                if (!cursor.moveToFirst()) throw new IllegalArgumentException("Catatan hutang/piutang tidak ditemukan.");
                type = cursor.getString(0);
                remaining = cursor.getLong(1);
            }
            DebtMath.validatePayment(amount, remaining, date, note);
            String createdAt = timeSource.now();
            if (!CreationTime.isValid(createdAt)) throw new IllegalStateException("Jam perangkat tidak valid.");
            ContentValues update = new ContentValues();
            update.put("remaining", remaining - amount);
            if (db.update("debt_records", update, "_id = ? AND remaining = ?",
                    new String[]{Long.toString(recordId), Long.toString(remaining)}) != 1)
                throw new IllegalStateException("Catatan berubah. Muat ulang lalu coba lagi.");
            ContentValues payment = new ContentValues();
            payment.put("record_id", recordId);
            payment.put("amount", amount);
            payment.put("date", date);
            payment.put("note", note.trim());
            payment.put("created_at", createdAt);
            long id = db.insertOrThrow("debt_payments", null, payment);
            db.setTransactionSuccessful();
            return id;
        } finally { db.endTransaction(); }
    }

    List<DebtRecord> allDebtRecords() {
        List<DebtRecord> records = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query("debt_records",
                new String[]{"_id", "type", "person", "amount", "remaining", "date", "note", "created_at"},
                null, null, null, null, "date DESC, _id DESC")) {
            while (cursor.moveToNext()) records.add(new DebtRecord(cursor.getLong(0), cursor.getString(1),
                    cursor.getString(2), cursor.getLong(3), cursor.getLong(4), cursor.getString(5),
                    cursor.getString(6), cursor.getString(7)));
        }
        return records;
    }

    List<DebtPayment> paymentsForDebt(long recordId) {
        List<DebtPayment> payments = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query("debt_payments",
                new String[]{"_id", "record_id", "amount", "date", "note", "created_at"},
                "record_id = ?", new String[]{Long.toString(recordId)}, null, null, "date DESC, _id DESC")) {
            while (cursor.moveToNext()) payments.add(new DebtPayment(cursor.getLong(0), cursor.getLong(1),
                    cursor.getLong(2), cursor.getString(3), cursor.getString(4), cursor.getString(5)));
        }
        return payments;
    }

    /** Transfer between available cash and savings; these movements never enter ordinary reports. */
    void saveSavingsMovement(String type, long amount, String date) {
        if (!("deposit".equals(type) || "withdrawal".equals(type)))
            throw new IllegalArgumentException("Jenis perpindahan tabungan tidak valid.");
        if (amount <= 0 || amount > Money.MAX_TRANSACTION || !Dates.isValid(date))
            throw new IllegalArgumentException("Nominal atau tanggal perpindahan tabungan tidak valid.");
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            BigInteger current = savingsBalance(db);
            BigInteger value = BigInteger.valueOf(amount);
            if ("deposit".equals(type)) {
                if (cashBalance(db).compareTo(value) < 0)
                    throw new IllegalArgumentException("Dana tersedia tidak cukup untuk ditabung.");
            } else if (current.compareTo(value) < 0) {
                throw new IllegalArgumentException("Saldo tabungan tidak cukup untuk diambil.");
            }
            String createdAt = timeSource.now();
            if (!CreationTime.isValid(createdAt)) throw new IllegalStateException("Jam perangkat tidak valid.");
            ContentValues values = new ContentValues();
            values.put("type", type);
            values.put("amount", amount);
            values.put("date", date);
            values.put("created_at", createdAt);
            db.insertOrThrow("savings_movements", null, values);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    BigInteger savingsBalance() { return savingsBalance(getReadableDatabase()); }

    private static BigInteger savingsBalance(SQLiteDatabase db) {
        BigInteger balance = BigInteger.ZERO;
        try (Cursor cursor = db.rawQuery("SELECT type, savings_amount FROM transactions", null)) {
            while (cursor.moveToNext()) {
                BigInteger amount = BigInteger.valueOf(cursor.getLong(1));
                balance = Transaction.INCOME.equals(cursor.getString(0)) ? balance.add(amount) : balance.subtract(amount);
            }
        }
        try (Cursor cursor = db.rawQuery("SELECT type, amount FROM savings_movements", null)) {
            while (cursor.moveToNext()) {
                BigInteger amount = BigInteger.valueOf(cursor.getLong(1));
                balance = "deposit".equals(cursor.getString(0)) ? balance.add(amount) : balance.subtract(amount);
            }
        }
        return balance;
    }

    /** Available cash includes the non-savings portion of transactions and all debt/transfer flows. */
    BigInteger availableFunds() { return cashBalance(getReadableDatabase()); }

    private static BigInteger cashBalance(SQLiteDatabase db) {
        BigInteger balance = BigInteger.ZERO;
        try (Cursor cursor = db.rawQuery("SELECT type, amount, savings_amount FROM transactions", null)) {
            while (cursor.moveToNext()) {
                BigInteger cashPortion = BigInteger.valueOf(cursor.getLong(1) - cursor.getLong(2));
                balance = Transaction.INCOME.equals(cursor.getString(0))
                        ? balance.add(cashPortion) : balance.subtract(cashPortion);
            }
        }
        try (Cursor cursor = db.rawQuery("SELECT type, remaining FROM debt_records", null)) {
            while (cursor.moveToNext()) {
                BigInteger amount = BigInteger.valueOf(cursor.getLong(1));
                balance = DebtRecord.DEBT.equals(cursor.getString(0)) ? balance.add(amount) : balance.subtract(amount);
            }
        }
        try (Cursor cursor = db.rawQuery("SELECT type, amount FROM savings_movements", null)) {
            while (cursor.moveToNext()) {
                BigInteger amount = BigInteger.valueOf(cursor.getLong(1));
                balance = "withdrawal".equals(cursor.getString(0)) ? balance.add(amount) : balance.subtract(amount);
            }
        }
        return balance;
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

    /** Load one bounded image only on demand, not with the transaction list. */
    byte[] getPhoto(long id) {
        try (Cursor cursor = getReadableDatabase().query(TABLE, new String[]{"photo"}, "_id = ?",
                new String[]{Long.toString(id)}, null, null, null)) {
            return cursor.moveToFirst() ? cursor.getBlob(0) : null;
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
                c.getString(4), c.getString(5), c.getString(6), c.getString(7), c.getInt(9) != 0, c.getLong(8));
    }
}
