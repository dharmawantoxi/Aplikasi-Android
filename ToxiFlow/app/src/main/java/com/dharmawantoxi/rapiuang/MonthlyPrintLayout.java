package com.dharmawantoxi.rapiuang;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import android.os.Build;
import android.os.CancellationSignal;
import android.print.PrintAttributes;
import android.print.pdf.PrintedPdfDocument;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

/** Lays out text and optional photo pages using the selected paper and printable margins. */
final class MonthlyPrintLayout {
    static final int MAX_PAGES = 500;
    private static final float HEADER_HEIGHT = 34;
    private static final float FOOTER_HEIGHT = 25;
    private static final float MIN_PHOTO_HEIGHT = 60;
    private static final int MIN_CONTENT_WIDTH = 220;
    private static final int MIN_CONTENT_HEIGHT = 170;

    static final class PageTooSmallException extends IllegalArgumentException { }
    static final class TooManyPhotoPagesException extends IOException { }

    static final class Line {
        final StaticLayout layout;
        final int line;
        final float top;
        final float height;

        Line(StaticLayout layout, int line, float top, float height) {
            this.layout = layout;
            this.line = line;
            this.top = top;
            this.height = height;
        }
    }

    static final class PhotoPage {
        final Transaction transaction;
        final int number;
        final StaticLayout label;
        final float imageTop, imageBottom;

        PhotoPage(Transaction transaction, int number, StaticLayout label, float imageTop, float imageBottom) {
            this.transaction = transaction;
            this.number = number;
            this.label = label;
            this.imageTop = imageTop;
            this.imageBottom = imageBottom;
        }
    }

    static final class Plan {
        final Rect content;
        final String period;
        // Text pages are retained separately so callers/tests can verify wrapping without images.
        final List<List<Line>> pages;
        final List<PhotoPage> photoPages;

        Plan(Rect content, String period, List<List<Line>> pages, List<PhotoPage> photoPages) {
            this.content = content;
            this.period = period;
            this.pages = pages;
            this.photoPages = photoPages;
        }

        int pageCount() { return pages.size() + photoPages.size(); }
        boolean isPhotoPage(int pageIndex) { return pageIndex >= pages.size(); }
        PhotoPage photoPage(int pageIndex) { return photoPages.get(pageIndex - pages.size()); }

        void drawPage(Canvas canvas, int pageIndex, Bitmap photo, CancellationSignal cancellation) {
            cancellation.throwIfCanceled();
            int save = canvas.save();
            // PrintedPdfDocument translates its canvas origin to the printable content rectangle.
            // Drawing at the page-relative left/top again would double the margins and clip text.
            int width = content.width();
            int height = content.height();
            canvas.clipRect(0, 0, width, height);

            TextPaint brand = paint(10, true, Color.BLACK);
            TextPaint small = paint(9, false, 0xff444444);
            canvas.drawText("TOXIFLOW", 0, 12, brand);
            canvas.drawText(period, width - small.measureText(period), 12, small);
            Paint rule = new Paint(Paint.ANTI_ALIAS_FLAG);
            rule.setColor(0xff999999);
            rule.setStrokeWidth(.5f);
            canvas.drawLine(0, 23, width, 23, rule);

            if (!isPhotoPage(pageIndex)) {
                for (Line line : pages.get(pageIndex)) {
                    cancellation.throwIfCanceled();
                    float y = HEADER_HEIGHT + line.top;
                    int lineSave = canvas.save();
                    // Drawing a clipped line preserves text shaping, wrapping and RTL.
                    canvas.clipRect(0, y, width, y + line.height);
                    canvas.translate(0, y - line.layout.getLineTop(line.line));
                    line.layout.draw(canvas);
                    canvas.restoreToCount(lineSave);
                }
            } else {
                PhotoPage attachment = photoPage(pageIndex);
                if (photo == null) throw new IllegalStateException("Foto lampiran tidak tersedia.");
                canvas.drawText("Lampiran foto " + attachment.number, 0, HEADER_HEIGHT + 12,
                        paint(12, true, Color.BLACK));
                int textSave = canvas.save();
                canvas.translate(0, HEADER_HEIGHT + 18);
                attachment.label.draw(canvas);
                canvas.restoreToCount(textSave);
                cancellation.throwIfCanceled();
                float boxHeight = attachment.imageBottom - attachment.imageTop;
                float scale = Math.min((float) width / photo.getWidth(), boxHeight / photo.getHeight());
                float imageWidth = photo.getWidth() * scale, imageHeight = photo.getHeight() * scale;
                float left = (width - imageWidth) / 2f;
                float top = attachment.imageTop + (boxHeight - imageHeight) / 2f;
                RectF target = new RectF(left, top, left + imageWidth, top + imageHeight);
                Paint imagePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
                canvas.drawBitmap(photo, null, target, imagePaint);
            }

            canvas.drawLine(0, height - 20, width, height - 20, rule);
            String pageNumber = "Halaman " + (pageIndex + 1) + " / " + pageCount();
            canvas.drawText("Dokumen pribadi", 0, height - 6, small);
            canvas.drawText(pageNumber, width - small.measureText(pageNumber), height - 6, small);
            canvas.restoreToCount(save);
        }
    }

