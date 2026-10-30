package com.dharmawantoxi.rapiuang;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Page placement and requested-page normalization, independent of Android for easy testing. */
final class PrintPagination {
    static final class Position {
        final int page;
        final float top;
        Position(int page, float top) { this.page = page; this.top = top; }
    }

    static final class TooManyPagesException extends IllegalStateException { }

    static final class Cursor {
        private final float capacity;
        private final int maxPages;
        private int page;
        private float used;

        Cursor(float capacity, int maxPages) {
            if (!(capacity > 0) || Float.isInfinite(capacity) || maxPages < 1)
                throw new IllegalArgumentException("Ukuran halaman tidak valid");
            this.capacity = capacity;
            this.maxPages = maxPages;
        }

        /** Whitespace never creates an extra empty page. */
        void gap(float height) {
            if (height < 0 || Float.isNaN(height) || Float.isInfinite(height))
                throw new IllegalArgumentException("Jarak tidak valid");
            if (used > 0 && used + height < capacity) used += height;
        }

        /** Keep a heading with at least the beginning of the next paragraph when possible. */
        void ensure(float height) {
            if (!(height > 0) || Float.isInfinite(height))
                throw new IllegalArgumentException("Tinggi tidak valid");
            float wanted = Math.min(height, capacity);
            if (used > 0 && used + wanted > capacity) nextPage();
        }

        Position place(float height) {
            if (!(height > 0) || Float.isInfinite(height) || height > capacity)
                throw new IllegalArgumentException("Halaman terlalu kecil untuk teks");
            if (used + height > capacity) nextPage();
            Position result = new Position(page, used);
            used += height;
            return result;
        }

        int pageCount() { return page + 1; }

        private void nextPage() {
            if (page + 1 >= maxPages) throw new TooManyPagesException();
            page++;
            used = 0;
        }
    }

    /** Clip, sort and merge inclusive zero-based ranges. Invalid/out-of-document ranges are ignored. */
    static List<int[]> normalizeRanges(int pageCount, List<int[]> requested) {
        List<int[]> ranges = new ArrayList<>();
        if (pageCount <= 0 || requested == null) return ranges;
        for (int[] range : requested) {
            if (range == null || range.length != 2 || range[0] > range[1]
                    || range[1] < 0 || range[0] >= pageCount) continue;
            ranges.add(new int[]{Math.max(0, range[0]), Math.min(pageCount - 1, range[1])});
        }
        Collections.sort(ranges, new Comparator<int[]>() {
            @Override public int compare(int[] a, int[] b) { return Integer.compare(a[0], b[0]); }
        });
        List<int[]> merged = new ArrayList<>();
        for (int[] range : ranges) {
            if (!merged.isEmpty() && range[0] <= (long) merged.get(merged.size() - 1)[1] + 1) {
                int[] last = merged.get(merged.size() - 1);
                last[1] = Math.max(last[1], range[1]);
            } else {
                merged.add(range);
            }
        }
        return merged;
    }

    private PrintPagination() { }
}
