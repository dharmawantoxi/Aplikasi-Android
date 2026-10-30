package com.dharmawantoxi.rapiuang;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.media.ExifInterface;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;

/** Imports a bounded image, fixes EXIF orientation, strips metadata and creates a small JPEG draft. */
final class PhotoProcessor {
    private static final int MAX_DECODE_SIDE = 2200;
    private static final int MIN_RECOMPRESS_SIDE = 800;

    static String importUri(Context context, Uri uri) throws IOException {
        if (uri == null || !"content".equalsIgnoreCase(uri.getScheme()))
            throw new IOException("Pilih foto melalui pemilih foto perangkat.");
        InputStream input = context.getContentResolver().openInputStream(uri);
        if (input == null) throw new IOException("Foto tidak dapat dibuka. Pilih foto lain.");
        try (InputStream stream = input) { return createDraft(context, stream); }
    }

    static String importCapture(Context context, String name) throws IOException {
        File source = PhotoFiles.file(context, name, "capture");
        if (!source.isFile() || source.length() == 0)
            throw new IOException("Kamera tidak menghasilkan foto. Coba lagi atau pilih dari galeri.");
        try (InputStream stream = new FileInputStream(source)) { return createDraft(context, stream); }
    }

    // Package-visible so Android tests can exercise the same import and compression path.
    static String createDraft(Context context, InputStream input) throws IOException {
        String sourceName = PhotoFiles.newName("source");
        File source = PhotoFiles.file(context, sourceName, "source");
        Bitmap image = null;
        String draftName = null;
        boolean success = false;
        try {
            copyBounded(input, source);
            checkCanceled();
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(source.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0)
                throw new IOException("File bukan foto yang dapat dibuka. Pilih foto lain.");
            if (bounds.outWidth > PhotoPolicy.MAX_SOURCE_DIMENSION || bounds.outHeight > PhotoPolicy.MAX_SOURCE_DIMENSION
                    || (long) bounds.outWidth * bounds.outHeight > PhotoPolicy.MAX_SOURCE_PIXELS)
                throw new IOException("Dimensi foto terlalu besar. Pilih foto yang lebih kecil.");
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            options.inSampleSize = sampleFor(bounds.outWidth, bounds.outHeight, MAX_DECODE_SIDE);
            image = BitmapFactory.decodeFile(source.getAbsolutePath(), options);
            if (image == null) throw new IOException("Foto tidak dapat dibuka. Pilih foto lain.");
            checkCanceled();
            Bitmap transformed = orient(image, orientation(source));
            if (transformed != image) { image.recycle(); image = transformed; }
            transformed = fit(image, PhotoPolicy.MAX_SIDE);
            if (transformed != image) { image.recycle(); image = transformed; }
            if (image.hasAlpha()) {
                Bitmap opaque = Bitmap.createBitmap(image.getWidth(), image.getHeight(), Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(opaque);
                canvas.drawColor(Color.WHITE);
                canvas.drawBitmap(image, 0, 0, new Paint(Paint.FILTER_BITMAP_FLAG));
                image.recycle();
                image = opaque;
            }
            byte[] encoded = null;
            for (int scale = 0; scale < 4 && encoded == null; scale++) {
                for (int quality : new int[]{88, 78, 68, 58}) {
                    checkCanceled();
                    LimitedOutput buffer = new LimitedOutput(PhotoPolicy.MAX_STORED_BYTES);
                    if (image.compress(Bitmap.CompressFormat.JPEG, quality, buffer) && !buffer.overflow) {
                        byte[] candidate = buffer.bytes.toByteArray();
                        if (PhotoPolicy.validStoredJpeg(candidate)) { encoded = candidate; break; }
                    }
                }
                if (encoded == null) {
                    int side = Math.max(image.getWidth(), image.getHeight());
                    if (side <= MIN_RECOMPRESS_SIDE) break;
                    int next = Math.max(MIN_RECOMPRESS_SIDE, (int) (side * .8f));
                    transformed = fit(image, next);
                    if (transformed != image) { image.recycle(); image = transformed; }
                }
            }
            if (encoded == null) throw new IOException("Foto belum bisa diperkecil. Pilih foto lain.");
            checkCanceled();
            draftName = PhotoFiles.newName("draft");
            File draft = PhotoFiles.file(context, draftName, "draft");
            try (OutputStream out = new FileOutputStream(draft)) { out.write(encoded); }
            checkCanceled();
            success = true;
            return draftName;
        } finally {
            if (image != null && !image.isRecycled()) image.recycle();
            PhotoFiles.delete(context, sourceName, "source");
            if (!success) PhotoFiles.delete(context, draftName, "draft");
        }
    }

    static byte[] readDraft(Context context, String name) throws IOException {
        File file = PhotoFiles.file(context, name, "draft");
        if (!file.isFile() || file.length() <= 0 || file.length() > PhotoPolicy.MAX_STORED_BYTES)
            throw new IOException("Foto pilihan tidak tersedia. Pilih kembali sebelum menyimpan.");
        try (InputStream in = new FileInputStream(file); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count, total = 0;
            while ((count = in.read(buffer)) != -1) {
                total += count;
                if (total > PhotoPolicy.MAX_STORED_BYTES) throw new IOException("Foto pilihan terlalu besar.");
                bytes.write(buffer, 0, count);
            }
            byte[] result = bytes.toByteArray();
            if (!PhotoPolicy.validStoredJpeg(result)) throw new IOException("Foto pilihan rusak. Pilih kembali.");
            return result;
        }
    }

    static Bitmap preview(byte[] data, int maxSide) {
        if (!PhotoPolicy.validStoredJpeg(data) || maxSide <= 0) return null;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0
                || bounds.outWidth > PhotoPolicy.MAX_SIDE || bounds.outHeight > PhotoPolicy.MAX_SIDE) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleFor(bounds.outWidth, bounds.outHeight, maxSide);
        return BitmapFactory.decodeByteArray(data, 0, data.length, options);
    }

