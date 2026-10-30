package com.dharmawantoxi.rapiuang;

import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.Locale;
import java.util.TimeZone;

/** Local wall-clock time at insertion, preserved even if the device time zone later changes. */
final class CreationTime {
    private static final String UNAVAILABLE = "Tidak tercatat";

    static String now() {
        return fromCalendar(new GregorianCalendar(TimeZone.getDefault(), Locale.US));
    }

    static String fromCalendar(Calendar c) {
        return String.format(Locale.US, "%04d-%02d-%02d %02d:%02d:%02d",
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
                c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND));
    }

    /** Accept only complete, valid wall-clock values; a null legacy value is not fabricated. */
    static boolean isValid(String value) {
        if (value == null || !value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}"))
            return false;
        try {
            int year = Integer.parseInt(value.substring(0, 4));
            if (year < 1) return false;
            Calendar c = new GregorianCalendar(TimeZone.getTimeZone("UTC"), Locale.US);
            c.clear();
            c.setLenient(false);
            c.set(year, Integer.parseInt(value.substring(5, 7)) - 1,
                    Integer.parseInt(value.substring(8, 10)), Integer.parseInt(value.substring(11, 13)),
                    Integer.parseInt(value.substring(14, 16)), Integer.parseInt(value.substring(17, 19)));
            c.getTimeInMillis();
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    static String display(String value) {
        if (!isValid(value)) return UNAVAILABLE;
        return Dates.display(value.substring(0, 10)) + ", " + value.substring(11, 16);
    }

    /** Put the hour first so it remains visible when a narrow list row is ellipsized. */
    static String listLabel(String value) {
        if (!isValid(value)) return "Jam pembuatan tidak tercatat";
        String date = value.substring(0, 10);
        String shortDate = Dates.displayShort(date);
        if (!shortDate.equals(date)) shortDate += " " + value.substring(0, 4);
        return value.substring(11, 16) + " · dibuat " + shortDate;
    }

    private CreationTime() { }
}
