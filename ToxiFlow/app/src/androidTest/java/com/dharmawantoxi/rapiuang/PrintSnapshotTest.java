package com.dharmawantoxi.rapiuang;

import static org.junit.Assert.*;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;

@RunWith(AndroidJUnit4.class)
public final class PrintSnapshotTest {
    private static final String DB = "rapiuang-print-snapshot-test.db";
    private Context context;
    private FinanceStore store;

    @Before public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        context.deleteDatabase(DB);
        store = new FinanceStore(context, DB);
    }

    @After public void tearDown() {
        store.close();
        context.deleteDatabase(DB);
    }

    private byte[] jpeg(int color) {
        Bitmap bitmap = Bitmap.createBitmap(100, 80, Bitmap.Config.ARGB_8888);
        try {
            bitmap.eraseColor(color);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out));
            return out.toByteArray();
        } finally { bitmap.recycle(); }
    }

    private Transaction transaction(long id, String type, long amount, String title, String category, String date) {
        return new Transaction(id, type, amount, title, category, date, "catatan");
    }

    @Test public void snapshotKeepsSelectedMonthTextAndPhotosStableAcrossLaterEdits() throws Exception {
        byte[] red = jpeg(Color.RED), green = jpeg(Color.GREEN), blue = jpeg(Color.BLUE);
        long expense = store.save(transaction(0, Transaction.EXPENSE, 25000, "Struk", "Belanja", "2026-10-01"),
                FinanceStore.PhotoAction.REPLACE, red);
        long income = store.save(transaction(0, Transaction.INCOME, 75000, "Bonus", "Gaji", "2026-10-02"),
                FinanceStore.PhotoAction.REPLACE, green);
        store.save(transaction(0, Transaction.INCOME, 10000, "Tanpa foto", "Gaji", "2026-10-03"));
        long otherMonth = store.save(transaction(0, Transaction.EXPENSE, 500, "Bulan lain", "Belanja", "2026-09-30"),
                FinanceStore.PhotoAction.REPLACE, blue);
        PrintSnapshot snapshot = PrintSnapshot.create(context, new FinanceStore(context, DB), 2026, 9,
                true, new CancellationSignal());
        File directory = snapshot.directory;
        try {
            assertEquals(3, snapshot.report.transactions.size());
            assertEquals(2, snapshot.report.photoTransactions().size());
            assertEquals(expense, snapshot.report.photoTransactions().get(0).id);
            assertEquals(income, snapshot.report.photoTransactions().get(1).id);
            assertNotNull(directory);
            assertTrue(directory.isDirectory());
            assertArrayEquals(red, snapshot.readPhoto(expense));
            assertArrayEquals(green, snapshot.readPhoto(income));
            try { snapshot.readPhoto(otherMonth); fail("Other month's photo included"); }
            catch (PrintSnapshot.PhotoUnavailableException expected) { }

            store.save(transaction(expense, Transaction.EXPENSE, 30000, "Struk diubah", "Belanja", "2026-10-01"),
                    FinanceStore.PhotoAction.REPLACE, blue);
            assertTrue(store.delete(income));
            assertArrayEquals(red, snapshot.readPhoto(expense));
            assertArrayEquals(green, snapshot.readPhoto(income));
            assertEquals("Struk", snapshot.report.photoTransactions().get(0).title);
            assertEquals(25000, snapshot.report.photoTransactions().get(0).amount);
        } finally { snapshot.close(); }
        assertFalse(directory.exists());
        try { snapshot.readPhoto(expense); fail("Closed snapshot accepted"); }
        catch (IOException expected) { }
    }

    @Test public void optingOutSkipsPhotosEvenIfAnOldBlobIsCorrupt() throws Exception {
        byte[] corrupt = {(byte) 0xff, (byte) 0xd8, 1, (byte) 0xff, (byte) 0xd9};
        store.save(transaction(0, Transaction.EXPENSE, 1, "Struk", "Belanja", "2026-10-01"),
                FinanceStore.PhotoAction.REPLACE, corrupt);
        try (PrintSnapshot snapshot = PrintSnapshot.create(context, new FinanceStore(context, DB),
                2026, 9, false, new CancellationSignal())) {
            assertFalse(snapshot.includePhotos);
            assertNull(snapshot.directory);
            assertEquals(1, snapshot.report.transactions.size());
            assertEquals(1, snapshot.report.photoTransactions().size());
            boolean saysOmitted = false;
            for (MonthlyReport.Paragraph p : snapshot.report.paragraphs(false))
                if (p.text.equals("Foto tidak disertakan dalam laporan ini.")) saysOmitted = true;
            assertTrue(saysOmitted);
        }
    }

    @Test public void invalidPhotoOrCancellationFailsWithoutLeavingAJobDirectory() throws Exception {
        byte[] corrupt = {(byte) 0xff, (byte) 0xd8, 1, (byte) 0xff, (byte) 0xd9};
        store.save(transaction(0, Transaction.EXPENSE, 1, "Struk", "Belanja", "2026-10-01"),
                FinanceStore.PhotoAction.REPLACE, corrupt);
        int before = jobCount();
        try {
            PrintSnapshot.create(context, new FinanceStore(context, DB), 2026, 9, true, new CancellationSignal());
            fail("Corrupt photo silently omitted");
        } catch (PrintSnapshot.PhotoUnavailableException expected) { }
        assertEquals(before, jobCount());
        CancellationSignal canceled = new CancellationSignal(); canceled.cancel();
        try {
            PrintSnapshot.create(context, new FinanceStore(context, DB), 2026, 9, true, canceled);
            fail("Canceled snapshot accepted");
        } catch (OperationCanceledException expected) { }
        assertEquals(before, jobCount());
        assertEquals(1, store.all().size());
    }

    @Test public void unavailableOrStaleCacheDoesNotSilentlyDropAnImage() throws Exception {
        long id = store.save(transaction(0, Transaction.INCOME, 1, "Bukti", "Gaji", "2026-10-01"),
                FinanceStore.PhotoAction.REPLACE, jpeg(Color.RED));
        try (PrintSnapshot snapshot = PrintSnapshot.create(context, new FinanceStore(context, DB),
                2026, 9, true, new CancellationSignal())) {
            assertTrue(snapshot.directory.setLastModified(System.currentTimeMillis() - 8L * 24 * 60 * 60 * 1000));
            PrintSnapshot.pruneOld(context);
            assertFalse(snapshot.directory.exists());
            try { snapshot.readPhoto(id); fail("Missing snapshot photo accepted"); }
            catch (PrintSnapshot.PhotoUnavailableException expected) { }
        }
        assertTrue(store.get(id).hasPhoto); // Stale cache cleanup never touches the database.
    }

    private int jobCount() {
        File root = new File(context.getCacheDir(), "toxiflow-print-snapshots");
        File[] jobs = root.listFiles((dir, name) -> name.startsWith("job-"));
        return jobs == null ? 0 : jobs.length;
    }
}
