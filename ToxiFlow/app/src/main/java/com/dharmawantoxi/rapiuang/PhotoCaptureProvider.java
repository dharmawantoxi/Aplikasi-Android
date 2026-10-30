package com.dharmawantoxi.rapiuang;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;

/** Exposes only an already-created, random temporary camera file to the chosen camera app. */
public final class PhotoCaptureProvider extends ContentProvider {
    static Uri uri(Context context, String captureName) {
        if (!PhotoFiles.validName(captureName, "capture")) throw new IllegalArgumentException("Nama foto tidak valid");
        return new Uri.Builder().scheme("content").authority(context.getPackageName() + ".photo_capture")
                .appendPath(captureName).build();
    }

    @Override public boolean onCreate() { return true; }

    private File resolve(Uri uri) throws FileNotFoundException {
        Context context = getContext();
        List<String> paths = uri.getPathSegments();
        if (context == null || !"content".equals(uri.getScheme())
                || !(context.getPackageName() + ".photo_capture").equals(uri.getAuthority())
                || paths.size() != 1 || uri.getQuery() != null || uri.getFragment() != null
                || !PhotoFiles.validName(paths.get(0), "capture"))
            throw new FileNotFoundException("Foto kamera tidak tersedia");
        try {
            File file = PhotoFiles.file(context, paths.get(0), "capture");
            if (!file.isFile()) throw new FileNotFoundException("Foto kamera tidak tersedia");
            return file;
        } catch (IOException e) {
            throw new FileNotFoundException("Foto kamera tidak tersedia");
        }
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode) && !"w".equals(mode) && !"wt".equals(mode)
                && !"rw".equals(mode) && !"rwt".equals(mode))
            throw new FileNotFoundException("Mode berkas tidak didukung");
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.parseMode(mode));
    }

    @Override public String getType(Uri uri) {
        try { resolve(uri); return "image/jpeg"; }
        catch (FileNotFoundException e) { return null; }
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        final File file;
        try { file = resolve(uri); }
        catch (FileNotFoundException e) { return null; }
        String[] columns = projection == null
                ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
        MatrixCursor cursor = new MatrixCursor(columns, 1);
        Object[] values = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) values[i] = "foto-transaksi.jpg";
            else if (OpenableColumns.SIZE.equals(columns[i])) values[i] = file.length();
        }
        cursor.addRow(values);
        return cursor;
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }
}
