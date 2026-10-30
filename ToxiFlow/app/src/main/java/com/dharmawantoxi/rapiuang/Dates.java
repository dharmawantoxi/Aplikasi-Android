package com.dharmawantoxi.rapiuang;

import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.Locale;

/** Date-only helpers. Stored dates are local calendar dates and sort in ISO order. */
public final class Dates {
    public static final int MIN_YEAR = 1900;
    public static final int MAX_YEAR = 2100;
    private static final String[] MONTHS = {
            "Januari", "Februari", "Maret", "April", "Mei", "Juni", "Juli", "Agustus",
            "September", "Oktober", "November", "Desember"
    };

    private Dates() { }

    public static String iso(int year, int monthZeroBased, int day) {
        return String.format(Locale.US, "%04d-%02d-%02d", year, monthZeroBased + 1, day);
    }

    public static String today() {
        Calendar c = Calendar.getInstance();
        return iso(c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH));
    }

    public static boolean isValid(String date) {
        if (date == null || !date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) return false;
        try {
            int y = Integer.parseInt(date.substring(0, 4));
            int m = Integer.parseInt(date.substring(5, 7));
            int d = Integer.parseInt(date.substring(8, 10));
            if (y < MIN_YEAR || y > MAX_YEAR || m < 1 || m > 12 || d < 1) return false;
            Calendar c = new GregorianCalendar();
            c.clear();
            c.setLenient(false);
            c.set(y, m - 1, d);
            c.getTime();
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public static String monthKey(int year, int monthZeroBased) {
        return String.format(Locale.US, "%04d-%02d", year, monthZeroBased + 1);
    }

    public static String monthLabel(int year, int monthZeroBased) {
        return MONTHS[monthZeroBased] + " " + year;
    }

    /** Use today's day number within the selected month, clamped for shorter months. */
    public static String dateInMonth(int year, int monthZeroBased, String referenceDate) {
        if (year < MIN_YEAR || year > MAX_YEAR || monthZeroBased < 0 || monthZeroBased > 11 || !isValid(referenceDate))
            throw new IllegalArgumentException("Periode tidak valid");
        Calendar c = new GregorianCalendar();
        c.clear();
        c.set(year, monthZeroBased, 1);
        int day = Math.min(Integer.parseInt(referenceDate.substring(8, 10)), c.getActualMaximum(Calendar.DAY_OF_MONTH));
        return iso(year, monthZeroBased, day);
    }

    public static String display(String date) {
        if (!isValid(date)) return date == null ? "" : date;
        return Integer.parseInt(date.substring(8, 10)) + " "
                + MONTHS[Integer.parseInt(date.substring(5, 7)) - 1] + " " + date.substring(0, 4);
    }

    public static String displayShort(String date) {
        if (!isValid(date)) return date == null ? "" : date;
        return Integer.parseInt(date.substring(8, 10)) + " "
                + MONTHS[Integer.parseInt(date.substring(5, 7)) - 1].substring(0, 3);
    }

    public static Calendar calendarFor(String date) {
        if (!isValid(date)) throw new IllegalArgumentException("Tanggal tidak valid");
        Calendar c = Calendar.getInstance();
        c.setLenient(false);
        // Noon avoids DST edge cases around midnight in the current time zone.
        c.set(Integer.parseInt(date.substring(0, 4)), Integer.parseInt(date.substring(5, 7)) - 1,
                Integer.parseInt(date.substring(8, 10)), 12, 0, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c;
    }
}
