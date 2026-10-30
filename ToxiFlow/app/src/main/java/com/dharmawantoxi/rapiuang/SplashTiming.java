package com.dharmawantoxi.rapiuang;

/** Monotonic, recreation-safe timing for the existing three-second opening animation. */
final class SplashTiming {
    static final long DURATION_MS = 3_000L;

    private SplashTiming() { }

    static long deadline(long now, long restored) {
        return restored > 0 && restored <= now + DURATION_MS ? restored : now + DURATION_MS;
    }

    static long remaining(long now, long deadline) {
        return Math.max(0, Math.min(DURATION_MS, deadline - now));
    }

    static int progress(long remaining) {
        long bounded = Math.max(0, Math.min(DURATION_MS, remaining));
        return (int) ((DURATION_MS - bounded) * 100 / DURATION_MS);
    }
}
