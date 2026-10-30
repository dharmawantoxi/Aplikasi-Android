package com.dharmawantoxi.rapiuang;

import java.util.Locale;

/** Stable app-wide language choice; financial values remain Indonesian rupiah. */
enum AppLanguage {
    INDONESIAN("id", new Locale("id", "ID")),
    ENGLISH("en", Locale.ENGLISH);

    final String code;
    final Locale locale;

    AppLanguage(String code, Locale locale) {
        this.code = code;
        this.locale = locale;
    }

    String text(String indonesian, String english) {
        return this == ENGLISH ? english : indonesian;
    }

    static AppLanguage fromCode(String code) {
        return ENGLISH.code.equals(code) ? ENGLISH : INDONESIAN;
    }
}
