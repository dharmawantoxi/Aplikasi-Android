package com.dharmawantoxi.rapiuang;

/** Bounds the decoded photo memory retained by Android's PDF recorder for one write. */
final class PhotoPrintPolicy {
    static final int MAX_PHOTOS_PER_WRITE = 64;
    // PdfDocument retains recorded bitmaps until it closes. This caps them at about 32 MiB (ARGB).
    static final long MAX_TOTAL_PIXELS = 8_000_000L;

    static final class Size {
        final int width, height;
        Size(int width, int height) { this.width = width; this.height = height; }
    }

    static Size sizeFor(int width, int height, int photoCount) {
        if (width <= 0 || height <= 0 || width > PhotoPolicy.MAX_SIDE || height > PhotoPolicy.MAX_SIDE
                || photoCount <= 0 || photoCount > MAX_PHOTOS_PER_WRITE)
            throw new IllegalArgumentException("Ukuran atau jumlah foto tidak valid.");
        long budget = MAX_TOTAL_PIXELS / photoCount;
        long pixels = (long) width * height;
        if (pixels <= budget) return new Size(width, height);
        double factor = Math.sqrt((double) budget / pixels);
        int w = Math.max(1, (int) Math.floor(width * factor));
        int h = Math.max(1, (int) Math.floor(height * factor));
        // Very narrow images can round their short side up to one pixel.
        if ((long) w * h > budget) {
            if (w > h) w = (int) Math.max(1, budget / h);
            else h = (int) Math.max(1, budget / w);
        }
        return new Size(w, h);
    }

    /** Power-of-two decode downsampling, without dropping below the final target. */
    static int decodeSample(int width, int height, Size target) {
        int sample = 1;
        while (sample < (1 << 27)) {
            int next = sample * 2;
            if (((long) width + next - 1) / next < target.width
                    || ((long) height + next - 1) / next < target.height) break;
            sample = next;
        }
        return sample;
    }

    private PhotoPrintPolicy() { }
}
