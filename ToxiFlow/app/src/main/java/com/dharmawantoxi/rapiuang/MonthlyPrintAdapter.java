package com.dharmawantoxi.rapiuang;

import android.content.Context;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.OperationCanceledException;
import android.os.ParcelFileDescriptor;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintDocumentInfo;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/** Android print adapter. The snapshot, layout and PDF generation remain local to the device. */
final class MonthlyPrintAdapter extends PrintDocumentAdapter {
    private final Context context;
    private final MonthlyReport requestedReport;
    private final boolean includePhotos;
    private final FinanceStore snapshotReader;
    private PrintSnapshot snapshot;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    // Accessed only on the serial worker, after a successful layout.
    private PrintAttributes attributes;
    private MonthlyPrintLayout.Plan layout;

    MonthlyPrintAdapter(Context context, MonthlyReport report, boolean includePhotos) {
        this.context = context.getApplicationContext();
        this.requestedReport = report;
        this.includePhotos = includePhotos;
        // Freeze the database choice before the worker runs, including the isolated UI-test database.
        this.snapshotReader = new FinanceStore(this.context);
    }

    @Override public void onLayout(PrintAttributes oldAttributes, PrintAttributes newAttributes,
                                   CancellationSignal cancellation, LayoutResultCallback callback,
                                   Bundle extras) {
        if (cancellation.isCanceled()) {
            callback.onLayoutCancelled();
            return;
        }
        if (newAttributes == null) {
            callback.onLayoutFailed(tr("Pengaturan kertas tidak tersedia.", "Paper settings are unavailable."));
            return;
        }
        try {
            worker.execute(() -> {
                try {
                    // A failed or cancelled relayout must not leave the previous layout writable.
                    attributes = null;
                    layout = null;
                    if (snapshot == null) snapshot = PrintSnapshot.create(context, snapshotReader,
                            requestedReport.year, requestedReport.month, includePhotos, requestedReport.language, cancellation);
                    MonthlyPrintLayout.Plan result = MonthlyPrintLayout.create(context, newAttributes,
                            snapshot.report, includePhotos, cancellation);
                    cancellation.throwIfCanceled();
                    attributes = newAttributes;
                    layout = result;
                    PrintDocumentInfo info = new PrintDocumentInfo.Builder(snapshot.report.fileName())
                            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                            .setPageCount(result.pageCount()).build();
                    boolean changed = !newAttributes.equals(oldAttributes);
                    main.post(() -> {
                        if (cancellation.isCanceled()) callback.onLayoutCancelled();
                        else callback.onLayoutFinished(info, changed);
                    });
                } catch (OperationCanceledException e) {
                    main.post(callback::onLayoutCancelled);
                } catch (IOException | RuntimeException | OutOfMemoryError e) {
                    String message = layoutError(e);
                    main.post(() -> {
                        if (cancellation.isCanceled()) callback.onLayoutCancelled();
                        else callback.onLayoutFailed(message);
                    });
                }
            });
        } catch (RejectedExecutionException e) {
            callback.onLayoutFailed(tr("Layanan cetak sudah ditutup. Coba lagi.", "The print service is closed. Try again."));
        }
    }

    @Override public void onWrite(PageRange[] requested, ParcelFileDescriptor destination,
                                  CancellationSignal cancellation, WriteResultCallback callback) {
        List<int[]> ranges = new ArrayList<>();
        if (requested != null) {
            for (PageRange range : requested)
                if (range != null) ranges.add(new int[]{range.getStart(), range.getEnd()});
        }
        try {
            worker.execute(() -> write(ranges, destination, cancellation, callback));
        } catch (RejectedExecutionException e) {
            closeQuietly(destination);
            callback.onWriteFailed(tr("Layanan cetak sudah ditutup. Coba lagi.", "The print service is closed. Try again."));
        }
    }

