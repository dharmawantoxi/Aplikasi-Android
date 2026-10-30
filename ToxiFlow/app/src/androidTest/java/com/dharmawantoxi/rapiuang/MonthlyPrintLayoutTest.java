package com.dharmawantoxi.rapiuang;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.pdf.PdfRenderer;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;
import android.os.ParcelFileDescriptor;
import android.print.PrintAttributes;
import android.text.StaticLayout;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RunWith(AndroidJUnit4.class)
public final class MonthlyPrintLayoutTest {
    private PrintAttributes attributes(PrintAttributes.MediaSize size, int marginMils) {
        return new PrintAttributes.Builder()
                .setMediaSize(size)
                .setResolution(new PrintAttributes.Resolution("test", "Test", 300, 300))
                .setMinMargins(new PrintAttributes.Margins(marginMils, marginMils, marginMils, marginMils))
                .setColorMode(PrintAttributes.COLOR_MODE_MONOCHROME)
                .build();
    }

    @Test public void wrapsAllTextAndWritesFullAndSelectedPages() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        List<Transaction> entries = new ArrayList<>();
        char[] chars = new char[270];
        Arrays.fill(chars, 'x'); // A long unbroken word must wrap, not disappear.
        for (int i = 0; i < 45; i++) {
            entries.add(new Transaction(i + 1, i % 2 == 0 ? Transaction.INCOME : Transaction.EXPENSE,
                    10_000 + i, "Transaksi " + i + " – kebutuhan", i % 2 == 0 ? "Gaji" : "Belanja",
                    "2026-10-15", "Baris pertama\n" + new String(chars) + " café 🧾", "2026-10-16 14:05:07"));
        }
        entries.add(new Transaction(99, Transaction.INCOME, 42, "Bulan lain", "Gaji", "2026-09-30", ""));
        MonthlyReport report = MonthlyReport.forMonth(2026, 9, entries);
        PrintAttributes attrs = attributes(PrintAttributes.MediaSize.ISO_A4, 500);
        CancellationSignal signal = new CancellationSignal();
        MonthlyPrintLayout.Plan plan = MonthlyPrintLayout.create(context, attrs, report, signal);
        assertTrue("Several pages are expected", plan.pageCount() > 1);
        assertTrue("A4 portrait", plan.content.height() > plan.content.width());
        boolean hasSectionBand = false, hasMetricCard = false, hasCategoryRow = false, hasTransactionBand = false;
        for (List<MonthlyPrintLayout.Line> page : plan.pages) {
            for (MonthlyPrintLayout.Line line : page) {
                hasSectionBand |= line.style == MonthlyReport.Style.SECTION && line.firstInParagraph;
                hasMetricCard |= line.style == MonthlyReport.Style.METRIC_BALANCE;
                hasCategoryRow |= line.style == MonthlyReport.Style.CATEGORY;
                hasTransactionBand |= line.style == MonthlyReport.Style.TRANSACTION_HEADER;
            }
        }
        assertTrue("PDF layout has hierarchy bands", hasSectionBand);
        assertTrue("PDF layout has a featured balance card", hasMetricCard);
        assertTrue("PDF layout has tabular category rows", hasCategoryRow);
        assertTrue("PDF layout has transaction entry bands", hasTransactionBand);

