package com.dharmawantoxi.rapiuang;

import android.content.Context;
import android.content.res.Configuration;

import java.util.Locale;

/** Local preference and per-activity locale, without changing the device's language. */
final class LanguageSettings {
    static final String PREFS = "toxiflow_settings";
    static final String KEY = "language";

    private LanguageSettings() { }

    static AppLanguage get(Context context) {
        return AppLanguage.fromCode(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, AppLanguage.INDONESIAN.code));
    }

    static boolean save(Context context, AppLanguage language) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY, language.code).commit();
    }

    static Context wrap(Context base) {
        AppLanguage language = get(base);
        // Some framework widgets (notably DatePicker) use the process default locale even when
        // their context is localized. This changes only this process, not the device language.
        Locale.setDefault(language.locale);
        Configuration configuration = new Configuration(base.getResources().getConfiguration());
        configuration.setLocale(language.locale);
        return base.createConfigurationContext(configuration);
    }
}
