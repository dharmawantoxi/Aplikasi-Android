package com.dharmawantoxi.rapiuang;

import static org.junit.Assert.*;

import android.content.Context;
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
}
