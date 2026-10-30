package com.dharmawantoxi.rapiuang;

import static org.junit.Assert.*;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;
import android.os.ParcelFileDescriptor;
import android.print.PrintAttributes;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.Collections;

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

    @Test public void databaseSnapshotProducesReadablePdfWithAndWithoutPhotos() throws Exception {
        store.save(transaction(0, Transaction.EXPENSE, 25000, "Struk", "Belanja", "2026-10-01"),
                FinanceStore.PhotoAction.REPLACE, jpeg(Color.RED));
        store.save(transaction(0, Transaction.INCOME, 75000, "Bonus", "Gaji", "2026-10-02"));
        store.save(transaction(0, Transaction.EXPENSE, 500, "Bulan lain", "Belanja", "2026-09-30"));
        PrintAttributes attributes = new PrintAttributes.Builder()
                .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                .setResolution(new PrintAttributes.Resolution("test", "Test", 300, 300))
                .setMinMargins(new PrintAttributes.Margins(500, 500, 500, 500))
                .setColorMode(PrintAttributes.COLOR_MODE_COLOR).build();
        CancellationSignal signal = new CancellationSignal();
        for (boolean includePhotos : new boolean[]{false, true}) {
            File pdfFile = File.createTempFile("toxiflow-snapshot-report-", ".pdf", context.getCacheDir());
            File photoDirectory = null;
            try {
                try (PrintSnapshot snapshot = PrintSnapshot.create(context, new FinanceStore(context, DB),
                        2026, 9, includePhotos, signal)) {
                    photoDirectory = snapshot.directory;
                    assertEquals(2, snapshot.report.transactions.size());
                    assertEquals(BigInteger.valueOf(25000), snapshot.report.summary.expense);
                    assertEquals(BigInteger.valueOf(75000), snapshot.report.summary.income);
                    MonthlyPrintLayout.Plan plan = MonthlyPrintLayout.create(context, attributes,
                            snapshot.report, includePhotos, signal);
                    assertEquals(includePhotos ? 1 : 0, plan.photoPages.size());
                    try (FileOutputStream out = new FileOutputStream(pdfFile)) {
                        MonthlyPrintLayout.writePdf(context, attributes, plan,
                                Collections.singletonList(new int[]{0, plan.pageCount() - 1}), snapshot, out, signal);
                    }
                    try (PdfRenderer pdf = new PdfRenderer(ParcelFileDescriptor.open(pdfFile,
                            ParcelFileDescriptor.MODE_READ_ONLY))) {
                        assertEquals(plan.pageCount(), pdf.getPageCount());
                        if (includePhotos) {
                            MonthlyPrintLayout.PhotoPage photo = plan.photoPages.get(0);
                            try (PdfRenderer.Page page = pdf.openPage(plan.pages.size())) {
                                Bitmap bitmap = Bitmap.createBitmap(page.getWidth(), page.getHeight(), Bitmap.Config.ARGB_8888);
                                try {
                                    bitmap.eraseColor(Color.WHITE);
                                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                                    int x = plan.content.left + plan.content.width() / 2;
                                    int y = plan.content.top + Math.round((photo.imageTop + photo.imageBottom) / 2);
                                    int pixel = bitmap.getPixel(x, y);
                                    assertTrue("The stored photo appears in the PDF", Color.red(pixel) > 180 && Color.green(pixel) < 100);
                                } finally { bitmap.recycle(); }
                            }
                        }
                    }
                }
                if (photoDirectory != null) assertFalse(photoDirectory.exists());
            } finally { pdfFile.delete(); }
        }
    }

    @Test public void snapshotRetainsRequestedLanguageAndCanonicalRecords() throws Exception {
        long id = store.save(transaction(0, Transaction.EXPENSE, 25000, "Struk", "Belanja", "2026-10-01"));
        try (PrintSnapshot snapshot = PrintSnapshot.create(context, new FinanceStore(context, DB),
                2026, 9, false, AppLanguage.ENGLISH, new CancellationSignal())) {
            assertEquals(AppLanguage.ENGLISH, snapshot.report.language);
            assertEquals("October 2026", snapshot.report.period());
            assertEquals("ToxiFlow-Report-2026-10.pdf", snapshot.report.fileName());
            assertEquals(id, snapshot.report.transactions.get(0).id);
            assertEquals("Belanja", snapshot.report.transactions.get(0).category);
            boolean translated = false;
            for (MonthlyReport.Paragraph p : snapshot.report.paragraphs(false))
                if (p.text.equals("Category: Shopping")) translated = true;
            assertTrue(translated);
        }
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
