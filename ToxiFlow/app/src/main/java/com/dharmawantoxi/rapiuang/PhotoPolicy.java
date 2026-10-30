package com.dharmawantoxi.rapiuang;

/** Limits for one normalized photo stored in the app's private database. */
final class PhotoPolicy {
    static final long MAX_SOURCE_BYTES = 20L * 1024 * 1024;
    // Safely below the Android SQLite CursorWindow size, since photos are read one at a time.
    static final int MAX_STORED_BYTES = 512 * 1024;
    static final int MAX_SIDE = 1600;
    static final long MAX_SOURCE_PIXELS = 100_000_000L;
    static final int MAX_SOURCE_DIMENSION = 30_000;

    /** Only the app's normalized, bounded JPEG representation may be written to the database. */
    static boolean validStoredJpeg(byte[] data) {
        return data != null && data.length >= 4 && data.length <= MAX_STORED_BYTES
                && (data[0] & 0xff) == 0xff && (data[1] & 0xff) == 0xd8
                && (data[data.length - 2] & 0xff) == 0xff && (data[data.length - 1] & 0xff) == 0xd9;
    }

    private PhotoPolicy() { }
}
