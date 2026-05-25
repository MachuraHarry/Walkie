package com.ronin.walkie.settings

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.util.Log
import java.util.Locale

/**
 * Hilfsklasse zum Verwalten der App-Sprache.
 * Ermöglicht das Setzen einer benutzerdefinierten Sprache unabhängig von der Systemsprache.
 */
object LocaleHelper {

    private const val TAG = "LocaleHelper"

    /**
     * Setzt die App-Sprache basierend auf dem gespeicherten Sprachcode.
     * Sollte im Application.onCreate() aufgerufen werden.
     */
    fun setLocale(context: Context): Context {
        val settingsManager = SettingsManager(context)
        val languageCode = settingsManager.getLanguage()
        return setLocale(context, languageCode)
    }

    /**
     * Setzt die App-Sprache auf einen bestimmten Sprachcode.
     * Unterstützte Codes: "de" (Deutsch), "en" (Englisch), "hr" (Kroatisch), "nb" (Norwegisch)
     */
    fun setLocale(context: Context, languageCode: String): Context {
        Log.d(TAG, "🌐 Setting locale to: $languageCode")

        val locale = when (languageCode) {
            "en" -> Locale.forLanguageTag("en")
            "hr" -> Locale.forLanguageTag("hr")
            "nb" -> Locale.forLanguageTag("nb")
            else -> Locale.forLanguageTag("de") // Fallback auf Deutsch
        }

        Locale.setDefault(locale)

        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return context.createConfigurationContext(config)
        } else {
            @Suppress("DEPRECATION")
            context.resources.updateConfiguration(config, context.resources.displayMetrics)
            return context
        }
    }

    /**
     * Gibt den Anzeigenamen für einen Sprachcode zurück.
     */
    fun getLanguageDisplayName(languageCode: String): String {
        return when (languageCode) {
            "de" -> "Deutsch"
            "en" -> "English"
            "hr" -> "Hrvatski"
            "nb" -> "Norsk"
            else -> "Deutsch"
        }
    }
}
