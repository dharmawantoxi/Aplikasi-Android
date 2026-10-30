package com.dharmawantoxi.rapiuang;

import static org.junit.Assert.*;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class FinanceStoreTest {
    private static final String TEST_DB = "rapiuang-instrumented-test.db";
    private Context context;
    private FinanceStore store;

    @Before public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        context.deleteDatabase(TEST_DB);
        store = new FinanceStore(context, TEST_DB);
    }

    @After public void tearDown() {
        store.close();
        context.deleteDatabase(TEST_DB);
    }

    @Test public void insertUpdateDeleteAndReopen() {
        long expense = store.save(new Transaction(0, Transaction.EXPENSE, 25000, "Makan", "Makan & minum", "2026-10-03", ""));
        long income = store.save(new Transaction(0, Transaction.INCOME, 100000, "Gaji", "Gaji", "2026-10-04", "bulanan"));
        assertTrue(expense > 0 && income > expense);
        store.close();
        store = new FinanceStore(context, TEST_DB);
        assertEquals(2, store.all().size());
        assertTrue(CreationTime.isValid(store.get(expense).createdAt));
        assertTrue(CreationTime.isValid(store.get(income).createdAt));
        assertEquals("Gaji", store.all().get(0).title);
        assertEquals(2, FinanceMath.forMonth(store.all(), 2026, 9).size());
        assertEquals(0, FinanceMath.forMonth(store.all(), 2026, 10).size());
        store.save(new Transaction(expense, Transaction.EXPENSE, 30000, "Makan siang", "Makan & minum", "2026-10-03", "diperbarui"));
        assertEquals(30000, store.get(expense).amount);
        assertEquals("Makan siang", store.get(expense).title);
        assertTrue(store.delete(income));
        assertFalse(store.delete(income));
        assertNull(store.get(income));
        assertEquals(1, store.all().size());
    }

    @Test public void invalidTransactionsAndMissingUpdatesAreRejected() {
        try {
            store.save(new Transaction(0, Transaction.EXPENSE, 0, "Tidak valid", "Lainnya", "2026-10-03", ""));
            fail("zero amount accepted");
        } catch (IllegalArgumentException expected) { }
        try {
            store.save(new Transaction(9999, Transaction.INCOME, 100, "Tidak ada", "Gaji", "2026-10-03", ""));
            fail("missing update accepted");
        } catch (IllegalStateException expected) { }
        assertTrue(store.all().isEmpty());
    }

    @Test public void creationTimeIsCapturedForBothTypesAndNeverChangedOnEdit() {
        store.close();
        final String[] clock = {"2026-11-10 11:22:33"};
        store = new FinanceStore(context, TEST_DB, () -> clock[0]);
        long expense = store.save(new Transaction(0, Transaction.EXPENSE, 25000,
                "Makan", "Makan & minum", "2026-10-01", ""));
        clock[0] = "2026-11-10 11:23:44";
        long income = store.save(new Transaction(0, Transaction.INCOME, 100000,
                "Gaji", "Gaji", "2026-10-02", ""));
        assertEquals("2026-11-10 11:22:33", store.get(expense).createdAt);
        assertEquals("2026-11-10 11:23:44", store.get(income).createdAt);
        clock[0] = "2026-12-01 08:00:00";
        // Even an update changing type/date or supplying a different timestamp cannot rewrite it.
        store.save(new Transaction(expense, Transaction.INCOME, 30000, "Bonus", "Gaji",
                "2026-09-01", "diubah", "2099-01-01 00:00:00"));
        assertEquals("2026-11-10 11:22:33", store.get(expense).createdAt);
        store.close();
        store = new FinanceStore(context, TEST_DB);
        assertEquals("2026-11-10 11:22:33", store.get(expense).createdAt);
        assertEquals("2026-11-10 11:23:44", store.get(income).createdAt);
        assertEquals("2026-09-01", store.get(expense).date);
    }

    @Test public void upgradingVersionOneKeepsDataAndLeavesOldTimesUnknown() {
        store.close();
        SQLiteDatabase legacy = context.openOrCreateDatabase(TEST_DB, Context.MODE_PRIVATE, null);
        try {
            // Exact version-one schema, before the created_at column existed.
            legacy.execSQL("CREATE TABLE transactions ("
                    + "_id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "type TEXT NOT NULL CHECK(type IN ('income','expense')), "
                    + "amount INTEGER NOT NULL CHECK(amount > 0 AND amount <= 999999999999), "
                    + "title TEXT NOT NULL CHECK(length(trim(title)) BETWEEN 1 AND 80), "
                    + "category TEXT NOT NULL, date TEXT NOT NULL CHECK(length(date) = 10), "
                    + "note TEXT NOT NULL DEFAULT '' CHECK(length(note) <= 300))");
            legacy.execSQL("CREATE INDEX transactions_date ON transactions(date DESC, _id DESC)");
            legacy.execSQL("INSERT INTO transactions (_id,type,amount,title,category,date,note) VALUES "
                    + "(1,'expense',25000,'Makan','Makan & minum','2026-10-01','lama')");
            legacy.execSQL("INSERT INTO transactions (_id,type,amount,title,category,date,note) VALUES "
                    + "(2,'income',100000,'Gaji','Gaji','2026-10-02','bulan lalu')");
            legacy.setVersion(1);
        } finally {
            legacy.close();
        }
        store = new FinanceStore(context, TEST_DB, () -> "2026-11-10 11:22:33");
        assertEquals(6, store.getReadableDatabase().getVersion());
        assertEquals(2, store.all().size());
        Transaction oldExpense = store.get(1);
        assertEquals("Makan", oldExpense.title);
        assertEquals("lama", oldExpense.note);
        assertEquals(25000, oldExpense.amount);
        assertEquals("2026-10-01", oldExpense.date);
        assertNull(oldExpense.createdAt);
        assertFalse(oldExpense.hasPhoto);
        assertNull(store.getPhoto(1));
        assertNull(store.get(2).createdAt);
        assertFalse(store.get(2).hasPhoto);
        assertEquals("75000", FinanceMath.summarize(store.all()).balance.toString());
        store.save(new Transaction(1, Transaction.EXPENSE, 26000, "Makan siang", "Makan & minum",
                "2026-10-01", "lama", "2026-10-01 10:00:00"));
        assertNull(store.get(1).createdAt); // Editing cannot invent a time for old data.
        long fresh = store.save(new Transaction(0, Transaction.INCOME, 5000, "Baru", "Gaji",
                "2026-10-03", ""));
        assertEquals("2026-11-10 11:22:33", store.get(fresh).createdAt);
        store.close();
        store = new FinanceStore(context, TEST_DB);
        assertEquals(3, store.all().size());
        assertNull(store.get(2).createdAt);
        assertEquals("2026-11-10 11:22:33", store.get(fresh).createdAt);
    }

    @Test public void upgradingVersionTwoPreservesTimesAndStartsWithoutPhotos() {
        store.close();
        SQLiteDatabase legacy = context.openOrCreateDatabase(TEST_DB, Context.MODE_PRIVATE, null);
        try {
            legacy.execSQL("CREATE TABLE transactions (_id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "type TEXT NOT NULL, amount INTEGER NOT NULL, title TEXT NOT NULL, "
                    + "category TEXT NOT NULL, date TEXT NOT NULL, note TEXT NOT NULL DEFAULT '', created_at TEXT)");
            legacy.execSQL("CREATE INDEX transactions_date ON transactions(date DESC, _id DESC)");
            legacy.execSQL("INSERT INTO transactions (type,amount,title,category,date,note,created_at) VALUES (?,?,?,?,?,?,?)",
                    new Object[]{"income", 10000, "Lama", "Gaji", "2026-10-01", "catatan", "2026-10-02 08:12:00"});
            legacy.execSQL("INSERT INTO transactions (type,amount,title,category,date,note,created_at) VALUES (?,?,?,?,?,?,?)",
                    new Object[]{"expense", 3000, "Makan", "Makan & minum", "2026-10-03", "", null});
            legacy.setVersion(2);
        } finally { legacy.close(); }
        store = new FinanceStore(context, TEST_DB);
        assertEquals(6, store.getReadableDatabase().getVersion());
        assertEquals(2, store.all().size());
        assertEquals("2026-10-02 08:12:00", store.get(1).createdAt);
        assertNull(store.get(2).createdAt);
        assertEquals("catatan", store.get(1).note);
        assertFalse(store.get(1).hasPhoto);
        assertNull(store.getPhoto(1));
        byte[] image = jpeg(1);
        store.save(new Transaction(1, Transaction.INCOME, 10000, "Lama", "Gaji", "2026-10-01", "catatan"),
                FinanceStore.PhotoAction.REPLACE, image);
        assertArrayEquals(image, store.getPhoto(1));
        assertEquals("2026-10-02 08:12:00", store.get(1).createdAt);
        assertFalse(store.get(2).hasPhoto);
        store.close();
        store = new FinanceStore(context, TEST_DB);
        assertTrue(store.get(1).hasPhoto);
        assertArrayEquals(image, store.getPhoto(1));
    }

    @Test public void debtAndReceivableFlowsChangeCashButNotIncomeExpenseReports() {
        long regularIncome = store.save(new Transaction(0, Transaction.INCOME, 50000,
                "Gaji", "Gaji", "2026-10-01", ""));
        long debt = store.saveDebtRecord(DebtRecord.DEBT, "Rina", 100000, "2026-10-02", "Pinjaman diterima");
        assertEquals("150000", store.availableFunds().toString());
        long receivable = store.saveDebtRecord(DebtRecord.RECEIVABLE, "Budi", 20000, "2026-10-03", "Dipinjamkan");
        assertEquals("130000", store.availableFunds().toString());
        store.saveDebtPayment(debt, 25000, "2026-10-04", "Cicilan");
        assertEquals("105000", store.availableFunds().toString());
        store.saveDebtPayment(receivable, 5000, "2026-10-05", "Dikembalikan");
        assertEquals("110000", store.availableFunds().toString());
        assertEquals(50000, store.get(regularIncome).amount);
        assertEquals("50000", FinanceMath.summarize(store.all()).balance.toString());
        assertEquals(2, store.allDebtRecords().size());
        assertEquals(1, store.paymentsForDebt(debt).size());
        assertEquals(75000, store.allDebtRecords().get(1).remaining);
        assertEquals(15000, store.allDebtRecords().get(0).remaining);
        try {
            store.saveDebtPayment(debt, 75001, "2026-10-06", "kelebihan");
            fail("Payment exceeding remaining amount accepted");
        } catch (IllegalArgumentException expected) { }
        assertEquals("110000", store.availableFunds().toString());
        assertEquals("50000", FinanceMath.summarize(store.all()).balance.toString());
        store.close();
        store = new FinanceStore(context, TEST_DB);
        assertEquals("110000", store.availableFunds().toString());
        assertEquals(1, store.paymentsForDebt(debt).size());
        assertEquals(1, store.paymentsForDebt(receivable).size());
    }

    @Test public void savingsTransfersStaySeparateFromOrdinaryTransactions() {
        store.save(new Transaction(0, Transaction.INCOME, 100000,
                "Gaji", "Gaji", "2026-10-01", ""));
        store.save(new Transaction(0, Transaction.EXPENSE, 30000,
                "Belanja", "Belanja", "2026-10-02", ""));
        assertEquals("70000", store.availableFunds().toString());
        store.saveSavingsMovement("deposit", 40000, "2026-10-03");
        assertEquals("30000", store.availableFunds().toString());
        store.save(new Transaction(0, Transaction.EXPENSE, 5000,
                "Kopi", "Makan & minum", "2026-10-03", ""));
        assertEquals("40000", store.savingsBalance().toString());
        assertEquals("25000", store.availableFunds().toString());
        assertEquals("40000", store.savingsBalance().toString());
        assertEquals("65000", FinanceMath.summarize(store.all()).balance.toString());
        try {
            store.saveSavingsMovement("deposit", 25001, "2026-10-03");
            fail("Deposit exceeding available cash accepted");
        } catch (IllegalArgumentException expected) { }
        try {
            store.saveSavingsMovement("withdrawal", 40001, "2026-10-03");
            fail("Withdrawal exceeding savings accepted");
        } catch (IllegalArgumentException expected) { }
        store.saveSavingsMovement("withdrawal", 10000, "2026-10-04");
        assertEquals("35000", store.availableFunds().toString());
        assertEquals("30000", store.savingsBalance().toString());
        store.close();
        store = new FinanceStore(context, TEST_DB);
        assertEquals("35000", store.availableFunds().toString());
        assertEquals("30000", store.savingsBalance().toString());
        assertEquals("65000", FinanceMath.summarize(store.all()).balance.toString());
    }

    @Test public void transactionCanSplitIncomeAndExpenseBetweenAvailableFundsAndSavings() {
        long incomeId = store.save(new Transaction(0, Transaction.INCOME, 100000,
                "Gaji", "Gaji", "2026-10-01", "", null, false, 40000));
        assertEquals("60000", store.availableFunds().toString());
        assertEquals("40000", store.savingsBalance().toString());

        long expenseId = store.save(new Transaction(0, Transaction.EXPENSE, 30000,
                "Belanja", "Belanja", "2026-10-02", "", null, false, 10000));
        assertEquals("40000", store.availableFunds().toString());
        assertEquals("30000", store.savingsBalance().toString());
        assertEquals("70000", FinanceMath.summarize(store.all()).balance.toString());
        assertEquals(10000, store.get(expenseId).savingsAmount);

        try {
            store.save(new Transaction(0, Transaction.EXPENSE, 50000,
                    "Liburan", "Hiburan", "2026-10-03", "", null, false, 35000));
            fail("Expense exceeding savings balance accepted");
        } catch (IllegalArgumentException expected) { }
        try {
            store.save(new Transaction(expenseId, Transaction.EXPENSE, 30000,
                    "Belanja", "Belanja", "2026-10-02", "", null, false, 45000));
            fail("Edited expense exceeding savings balance accepted");
        } catch (IllegalArgumentException expected) { }
        assertEquals("30000", store.savingsBalance().toString());
        assertTrue(store.delete(expenseId));
        assertEquals("60000", store.availableFunds().toString());
        assertEquals("40000", store.savingsBalance().toString());
        assertEquals(100000, store.get(incomeId).amount);
    }

    @Test public void upgradingVersionThreeAddsDebtTablesWithoutChangingTransactions() {
        store.close();
        SQLiteDatabase legacy = context.openOrCreateDatabase(TEST_DB, Context.MODE_PRIVATE, null);
        try {
            legacy.execSQL("CREATE TABLE transactions (_id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "type TEXT NOT NULL CHECK(type IN ('income','expense')), amount INTEGER NOT NULL, "
                    + "title TEXT NOT NULL, category TEXT NOT NULL, date TEXT NOT NULL, "
                    + "note TEXT NOT NULL DEFAULT '', created_at TEXT, photo BLOB)");
            legacy.execSQL("CREATE INDEX transactions_date ON transactions(date DESC, _id DESC)");
            legacy.execSQL("INSERT INTO transactions (type,amount,title,category,date,note,created_at) "
                    + "VALUES ('income',50000,'Lama','Gaji','2026-10-01','tetap','2026-10-01 10:00:00')");
            legacy.setVersion(3);
        } finally { legacy.close(); }
        store = new FinanceStore(context, TEST_DB);
        assertEquals(6, store.getReadableDatabase().getVersion());
        assertEquals(1, store.all().size());
        assertEquals("tetap", store.get(1).note);
        assertTrue(store.allDebtRecords().isEmpty());
        assertEquals("50000", store.availableFunds().toString());
        store.saveDebtRecord(DebtRecord.DEBT, "Rina", 10000, "2026-10-02", "baru");
        assertEquals("60000", store.availableFunds().toString());
        assertEquals("50000", FinanceMath.summarize(store.all()).balance.toString());
    }

    private static byte[] jpeg(int marker) {
        return new byte[]{(byte) 0xff, (byte) 0xd8, (byte) marker, (byte) 0xff, (byte) 0xd9};
    }

    @Test public void optionalPhotosAreAtomicAndPersistForBothTransactionTypes() {
        byte[] first = jpeg(1), second = jpeg(2);
        Transaction expense = new Transaction(0, Transaction.EXPENSE, 25000,
                "Makan", "Makan & minum", "2026-10-01", "awal");
        Transaction income = new Transaction(0, Transaction.INCOME, 100000,
                "Gaji", "Gaji", "2026-10-02", "");
        long expenseId = store.save(expense, FinanceStore.PhotoAction.REPLACE, first);
        long incomeId = store.save(income);
        assertTrue(store.get(expenseId).hasPhoto);
        assertFalse(store.get(incomeId).hasPhoto);
        assertArrayEquals(first, store.getPhoto(expenseId));
        assertNull(store.getPhoto(incomeId));
        String created = store.get(expenseId).createdAt;
        store.close();
        store = new FinanceStore(context, TEST_DB);
        assertArrayEquals(first, store.getPhoto(expenseId));
        Transaction edit = new Transaction(expenseId, Transaction.EXPENSE, 26000,
                "Makan siang", "Makan & minum", "2026-10-01", "diubah");
        store.save(edit); // Ordinary edits never clear the existing photo.
        assertArrayEquals(first, store.getPhoto(expenseId));
        assertEquals(created, store.get(expenseId).createdAt);
        Transaction editIncome = new Transaction(incomeId, Transaction.INCOME, 100000,
                "Gaji", "Gaji", "2026-10-02", "");
        store.save(editIncome, FinanceStore.PhotoAction.REPLACE, second);
        assertTrue(store.get(incomeId).hasPhoto);
        assertArrayEquals(second, store.getPhoto(incomeId));
        try {
            store.save(new Transaction(expenseId, Transaction.EXPENSE, 27000, "Invalid", "Makan & minum",
                    "2026-10-01", ""), FinanceStore.PhotoAction.REPLACE, new byte[]{1, 2, 3});
            fail("Invalid replacement photo accepted");
        } catch (IllegalArgumentException expected) { }
        assertEquals(26000, store.get(expenseId).amount);
        assertArrayEquals(first, store.getPhoto(expenseId));
        // A database failure cannot commit only the photo or only the other fields.
        store.getWritableDatabase().execSQL("CREATE TRIGGER reject_update BEFORE UPDATE ON transactions "
                + "BEGIN SELECT RAISE(ABORT, 'test'); END");
        try {
            store.save(edit, FinanceStore.PhotoAction.REPLACE, second);
            fail("Failed database update accepted");
        } catch (RuntimeException expected) { }
        store.getWritableDatabase().execSQL("DROP TRIGGER reject_update");
        assertEquals(26000, store.get(expenseId).amount);
        assertArrayEquals(first, store.getPhoto(expenseId));
        store.save(edit, FinanceStore.PhotoAction.REPLACE, second);
        assertArrayEquals(second, store.getPhoto(expenseId));
        store.save(edit, FinanceStore.PhotoAction.REMOVE, null);
        assertFalse(store.get(expenseId).hasPhoto);
        assertNull(store.getPhoto(expenseId));
        assertEquals("Makan siang", store.get(expenseId).title);
        assertEquals(created, store.get(expenseId).createdAt);
        store.close();
        store = new FinanceStore(context, TEST_DB);
        assertNull(store.getPhoto(expenseId));
        assertArrayEquals(second, store.getPhoto(incomeId));
        assertTrue(store.delete(incomeId));
        assertNull(store.getPhoto(incomeId));
        assertFalse(store.delete(incomeId));
        assertEquals(1, store.all().size());
    }

    @Test public void listingManyLargePhotosDoesNotSelectBlobData() {
        byte[] max = new byte[PhotoPolicy.MAX_STORED_BYTES];
        max[0] = (byte) 0xff; max[1] = (byte) 0xd8;
        max[max.length - 2] = (byte) 0xff; max[max.length - 1] = (byte) 0xd9;
        for (int i = 0; i < 8; i++) {
            store.save(new Transaction(0, Transaction.EXPENSE, 1, "Struk " + i,
                    "Belanja", "2026-10-01", ""), FinanceStore.PhotoAction.REPLACE, max);
        }
        assertEquals(8, store.all().size());
        for (Transaction transaction : store.all()) assertTrue(transaction.hasPhoto);
        assertEquals(PhotoPolicy.MAX_STORED_BYTES, store.getPhoto(store.all().get(0).id).length);
    }

    @Test public void invalidClockDoesNotInsertATransaction() {
        store.close();
        store = new FinanceStore(context, TEST_DB, () -> "not-a-time");
        try {
            store.save(new Transaction(0, Transaction.EXPENSE, 100, "Makan", "Makan & minum", "2026-10-01", ""));
            fail("Invalid creation time accepted");
        } catch (IllegalStateException expected) { }
        assertTrue(store.all().isEmpty());
    }
}
