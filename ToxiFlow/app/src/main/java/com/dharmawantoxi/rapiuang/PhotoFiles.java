package com.dharmawantoxi.rapiuang;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

/** Private, disposable drafts and camera outputs; saved photos live atomically in SQLite. */
final class PhotoFiles {
    private static final long STALE_AFTER_MS = 7L * 24 * 60 * 60 * 1000;
    private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    static String newName(String kind) {
        if (!"draft".equals(kind) && !"capture".equals(kind) && !"source".equals(kind))
            throw new IllegalArgumentException("Jenis berkas tidak valid");
        return kind + "-" + UUID.randomUUID() + ("source".equals(kind) ? ".tmp" : ".jpg");
    }

    static boolean validName(String name, String kind) {
        if (name == null || !("draft".equals(kind) || "capture".equals(kind) || "source".equals(kind))) return false;
        String suffix = "source".equals(kind) ? "\\.tmp" : "\\.jpg";
        return name.matches(kind + "-" + UUID_PATTERN + suffix);
    }

    static File file(Context context, String name, String kind) throws IOException {
        if (!validName(name, kind)) throw new IOException("Nama berkas foto tidak valid");
        File dir = directory(context);
        File result = new File(dir, name).getCanonicalFile();
        if (!dir.equals(result.getParentFile())) throw new IOException("Lokasi foto tidak valid");
        return result;
    }

    static String createCapture(Context context) throws IOException {
        String name = newName("capture");
        if (!file(context, name, "capture").createNewFile()) throw new IOException("Berkas kamera tidak dapat dibuat");
        return name;
    }

    static void delete(Context context, String name, String kind) {
        if (name == null) return;
        try { file(context, name, kind).delete(); } catch (IOException | SecurityException ignored) { }
    }

    static void pruneOld(Context context) {
        try {
            File[] files = directory(context).listFiles();
            if (files == null) return;
            long cutoff = System.currentTimeMillis() - STALE_AFTER_MS;
            for (File f : files) {
                String name = f.getName();
                if ((validName(name, "draft") || validName(name, "capture") || validName(name, "source"))
                        && f.lastModified() > 0 && f.lastModified() < cutoff) f.delete();
            }
        } catch (IOException | SecurityException ignored) { }
    }

    private static File directory(Context context) throws IOException {
        File dir = new File(context.getCacheDir(), "toxiflow-photo-drafts");
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory())
            throw new IOException("Ruang sementara foto tidak tersedia");
        return dir.getCanonicalFile();
    }

    private PhotoFiles() { }
}
