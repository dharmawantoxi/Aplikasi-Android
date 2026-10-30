package com.dharmawantoxi.rapiuang;

import android.content.Context;
import android.content.ContextWrapper;
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
        Context localized = base.createConfigurationContext(configuration);
        // A configuration context has its own service cache and is not an Activity. In
        // particular, PrintManager.print() rejects a manager created with that context.
        // Keep localized resources, but obtain services from the original activity base,
        // whose outer context is the Activity that Android attached to it.
        return new ContextWrapper(localized) {
            @Override public Object getSystemService(String name) {
                // PrintManager requires an Activity-backed context in PrintManager.print().
                // All other services retain the localized context and its resource configuration.
                return Context.PRINT_SERVICE.equals(name)
                        ? base.getSystemService(name) : super.getSystemService(name);
            }
        };
    }
}