    static Plan create(Context context, PrintAttributes attributes, MonthlyReport report,
                       CancellationSignal cancellation) {
        return create(context, attributes, report, true, cancellation);
    }

    static Plan create(Context context, PrintAttributes attributes, MonthlyReport report,
                       boolean includePhotos, CancellationSignal cancellation) {
        cancellation.throwIfCanceled();
        // Check before constructing PdfDocument, whose native resources need an explicit close.
        if (attributes == null || attributes.getMediaSize() == null || attributes.getMinMargins() == null)
            throw new IllegalArgumentException("Pengaturan kertas belum lengkap");
        PrintedPdfDocument document = new PrintedPdfDocument(context, attributes);
        Rect content;
        try {
            content = new Rect(document.getPageContentRect());
        } finally {
            document.close();
        }
        if (content.width() < MIN_CONTENT_WIDTH || content.height() < MIN_CONTENT_HEIGHT)
            throw new PageTooSmallException();
        float availableHeight = content.height() - HEADER_HEIGHT - FOOTER_HEIGHT;
        PrintPagination.Cursor cursor = new PrintPagination.Cursor(availableHeight, MAX_PAGES);
        List<List<Line>> pages = new ArrayList<>();
        EnumMap<MonthlyReport.Style, TextPaint> paints = paints();

        for (MonthlyReport.Paragraph paragraph : report.paragraphs(includePhotos)) {
            cancellation.throwIfCanceled();
            StaticLayout layout = textLayout(paragraph.text, paints.get(paragraph.style), content.width());
            if (layout.getLineCount() == 0) continue;
            float firstHeight = layout.getLineBottom(0) - layout.getLineTop(0);
            cursor.gap(paragraph.spaceBefore);
            cursor.ensure(firstHeight * (paragraph.keepWithNext ? 2 : 1));
            for (int i = 0; i < layout.getLineCount(); i++) {
                cancellation.throwIfCanceled();
                float height = layout.getLineBottom(i) - layout.getLineTop(i);
                PrintPagination.Position pos = cursor.place(height);
                while (pages.size() <= pos.page) pages.add(new ArrayList<>());
                pages.get(pos.page).add(new Line(layout, i, pos.top, height));
            }
        }
        List<PhotoPage> photos = new ArrayList<>();
        if (includePhotos) {
            for (Transaction t : report.photoTransactions()) {
                cancellation.throwIfCanceled();
                if (pages.size() + photos.size() >= MAX_PAGES) throw new PrintPagination.TooManyPagesException();
                String type = Transaction.INCOME.equals(t.type) ? "Pemasukan" : "Pengeluaran";
                // The complete name remains in the history; normalize whitespace in this label so
                // pasted newlines cannot push the photo off a small printable page.
                String title = t.title.replaceAll("\\s+", " ").trim();
                String caption = "Nama: " + title + "\nTanggal: " + Dates.display(t.date) + " · " + type
                        + "\nKategori: " + t.category + " · Nominal: " + Money.format(t.amount);
                StaticLayout label = textLayout(caption, paints.get(MonthlyReport.Style.BODY), content.width());
                float imageTop = HEADER_HEIGHT + 18 + label.getHeight() + 8;
                float imageBottom = content.height() - FOOTER_HEIGHT - 4;
                if (imageBottom - imageTop < MIN_PHOTO_HEIGHT) throw new PageTooSmallException();
                photos.add(new PhotoPage(t, photos.size() + 1, label, imageTop, imageBottom));
            }
        }
        return new Plan(content, report.period(), pages, photos);
    }

    /** Backward-compatible helper for a report with no photo pages. */
    static void writePdf(Context context, PrintAttributes attributes, Plan layout, List<int[]> ranges,
                         OutputStream output, CancellationSignal cancellation) throws IOException {
        writePdf(context, attributes, layout, ranges, null, output, cancellation);
    }

