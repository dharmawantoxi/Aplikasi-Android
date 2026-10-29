package com.dharmawantoxi.docscan.ui

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/** A private app preference, independent of the device's language. */
object Language {
    private const val PREFS = "app_language"
    private const val KEY = "language"
    const val INDONESIAN = "id"
    const val ENGLISH = "en"

    fun code(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(KEY, INDONESIAN).let { if (it == ENGLISH) ENGLISH else INDONESIAN }

    fun set(context: Context, value: String) {
        require(value == INDONESIAN || value == ENGLISH)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, value).apply()
    }

    fun wrap(context: Context): Context {
        val config = Configuration(context.resources.configuration)
        config.setLocale(Locale.forLanguageTag(code(context)))
        return context.createConfigurationContext(config)
    }

    fun detail(context: Context, message: String?): String? =
        if (message == null || code(context) != ENGLISH) message else ErrorTranslations.english(message)

    fun text(context: Context, indonesian: String, english: String): String =
        if (code(context) == ENGLISH) english else indonesian
}