        // Reassemble every laid-out line, including those split across pages, and compare the
        // exact original paragraphs. This checks that wrapping never truncates long notes.
        List<String> actual = new ArrayList<>();
        StringBuilder current = null;
        StaticLayout previous = null;
        int expectedStart = 0;
        for (List<MonthlyPrintLayout.Line> page : plan.pages) {
            assertTrue("Every page has body text", !page.isEmpty());
            for (MonthlyPrintLayout.Line line : page) {
                assertTrue("Line starts inside content", line.top >= 0);
                assertTrue("Line fits between header and footer", line.top + line.height <= plan.content.height() - 78);
                if (line.layout != previous) {
                    if (current != null) actual.add(current.toString());
                    current = new StringBuilder();
                    expectedStart = 0;
                    previous = line.layout;
                }
                int start = line.layout.getLineStart(line.line);
                int end = line.layout.getLineEnd(line.line);
                assertEquals("No text omitted or repeated", expectedStart, start);
                current.append(line.layout.getText(), start, end);
                expectedStart = end;
            }
        }
        if (current != null) actual.add(current.toString());
        List<MonthlyReport.Paragraph> expected = report.paragraphs();
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) assertEquals(expected.get(i).text, actual.get(i));

        File full = File.createTempFile("toxiflow-print-", ".pdf", context.getCacheDir());
        File selected = File.createTempFile("toxiflow-pages-", ".pdf", context.getCacheDir());
        try {
            try (FileOutputStream out = new FileOutputStream(full)) {
                MonthlyPrintLayout.writePdf(context, attrs, plan,
                        Collections.singletonList(new int[]{0, plan.pageCount() - 1}), out, signal);
            }
            try (PdfRenderer pdf = new PdfRenderer(ParcelFileDescriptor.open(full, ParcelFileDescriptor.MODE_READ_ONLY))) {
                assertEquals(plan.pageCount(), pdf.getPageCount());
                assertPageHasInk(pdf, 0, plan.content);
                assertPageHasInk(pdf, pdf.getPageCount() - 1, plan.content);
            }
            // Page numbers are zero-based in PrintDocumentAdapter; only the second page is written.
            try (FileOutputStream out = new FileOutputStream(selected)) {
                MonthlyPrintLayout.writePdf(context, attrs, plan,
                        Collections.singletonList(new int[]{1, 1}), out, signal);
            }
            try (PdfRenderer pdf = new PdfRenderer(ParcelFileDescriptor.open(selected, ParcelFileDescriptor.MODE_READ_ONLY))) {
                assertEquals(1, pdf.getPageCount());
                assertPageHasInk(pdf, 0, plan.content);
            }
        } finally {
            full.delete();
            selected.delete();
        }
    }

    @Test public void photoAppendicesAreVisibleOrderedAndFollowSelectedPageRanges() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        List<Transaction> entries = Arrays.asList(
                new Transaction(2, Transaction.INCOME, 2000, "Bukti bonus", "Gaji", "2026-10-02", "", null, true),
                new Transaction(1, Transaction.EXPENSE, 1000, "Struk merah", "Belanja", "2026-10-01", "", null, true),
                new Transaction(3, Transaction.EXPENSE, 500, "Tanpa foto", "Belanja", "2026-10-03", ""),
                new Transaction(4, Transaction.EXPENSE, 500, "Bulan lain", "Belanja", "2026-09-01", "", null, true));
        MonthlyReport report = MonthlyReport.forMonth(2026, 9, entries);
        PrintAttributes attrs = new PrintAttributes.Builder()
                .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                .setResolution(new PrintAttributes.Resolution("test", "Test", 300, 300))
                .setMinMargins(new PrintAttributes.Margins(500, 500, 500, 500))
                .setColorMode(PrintAttributes.COLOR_MODE_COLOR).build();
        CancellationSignal signal = new CancellationSignal();
        MonthlyPrintLayout.Plan plan = MonthlyPrintLayout.create(context, attrs, report, true, signal);
        assertEquals(2, plan.photoPages.size());
        assertEquals(plan.pages.size() + 2, plan.pageCount());
        assertEquals(1, plan.photoPages.get(0).transaction.id);
        assertEquals(2, plan.photoPages.get(1).transaction.id);
        String expenseLabel = plan.photoPages.get(0).label.getText().toString();
        String incomeLabel = plan.photoPages.get(1).label.getText().toString();
        assertTrue(expenseLabel.contains("Struk merah"));
        assertTrue(expenseLabel.contains("1 Oktober 2026 · Pengeluaran"));
        assertTrue(expenseLabel.contains("Belanja · Nominal: Rp1.000"));
        assertTrue(incomeLabel.contains("Bukti bonus"));
        assertTrue(incomeLabel.contains("2 Oktober 2026 · Pemasukan"));
        assertTrue(incomeLabel.contains("Gaji · Nominal: Rp2.000"));
        assertTrue(plan.photoPages.get(0).imageTop >= 0);
        assertTrue(plan.photoPages.get(0).imageBottom < plan.content.height());

        Map<Long, byte[]> images = new HashMap<>();
        images.put(1L, jpeg(Color.RED)); images.put(2L, jpeg(Color.GREEN));
        List<Long> readIds = new ArrayList<>();
        PhotoPrintSource source = id -> { readIds.add(id); return images.get(id); };
        File full = File.createTempFile("toxiflow-photos-", ".pdf", context.getCacheDir());
        File selected = File.createTempFile("toxiflow-photo-page-", ".pdf", context.getCacheDir());
        try {
            try (FileOutputStream out = new FileOutputStream(full)) {
                MonthlyPrintLayout.writePdf(context, attrs, plan,
                        Collections.singletonList(new int[]{0, plan.pageCount() - 1}), source, out, signal);
            }
            assertEquals(Arrays.asList(1L, 2L), readIds);
            try (PdfRenderer pdf = new PdfRenderer(ParcelFileDescriptor.open(full, ParcelFileDescriptor.MODE_READ_ONLY))) {
                assertEquals(plan.pageCount(), pdf.getPageCount());
                assertPhotoColor(pdf, plan.pages.size(), plan.content, plan.photoPages.get(0), true);
                assertPhotoColor(pdf, plan.pages.size() + 1, plan.content, plan.photoPages.get(1), false);
            }
            readIds.clear();
            int secondPhotoPage = plan.pages.size() + 1;
            try (FileOutputStream out = new FileOutputStream(selected)) {
                MonthlyPrintLayout.writePdf(context, attrs, plan,
                        Collections.singletonList(new int[]{secondPhotoPage, secondPhotoPage}), source, out, signal);
            }
            assertEquals(Collections.singletonList(2L), readIds);
            try (PdfRenderer pdf = new PdfRenderer(ParcelFileDescriptor.open(selected, ParcelFileDescriptor.MODE_READ_ONLY))) {
                assertEquals(1, pdf.getPageCount());
                assertPhotoColor(pdf, 0, plan.content, plan.photoPages.get(1), false);
            }
            MonthlyPrintLayout.Plan withoutPhotos = MonthlyPrintLayout.create(context, attrs, report, false, signal);
            assertTrue(withoutPhotos.photoPages.isEmpty());
            assertEquals(withoutPhotos.pages.size(), withoutPhotos.pageCount());
            for (List<MonthlyPrintLayout.Line> page : withoutPhotos.pages)
                for (MonthlyPrintLayout.Line line : page)
                    assertFalse(line.layout.getText().toString().contains("Foto: Lampiran"));
        } finally { full.delete(); selected.delete(); }
    }

    @Test public void englishPhotoAppendixUsesReportLanguageAndKeepsUserText() {
        Context context = ApplicationProvider.getApplicationContext();
        Transaction entry = new Transaction(1, Transaction.EXPENSE, 1000, "Struk merah", "Belanja",
                "2026-10-01", "Catatan", "2026-10-01 12:30:00", true);
        MonthlyReport report = MonthlyReport.forMonth(2026, 9, Collections.singletonList(entry), AppLanguage.ENGLISH);
        MonthlyPrintLayout.Plan plan = MonthlyPrintLayout.create(context,
                attributes(PrintAttributes.MediaSize.ISO_A4, 500), report, true, new CancellationSignal());
        assertEquals(AppLanguage.ENGLISH, plan.language);
        assertEquals("October 2026", plan.period);
        assertEquals(1, plan.photoPages.size());
        assertEquals("Name: Struk merah\nDate: 1 October 2026 · Expense\nCategory: Shopping · Amount: Rp1.000",
                plan.photoPages.get(0).label.getText().toString());
        assertEquals("Belanja", plan.photoPages.get(0).transaction.category);
    }

    @Test public void missingPhotoAndPhotoLimitFailRatherThanProducePartialPdf() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        PrintAttributes attrs = attributes(PrintAttributes.MediaSize.ISO_A4, 500);
        CancellationSignal signal = new CancellationSignal();
        List<Transaction> entries = new ArrayList<>();
        for (int i = 0; i < 65; i++) entries.add(new Transaction(i + 1, Transaction.EXPENSE, 1,
                "Struk " + i, "Belanja", "2026-10-01", "", null, true));
        MonthlyPrintLayout.Plan plan = MonthlyPrintLayout.create(context, attrs,
                MonthlyReport.forMonth(2026, 9, entries), true, signal);
        assertEquals(65, plan.photoPages.size());
        final int[] reads = {0};
        PhotoPrintSource source = id -> { reads[0]++; return jpeg(Color.RED); };
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            MonthlyPrintLayout.writePdf(context, attrs, plan,
                    Collections.singletonList(new int[]{0, plan.pageCount() - 1}), source, out, signal);
            fail("More than the per-write photo limit accepted");
        } catch (MonthlyPrintLayout.TooManyPhotoPagesException expected) { }
        assertEquals(0, reads[0]);
        assertEquals(0, out.size());
        MonthlyPrintLayout.Plan one = MonthlyPrintLayout.create(context, attrs,
                MonthlyReport.forMonth(2026, 9, Collections.singletonList(entries.get(0))), true, signal);
        out.reset();
        try {
            MonthlyPrintLayout.writePdf(context, attrs, one,
                    Collections.singletonList(new int[]{0, one.pageCount() - 1}), id -> null, out, signal);
            fail("Missing photo silently omitted");
        } catch (PrintSnapshot.PhotoUnavailableException expected) { }
        assertEquals(0, out.size());
    }

    private byte[] jpeg(int color) {
        Bitmap image = Bitmap.createBitmap(100, 80, Bitmap.Config.ARGB_8888);
        try {
            image.eraseColor(color);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            assertTrue(image.compress(Bitmap.CompressFormat.JPEG, 90, out));
            return out.toByteArray();
        } finally { image.recycle(); }
    }

    private void assertPhotoColor(PdfRenderer pdf, int pageIndex, Rect content,
                                  MonthlyPrintLayout.PhotoPage photo, boolean red) {
        try (PdfRenderer.Page page = pdf.openPage(pageIndex)) {
            Bitmap bitmap = Bitmap.createBitmap(page.getWidth(), page.getHeight(), Bitmap.Config.ARGB_8888);
            try {
                bitmap.eraseColor(Color.WHITE);
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                int x = content.left + content.width() / 2;
                int y = content.top + Math.round((photo.imageTop + photo.imageBottom) / 2);
                int pixel = bitmap.getPixel(x, y);
                if (red) assertTrue("Red photo is visible", Color.red(pixel) > 180 && Color.green(pixel) < 100);
                else assertTrue("Green photo is visible", Color.green(pixel) > 180 && Color.red(pixel) < 100);
            } finally { bitmap.recycle(); }
        }
    }

    private void assertPageHasInk(PdfRenderer pdf, int index, Rect content) {
        try (PdfRenderer.Page page = pdf.openPage(index)) {
            Bitmap bitmap = Bitmap.createBitmap(page.getWidth(), page.getHeight(), Bitmap.Config.ARGB_8888);
            try {
                bitmap.eraseColor(Color.WHITE);
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                int darkPixels = 0, headerPixels = 0, footerPixels = 0;
                for (int y = 0; y < bitmap.getHeight(); y += 2) {
                    for (int x = 0; x < bitmap.getWidth(); x += 2) {
                        int pixel = bitmap.getPixel(x, y);
                        if (Color.red(pixel) < 180 && Color.green(pixel) < 180 && Color.blue(pixel) < 180) {
                            darkPixels++;
                            if (x >= content.left && x < content.left + 20
                                    && y >= content.top && y < content.top + 16) headerPixels++;
                            if (x >= content.left && x < content.left + 40
                                    && y >= content.bottom - 16 && y < content.bottom) footerPixels++;
                        }
                    }
                }
                assertTrue("PDF page contains visible text", darkPixels > 50);
                assertTrue("Header starts at the printable left/top margin", headerPixels > 0);
                assertTrue("Footer is not clipped by a doubled bottom margin", footerPixels > 0);
            } finally {
                bitmap.recycle();
            }
        }
    }

    @Test public void emptyLandscapeSmallPaperAndCancellation() {
        Context context = ApplicationProvider.getApplicationContext();
        MonthlyReport empty = MonthlyReport.forMonth(2026, 1, Collections.emptyList());
        CancellationSignal signal = new CancellationSignal();
        MonthlyPrintLayout.Plan plan = MonthlyPrintLayout.create(context,
                attributes(PrintAttributes.MediaSize.ISO_A4.asLandscape(), 500), empty, signal);
        assertTrue("Empty reports still have printable pages", plan.pageCount() >= 1);
        assertTrue(plan.content.width() > plan.content.height());
        try {
            PrintAttributes.MediaSize tiny = new PrintAttributes.MediaSize("tiny", "Tiny", 1000, 1000);
            MonthlyPrintLayout.create(context, attributes(tiny, 0), empty, signal);
            fail("Paper that cannot fit a report must fail rather than clip it");
        } catch (MonthlyPrintLayout.PageTooSmallException expected) { }
        signal.cancel();
        try {
            MonthlyPrintLayout.create(context, attributes(PrintAttributes.MediaSize.ISO_A4, 500), empty, signal);
            fail("Canceled layout must stop");
        } catch (OperationCanceledException expected) { }
    }
}
