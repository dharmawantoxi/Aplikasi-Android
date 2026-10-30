package com.dharmawantoxi.rapiuang;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.os.CancellationSignal;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** A consistent, private print snapshot. Each image is staged on disk, never all in memory. */
final class PrintSnapshot implements PhotoPrintSource, AutoCloseable {
    private static final long STALE_AFTER_MS = 7L * 24 * 60 * 60 * 1000;
    private static final String JOB_NAME = "job-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    static final class PhotoUnavailableException extends IOException {
        PhotoUnavailableException() { super("Foto transaksi tidak dapat dibuka."); }
    }

    final MonthlyReport report;
    final boolean includePhotos;
    final File directory; // null for a report without photo pages.
    private final Map<Long, File> photos;
    private boolean closed;

    private PrintSnapshot(MonthlyReport report, boolean includePhotos, File directory, Map<Long, File> photos) {
        this.report = report;
        this.includePhotos = includePhotos;
        this.directory = directory;
        this.photos = photos;
    }

    /** Capture text and photos under one SQLite transaction after the user confirms printing. */
    static PrintSnapshot create(Context context, FinanceStore reader, int year, int month,
                                boolean includePhotos, CancellationSignal cancellation) throws IOException {
        return create(context, reader, year, month, includePhotos, AppLanguage.INDONESIAN, cancellation);
    }

    static PrintSnapshot create(Context context, FinanceStore reader, int year, int month,
                                boolean includePhotos, AppLanguage language, CancellationSignal cancellation) throws IOException {
        File job = null;
        boolean success = false;
        try {
            cancellation.throwIfCanceled();
            SQLiteDatabase db = reader.getWritableDatabase();
            db.beginTransactionNonExclusive();
            MonthlyReport report;
            Map<Long, File> files = new HashMap<>();
            try {
                cancellation.throwIfCanceled();
                report = MonthlyReport.forMonth(year, month, reader.all(), language);
                List<Transaction> attached = report.photoTransactions();
                if (includePhotos && attached.size() >= MonthlyPrintLayout.MAX_PAGES)
                    throw new PrintPagination.TooManyPagesException(); // At least one page is needed for text.
                if (includePhotos && !attached.isEmpty()) {
                    job = newJobDirectory(context);
                    for (Transaction t : attached) {
                        cancellation.throwIfCanceled();
                        if (t.id <= 0) throw new PhotoUnavailableException();
                        byte[] bytes = reader.getPhoto(t.id);
                        if (!PhotoPolicy.validStoredJpeg(bytes)) throw new PhotoUnavailableException();
                        // Validate actual decoding too; a corrupt BLOB must not disappear silently from the PDF.
                        Bitmap preview = PhotoProcessor.preview(bytes, 64);
                        if (preview == null) throw new PhotoUnavailableException();
                        preview.recycle();
                        cancellation.throwIfCanceled();
                        File image = new File(job, "photo-" + t.id + ".jpg");
                        try (FileOutputStream out = new FileOutputStream(image)) { out.write(bytes); }
                        files.put(t.id, image);
                    }
                }
                cancellation.throwIfCanceled();
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            reader.close();
            success = true;
            return new PrintSnapshot(report, includePhotos, job, files);
        } finally {
            if (!success) {
                try { reader.close(); } finally { deleteJob(job); }
            }
        }
    }

    @Override public byte[] readPhoto(long transactionId) throws IOException {
        if (closed) throw new IOException("Salinan laporan sudah ditutup.");
        File file = photos.get(transactionId);
        if (file == null || !file.isFile() || file.length() <= 0 || file.length() > PhotoPolicy.MAX_STORED_BYTES)
            throw new PhotoUnavailableException();
        try (InputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count, total = 0;
            while ((count = in.read(buffer)) != -1) {
                total += count;
                if (total > PhotoPolicy.MAX_STORED_BYTES) throw new PhotoUnavailableException();
                out.write(buffer, 0, count);
            }
            byte[] bytes = out.toByteArray();
            if (!PhotoPolicy.validStoredJpeg(bytes)) throw new PhotoUnavailableException();
            if (directory != null) directory.setLastModified(System.currentTimeMillis());
            return bytes;
        }
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        deleteJob(directory);
        photos.clear();
    }

    /** Remove old interrupted print snapshots; never traverse outside the private cache directory. */
    static void pruneOld(Context context) {
        try {
            File root = new File(context.getCacheDir(), "toxiflow-print-snapshots").getCanonicalFile();
            File[] jobs = root.listFiles();
            if (jobs == null) return;
            long cutoff = System.currentTimeMillis() - STALE_AFTER_MS;
            for (File job : jobs) {
                if (!job.getName().matches(JOB_NAME) || !root.equals(job.getCanonicalFile().getParentFile())) continue;
                if (job.lastModified() > 0 && job.lastModified() < cutoff) deleteJob(job);
            }
        } catch (IOException | SecurityException ignored) { }
    }

    private static File newJobDirectory(Context context) throws IOException {
        File root = new File(context.getCacheDir(), "toxiflow-print-snapshots").getCanonicalFile();
        if (!root.isDirectory() && !root.mkdirs() && !root.isDirectory())
            throw new IOException("Cache laporan tidak tersedia.");
        File job = new File(root, "job-" + UUID.randomUUID()).getCanonicalFile();
        if (!root.equals(job.getParentFile()) || !job.mkdir()) throw new IOException("Cache laporan tidak tersedia.");
        return job;
    }

    private static void deleteJob(File job) {
        if (job == null || !job.getName().matches(JOB_NAME)) return;
        try {
            File[] files = job.listFiles();
            if (files != null) {
                for (File file : files)
                    if (file.getName().matches("photo-[1-9][0-9]*\\.jpg")) file.delete();
            }
            job.delete();
        } catch (SecurityException ignored) { }
    }
}