    private void write(List<int[]> requested, ParcelFileDescriptor destination,
                       CancellationSignal cancellation, WriteResultCallback callback) {
        PageRange[] written;
        // AutoCloseOutputStream also closes the descriptor on failure or cancellation.
        try (OutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(destination)) {
            cancellation.throwIfCanceled();
            if (layout == null || attributes == null)
                throw new IllegalStateException(tr("Tata letak belum tersedia", "Layout is not available"));
            List<int[]> ranges = PrintPagination.normalizeRanges(layout.pageCount(), requested);
            if (ranges.isEmpty()) throw new IllegalArgumentException(tr("Tidak ada halaman yang dipilih", "No pages selected"));
            MonthlyPrintLayout.writePdf(context, attributes, layout, ranges, snapshot,
                    new CancellableOutputStream(output, cancellation), cancellation);
            written = new PageRange[ranges.size()];
            for (int i = 0; i < ranges.size(); i++)
                written[i] = new PageRange(ranges.get(i)[0], ranges.get(i)[1]);
        } catch (OperationCanceledException e) {
            main.post(callback::onWriteCancelled);
            return;
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            String message = writeError(e);
            main.post(() -> {
                if (cancellation.isCanceled()) callback.onWriteCancelled();
                else callback.onWriteFailed(message);
            });
            return;
        }
        // Notify the system only after the destination has been completely closed.
        main.post(() -> {
            if (cancellation.isCanceled()) callback.onWriteCancelled();
            else callback.onWriteFinished(written);
        });
    }

    @Override public void onFinish() {
        try {
            // Run cleanup after any in-flight work; never delete a snapshot while it is being read.
            worker.execute(() -> {
                if (snapshot != null) snapshot.close();
                snapshotReader.close();
            });
        } catch (RejectedExecutionException ignored) { }
        worker.shutdown();
    }

    private String tr(String indonesian, String english) {
        return requestedReport.language.text(indonesian, english);
    }

    private String layoutError(Throwable e) {
        if (e instanceof PrintPagination.TooManyPagesException)
            return tr("Laporan lebih dari ", "The report exceeds ") + MonthlyPrintLayout.MAX_PAGES
                    + tr(" halaman. Coba tanpa foto atau gunakan CSV untuk teks transaksi.", " pages. Try without photos or use CSV for transaction text.");
        if (e instanceof PrintSnapshot.PhotoUnavailableException)
            return tr("Ada foto transaksi yang tidak dapat dibuka. Periksa fotonya atau cetak tanpa foto.", "A transaction photo could not be opened. Check the photo or print without photos.");
        if (e instanceof MonthlyPrintLayout.PageTooSmallException || e instanceof IllegalArgumentException)
            return tr("Ukuran kertas terlalu kecil atau tidak tersedia. Pilih A4 atau Letter dan coba lagi.", "The paper size is too small or unavailable. Choose A4 or Letter and try again.");
        if (e instanceof OutOfMemoryError)
            return tr("Memori perangkat tidak cukup. Coba cetak tanpa foto atau tutup aplikasi lain.", "Not enough device memory. Try printing without photos or close other apps.");
        if (e instanceof IOException)
            return tr("Salinan laporan belum bisa disiapkan. Periksa ruang penyimpanan dan coba lagi.", "The report snapshot could not be prepared. Check storage and try again.");
        return tr("Laporan belum bisa disiapkan. Coba pilih ukuran kertas lain atau coba lagi.", "The report could not be prepared. Choose another paper size or try again.");
    }

    private String writeError(Throwable e) {
        if (e instanceof MonthlyPrintLayout.TooManyPhotoPagesException)
            return tr("Maksimal ", "Maximum ") + PhotoPrintPolicy.MAX_PHOTOS_PER_WRITE
                    + tr(" foto per proses. Pilih rentang halaman lebih kecil atau cetak tanpa foto.", " photos per job. Choose a smaller page range or print without photos.");
        if (e instanceof PrintSnapshot.PhotoUnavailableException)
            return tr("Foto lampiran tidak tersedia. Mulai cetak lagi atau pilih tanpa foto.", "An attachment photo is unavailable. Start printing again or choose no photos.");
        if (e instanceof OutOfMemoryError)
            return tr("Memori perangkat tidak cukup. Pilih lebih sedikit halaman atau cetak tanpa foto.", "Not enough device memory. Select fewer pages or print without photos.");
        return tr("Laporan belum bisa dibuat. Periksa penyimpanan dan coba lagi.", "The report could not be created. Check storage and try again.");
    }

    private static void closeQuietly(ParcelFileDescriptor descriptor) {
        try { descriptor.close(); } catch (IOException ignored) { }
    }

    private static final class CancellableOutputStream extends FilterOutputStream {
        private final CancellationSignal cancellation;
        CancellableOutputStream(OutputStream out, CancellationSignal cancellation) {
            super(out);
            this.cancellation = cancellation;
        }
        @Override public void write(int value) throws IOException {
            cancellation.throwIfCanceled();
            out.write(value);
        }
        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            cancellation.throwIfCanceled();
            out.write(bytes, offset, length);
        }
    }
}