    /** Render only requested original pages, loading a bounded number of photos one at a time. */
    static void writePdf(Context context, PrintAttributes attributes, Plan layout, List<int[]> ranges,
                         PhotoPrintSource source, OutputStream output, CancellationSignal cancellation) throws IOException {
        cancellation.throwIfCanceled();
        int photoCount = 0;
        for (int[] range : ranges) {
            if (range == null || range.length != 2 || range[0] < 0 || range[1] < range[0]
                    || range[1] >= layout.pageCount()) throw new IllegalArgumentException("Rentang halaman tidak valid.");
            int firstPhoto = Math.max(range[0], layout.pages.size());
            if (range[1] >= firstPhoto) photoCount += range[1] - firstPhoto + 1;
        }
        if (photoCount > PhotoPrintPolicy.MAX_PHOTOS_PER_WRITE) throw new TooManyPhotoPagesException();
        if (photoCount > 0 && source == null) throw new PrintSnapshot.PhotoUnavailableException();
        PrintedPdfDocument document = new PrintedPdfDocument(context, attributes);
        // PdfDocument retains all page pictures until writeTo/close; keep their bitmaps valid and
        // cap the combined decoded pixel count rather than recycling one page too early.
        List<Bitmap> retained = new ArrayList<>();
        try {
            for (int[] range : ranges) {
                for (int pageIndex = range[0]; pageIndex <= range[1]; pageIndex++) {
                    cancellation.throwIfCanceled();
                    Bitmap photo = null;
                    if (layout.isPhotoPage(pageIndex)) {
                        byte[] bytes = source.readPhoto(layout.photoPage(pageIndex).transaction.id);
                        photo = decodePhoto(bytes, photoCount);
                        retained.add(photo);
                    }
                    PdfDocument.Page page = document.startPage(pageIndex);
                    try {
                        layout.drawPage(page.getCanvas(), pageIndex, photo, cancellation);
                    } finally {
                        // PdfDocument cannot be closed while a page is unfinished.
                        document.finishPage(page);
                    }
                }
            }
            cancellation.throwIfCanceled();
            document.writeTo(output);
            cancellation.throwIfCanceled();
        } finally {
            try { document.close(); }
            finally { for (Bitmap bitmap : retained) if (!bitmap.isRecycled()) bitmap.recycle(); }
        }
    }

    private static Bitmap decodePhoto(byte[] bytes, int photoCount) throws IOException {
        if (!PhotoPolicy.validStoredJpeg(bytes)) throw new PrintSnapshot.PhotoUnavailableException();
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        final PhotoPrintPolicy.Size target;
        try { target = PhotoPrintPolicy.sizeFor(bounds.outWidth, bounds.outHeight, photoCount); }
        catch (IllegalArgumentException e) { throw new PrintSnapshot.PhotoUnavailableException(); }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        options.inSampleSize = PhotoPrintPolicy.decodeSample(bounds.outWidth, bounds.outHeight, target);
        Bitmap decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (decoded == null) throw new PrintSnapshot.PhotoUnavailableException();
        if (decoded.getWidth() <= target.width && decoded.getHeight() <= target.height) return decoded;
        try {
            Bitmap scaled = Bitmap.createScaledBitmap(decoded, target.width, target.height, true);
            if (scaled != decoded) decoded.recycle();
            return scaled;
        } catch (RuntimeException | OutOfMemoryError e) {
            decoded.recycle();
            throw e;
        }
    }

    private static StaticLayout textLayout(String text, TextPaint paint, int width) {
        StaticLayout.Builder builder = StaticLayout.Builder.obtain(text, 0, text.length(), paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(true)
                .setLineSpacing(1.5f, 1f)
                .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE);
        if (Build.VERSION.SDK_INT >= 28) builder.setUseLineSpacingFromFallbacks(true);
        return builder.build();
    }

    private static EnumMap<MonthlyReport.Style, TextPaint> paints() {
        EnumMap<MonthlyReport.Style, TextPaint> result = new EnumMap<>(MonthlyReport.Style.class);
        result.put(MonthlyReport.Style.TITLE, paint(19, true, Color.BLACK));
        result.put(MonthlyReport.Style.SUBTITLE, paint(11, false, 0xff333333));
        result.put(MonthlyReport.Style.SECTION, paint(12, true, Color.BLACK));
        result.put(MonthlyReport.Style.STRONG, paint(10, true, Color.BLACK));
        result.put(MonthlyReport.Style.BODY, paint(10, false, Color.BLACK));
        result.put(MonthlyReport.Style.CAPTION, paint(9, false, 0xff444444));
        return result;
    }

    private static TextPaint paint(float size, boolean bold, int color) {
        TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(color);
        paint.setTextSize(size);
        paint.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        return paint;
    }

    private MonthlyPrintLayout() { }
}
