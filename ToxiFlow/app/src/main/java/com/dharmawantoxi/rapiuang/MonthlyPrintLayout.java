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
    private static final float HEADER_HEIGHT = 48;
    private static final float FOOTER_HEIGHT = 30;
    private static final float SECTION_ROW_HEIGHT = 28;
    private static final float METRIC_ROW_HEIGHT = 32;
    private static final float CATEGORY_ROW_HEIGHT = 24;
    private static final float TRANSACTION_ROW_HEIGHT = 24;
    private static final float MIN_PHOTO_HEIGHT = 60;
    private static final int MIN_CONTENT_WIDTH = 220;
    private static final int MIN_CONTENT_HEIGHT = 190;
    private static final int NAVY = 0xff17324d;
    private static final int TEAL = 0xff15806f;
    private static final int INK = 0xff263746;
    private static final int MUTED = 0xff687985;
    private static final int PALE_BLUE = 0xffeef3f7;
    private static final int PALE_GREEN = 0xffedf6f2;
    private static final int PALE_RED = 0xfff8f0f0;
    private static final int BORDER = 0xffdce5e9;

    static final class PageTooSmallException extends IllegalArgumentException { }
    static final class TooManyPhotoPagesException extends IOException { }

    static final class Line {
        final StaticLayout layout;
        final int line;
        final float top;
        final float height;
        final MonthlyReport.Style style;
        final boolean firstInParagraph;
        final boolean alternateRow;

        Line(StaticLayout layout, int line, float top, float height, MonthlyReport.Style style,
             boolean firstInParagraph, boolean alternateRow) {
            this.layout = layout;
            this.line = line;
            this.top = top;
            this.height = height;
            this.style = style;
            this.firstInParagraph = firstInParagraph;
            this.alternateRow = alternateRow;
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
        final AppLanguage language;
        // Text pages are retained separately so callers/tests can verify wrapping without images.
        final List<List<Line>> pages;
        final List<PhotoPage> photoPages;

        Plan(Rect content, String period, AppLanguage language, List<List<Line>> pages, List<PhotoPage> photoPages) {
            this.content = content;
            this.period = period;
            this.language = language;
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

            Paint rule = new Paint(Paint.ANTI_ALIAS_FLAG);
            rule.setColor(BORDER);
            rule.setStrokeWidth(.8f);
            Paint block = new Paint(Paint.ANTI_ALIAS_FLAG);
            block.setColor(NAVY);
            canvas.drawRoundRect(new RectF(0, 3, 30, 33), 7, 7, block);
            canvas.drawText("TF", 6, 23, paint(12, true, Color.WHITE));
            canvas.drawText("TOXIFLOW", 39, 15, paint(10, true, NAVY));
            canvas.drawText(language.text("LAPORAN KEUANGAN PRIBADI", "PERSONAL FINANCE REPORT"),
                    39, 29, paint(6.5f, true, MUTED));
            TextPaint periodPaint = paint(10, true, INK);
            canvas.drawText(period, width - periodPaint.measureText(period), 22, periodPaint);
            canvas.drawLine(0, 41, width, 41, rule);
            block.setColor(TEAL);
            canvas.drawRoundRect(new RectF(0, 40, width, 42), 1, 1, block);

            if (!isPhotoPage(pageIndex)) {
                for (Line line : pages.get(pageIndex)) {
                    cancellation.throwIfCanceled();
                    float y = HEADER_HEIGHT + line.top;
                    if (line.style == MonthlyReport.Style.SECTION && line.firstInParagraph) {
                        drawSection(canvas, line, y, width);
                    } else if (isMetric(line.style) && line.layout.getLineCount() == 1) {
                        drawMetric(canvas, line, y, width);
                    } else if (line.style == MonthlyReport.Style.CATEGORY && line.layout.getLineCount() == 1) {
                        drawCategory(canvas, line, y, width);
                    } else if (line.style == MonthlyReport.Style.TRANSACTION_HEADER) {
                        drawTransactionHeader(canvas, line, y, width);
                    } else {
                        drawTextLine(canvas, line, y, width);
                    }
                }
            } else {
                PhotoPage attachment = photoPage(pageIndex);
                if (photo == null) throw new IllegalStateException("Foto lampiran tidak tersedia.");
                canvas.drawText(language.text("Lampiran foto ", "Photo appendix ") + attachment.number, 0, HEADER_HEIGHT + 12,
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

            canvas.drawLine(0, height - 23, width, height - 23, rule);
            TextPaint footer = paint(7.5f, false, MUTED);
            String pageNumber = language.text("Halaman ", "Page ") + (pageIndex + 1) + " / " + pageCount();
            canvas.drawText(language.text("ToxiFlow · Dokumen pribadi", "ToxiFlow · Private report"), 0, height - 8, footer);
            canvas.drawText(pageNumber, width - footer.measureText(pageNumber), height - 8, footer);
            canvas.restoreToCount(save);
        }
    }

    private static float rowHeight(MonthlyReport.Style style, float textHeight) {
        switch (style) {
            case SECTION: return Math.max(textHeight, SECTION_ROW_HEIGHT);
            case METRIC_INCOME:
            case METRIC_EXPENSE:
            case METRIC_BALANCE: return Math.max(textHeight, METRIC_ROW_HEIGHT);
            case CATEGORY: return Math.max(textHeight, CATEGORY_ROW_HEIGHT);
            case TRANSACTION_HEADER: return Math.max(textHeight, TRANSACTION_ROW_HEIGHT);
            default: return textHeight;
        }
    }

    private static boolean isMetric(MonthlyReport.Style style) {
        return style == MonthlyReport.Style.METRIC_INCOME || style == MonthlyReport.Style.METRIC_EXPENSE
                || style == MonthlyReport.Style.METRIC_BALANCE;
    }

    private static void drawTextLine(Canvas canvas, Line line, float y, int width) {
        float top = y;
        if (line.style == MonthlyReport.Style.TITLE) top += 1;
        int save = canvas.save();
        canvas.clipRect(0, top, width, y + line.height);
        canvas.translate(0, top - line.layout.getLineTop(line.line));
        line.layout.draw(canvas);
        canvas.restoreToCount(save);
    }

    private static void drawSection(Canvas canvas, Line line, float y, int width) {
        Paint background = new Paint(Paint.ANTI_ALIAS_FLAG);
        background.setColor(PALE_BLUE);
        canvas.drawRoundRect(new RectF(0, y + 2, width, y + line.height - 1), 5, 5, background);
        background.setColor(TEAL);
        canvas.drawRoundRect(new RectF(0, y + 5, 3, y + line.height - 4), 1.5f, 1.5f, background);
        int save = canvas.save();
        canvas.clipRect(8, y, width - 4, y + line.height);
        float textHeight = line.layout.getLineBottom(line.line) - line.layout.getLineTop(line.line);
        canvas.translate(8, y + (line.height - textHeight) / 2f - line.layout.getLineTop(line.line));
        line.layout.draw(canvas);
        canvas.restoreToCount(save);
    }

    private static void drawMetric(Canvas canvas, Line line, float y, int width) {
        String text = line.layout.getText().toString();
        int split = text.indexOf(": ");
        if (split < 0) { drawTextLine(canvas, line, y, width); return; }
        boolean balance = line.style == MonthlyReport.Style.METRIC_BALANCE;
        int bg = line.style == MonthlyReport.Style.METRIC_INCOME ? PALE_GREEN
                : line.style == MonthlyReport.Style.METRIC_EXPENSE ? PALE_RED : NAVY;
        int fg = balance ? Color.WHITE : INK;
        Paint background = new Paint(Paint.ANTI_ALIAS_FLAG);
        background.setColor(bg);
        RectF rect = new RectF(0, y + 2, width, y + line.height - 1);
        canvas.drawRoundRect(rect, 6, 6, background);
        if (!balance) {
            background.setStyle(Paint.Style.STROKE);
            background.setStrokeWidth(.7f);
            background.setColor(BORDER);
            canvas.drawRoundRect(rect, 6, 6, background);
        }
        TextPaint label = paint(9.5f, true, balance ? 0xffdce9ef : MUTED);
        TextPaint amount = paint(balance ? 13 : 11, true, fg);
        String left = text.substring(0, split);
        String right = text.substring(split + 2);
        float baseline = centeredBaseline(y + 2, line.height - 3, amount);
        canvas.drawText(left, 12, baseline, label);
        float valueWidth = amount.measureText(right);
        if (valueWidth + label.measureText(left) + 30 > width) amount.setTextSize(9.5f);
        canvas.drawText(right, width - 12 - amount.measureText(right), baseline, amount);
    }

    private static void drawCategory(Canvas canvas, Line line, float y, int width) {
        String text = line.layout.getText().toString();
        int split = text.indexOf(": ");
        if (split < 0) { drawTextLine(canvas, line, y, width); return; }
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        if (line.alternateRow) {
            fill.setColor(0xfff5f8fa);
            canvas.drawRect(0, y, width, y + line.height, fill);
        }
        TextPaint label = paint(9.5f, false, INK);
        TextPaint value = paint(9.5f, true, INK);
        float baseline = centeredBaseline(y, line.height, value);
        canvas.drawText(text.substring(0, split), 10, baseline, label);
        canvas.drawText(text.substring(split + 2), width - 10 - value.measureText(text.substring(split + 2)), baseline, value);
        fill.setColor(BORDER);
        fill.setStrokeWidth(.45f);
        canvas.drawLine(8, y + line.height, width - 8, y + line.height, fill);
    }

    private static void drawTransactionHeader(Canvas canvas, Line line, float y, int width) {
        Paint background = new Paint(Paint.ANTI_ALIAS_FLAG);
        background.setColor(PALE_BLUE);
        canvas.drawRoundRect(new RectF(0, y + 1, width, y + line.height), 4, 4, background);
        background.setColor(TEAL);
        canvas.drawRoundRect(new RectF(0, y + 4, 3, y + line.height - 3), 1.5f, 1.5f, background);
        int save = canvas.save();
        canvas.clipRect(10, y, width - 5, y + line.height);
        float textHeight = line.layout.getLineBottom(line.line) - line.layout.getLineTop(line.line);
        canvas.translate(10, y + (line.height - textHeight) / 2f - line.layout.getLineTop(line.line));
        line.layout.draw(canvas);
        canvas.restoreToCount(save);
    }

    private static float centeredBaseline(float top, float height, TextPaint paint) {
        Paint.FontMetrics metrics = paint.getFontMetrics();
        return top + (height - metrics.descent - metrics.ascent) / 2f;
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
        int categoryRow = 0;

        for (MonthlyReport.Paragraph paragraph : report.paragraphs(includePhotos)) {
            cancellation.throwIfCanceled();
            StaticLayout layout = textLayout(paragraph.text, paints.get(paragraph.style), content.width());
            if (layout.getLineCount() == 0) continue;
            float firstHeight = rowHeight(paragraph.style,
                    layout.getLineBottom(0) - layout.getLineTop(0));
            cursor.gap(paragraph.spaceBefore);
            cursor.ensure(firstHeight * (paragraph.keepWithNext ? 2 : 1));
            for (int i = 0; i < layout.getLineCount(); i++) {
                cancellation.throwIfCanceled();
                float textHeight = layout.getLineBottom(i) - layout.getLineTop(i);
                float height = rowHeight(paragraph.style, textHeight);
                PrintPagination.Position pos = cursor.place(height);
                while (pages.size() <= pos.page) pages.add(new ArrayList<>());
                boolean alternate = paragraph.style == MonthlyReport.Style.CATEGORY && (categoryRow++ % 2 == 0);
                pages.get(pos.page).add(new Line(layout, i, pos.top, height, paragraph.style, i == 0, alternate));
            }
        }
        List<PhotoPage> photos = new ArrayList<>();
        if (includePhotos) {
            for (Transaction t : report.photoTransactions()) {
                cancellation.throwIfCanceled();
                if (pages.size() + photos.size() >= MAX_PAGES) throw new PrintPagination.TooManyPagesException();
                AppLanguage language = report.language;
                String type = Transaction.INCOME.equals(t.type)
                        ? language.text("Pemasukan", "Income") : language.text("Pengeluaran", "Expense");
                // The complete name remains in the history; normalize whitespace in this label so
                // pasted newlines cannot push the photo off a small printable page.
                String title = t.title.replaceAll("\\s+", " ").trim();
                String caption = language.text("Nama: ", "Name: ") + title
                        + language.text("\nTanggal: ", "\nDate: ") + Dates.display(t.date, language) + " · " + type
                        + language.text("\nKategori: ", "\nCategory: ") + Categories.label(t.category, language)
                        + language.text(" · Nominal: ", " · Amount: ") + Money.format(t.amount);
                StaticLayout label = textLayout(caption, paints.get(MonthlyReport.Style.BODY), content.width());
                float imageTop = HEADER_HEIGHT + 18 + label.getHeight() + 8;
                float imageBottom = content.height() - FOOTER_HEIGHT - 4;
                if (imageBottom - imageTop < MIN_PHOTO_HEIGHT) throw new PageTooSmallException();
                photos.add(new PhotoPage(t, photos.size() + 1, label, imageTop, imageBottom));
            }
        }
        return new Plan(content, report.period(), report.language, pages, photos);
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
        result.put(MonthlyReport.Style.TITLE, paint(22, true, NAVY));
        result.put(MonthlyReport.Style.SUBTITLE, paint(11, false, MUTED));
        result.put(MonthlyReport.Style.SECTION, paint(11, true, NAVY));
        result.put(MonthlyReport.Style.METRIC_INCOME, paint(10, true, INK));
        result.put(MonthlyReport.Style.METRIC_EXPENSE, paint(10, true, INK));
        result.put(MonthlyReport.Style.METRIC_BALANCE, paint(12, true, Color.WHITE));
        result.put(MonthlyReport.Style.META, paint(9, false, MUTED));
        result.put(MonthlyReport.Style.CATEGORY, paint(9.5f, false, INK));
        result.put(MonthlyReport.Style.TRANSACTION_HEADER, paint(10, true, NAVY));
        result.put(MonthlyReport.Style.STRONG, paint(10, true, NAVY));
        result.put(MonthlyReport.Style.BODY, paint(9.5f, false, INK));
        result.put(MonthlyReport.Style.CAPTION, paint(8.5f, false, MUTED));
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
