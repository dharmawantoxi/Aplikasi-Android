package com.dharmawantoxi.rapiuang;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

/** Isolate language-dependent UI tests without erasing a tester's saved choice. */
final class LanguageTestPreference implements AutoCloseable {
    private final SharedPreferences prefs;
    private final boolean hadChoice;
    private final String previousChoice;
    private final Locale previousLocale;

    LanguageTestPreference(Context context, AppLanguage language) {
        previousLocale = Locale.getDefault();
        prefs = context.getSharedPreferences(LanguageSettings.PREFS, Context.MODE_PRIVATE);
        hadChoice = prefs.contains(LanguageSettings.KEY);
        previousChoice = prefs.getString(LanguageSettings.KEY, null);
        if (!LanguageSettings.save(context, language)) throw new AssertionError("Could not set test language");
    }

    @Override public void close() {
        SharedPreferences.Editor edit = prefs.edit();
        if (hadChoice) edit.putString(LanguageSettings.KEY, previousChoice);
        else edit.remove(LanguageSettings.KEY);
        boolean restored = edit.commit();
        Locale.setDefault(previousLocale);
        if (!restored) throw new AssertionError("Could not restore language preference");
    }
}