    private static void copyBounded(InputStream input, File destination) throws IOException {
        try (OutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[32 * 1024];
            long total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                checkCanceled();
                if (count == 0) {
                    // A non-conforming provider must not make us spin forever without progress.
                    int one = input.read();
                    if (one == -1) break;
                    buffer[0] = (byte) one;
                    count = 1;
                }
                total += count;
                if (total > PhotoPolicy.MAX_SOURCE_BYTES)
                    throw new IOException("Ukuran foto lebih dari 20 MiB. Pilih foto yang lebih kecil.");
                output.write(buffer, 0, count);
            }
            if (total == 0) throw new IOException("Foto kosong. Pilih foto lain.");
        }
    }

    private static int sampleFor(int width, int height, int limit) {
        int sample = 1;
        while (((long) Math.max(width, height) + sample - 1) / sample > limit && sample < (1 << 28)) sample *= 2;
        return sample;
    }

    private static Bitmap fit(Bitmap original, int maxSide) {
        int side = Math.max(original.getWidth(), original.getHeight());
        if (side <= maxSide) return original;
        float factor = (float) maxSide / side;
        int width = Math.max(1, Math.round(original.getWidth() * factor));
        int height = Math.max(1, Math.round(original.getHeight() * factor));
        return Bitmap.createScaledBitmap(original, width, height, true);
    }

    private static int orientation(File source) {
        try {
            return new ExifInterface(source.getAbsolutePath()).getAttributeInt(ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL);
        } catch (IOException | RuntimeException ignored) {
            return ExifInterface.ORIENTATION_NORMAL;
        }
    }

    private static Bitmap orient(Bitmap original, int orientation) {
        Matrix m = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: m.setScale(-1, 1); break;
            case ExifInterface.ORIENTATION_ROTATE_180: m.setRotate(180); break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL: m.setScale(1, -1); break;
            case ExifInterface.ORIENTATION_TRANSPOSE: m.setRotate(90); m.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_ROTATE_90: m.setRotate(90); break;
            case ExifInterface.ORIENTATION_TRANSVERSE: m.setRotate(-90); m.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_ROTATE_270: m.setRotate(-90); break;
            default: return original;
        }
        return Bitmap.createBitmap(original, 0, 0, original.getWidth(), original.getHeight(), m, true);
    }

    private static void checkCanceled() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Pemrosesan foto dibatalkan.");
    }

    /** Bounds memory while trying JPEG qualities; discarded bytes are never written to disk. */
    private static final class LimitedOutput extends OutputStream {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final int limit;
        boolean overflow;
        LimitedOutput(int limit) { this.limit = limit; }
        @Override public void write(int value) {
            if (overflow || bytes.size() >= limit) { overflow = true; return; }
            bytes.write(value);
        }
        @Override public void write(byte[] value, int offset, int length) {
            if (overflow || length > limit - bytes.size()) { overflow = true; return; }
            bytes.write(value, offset, length);
        }
    }

    private PhotoProcessor() { }
}
