package com.ronin.walkie.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.ronin.walkie.settings.SettingsManager

private val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80
)

private val LightColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40
)

/**
 * Bestimmt, ob der Dark Mode aktiviert werden soll, basierend auf den App-Einstellungen.
 * - "system": Folgt dem System-Dark-Mode
 * - "dark": Immer dunkel
 * - "light": Immer hell
 *
 * Diese Funktion ist NICHT @Composable, da sie nur SharedPreferences liest.
 * Der System-Dark-Mode wird hier nicht dynamisch erfasst - das passiert
 * in der @Composable WalkieTheme-Funktion.
 */
fun isDarkThemeFromSettings(context: android.content.Context): Boolean {
    val settingsManager = SettingsManager(context)
    return when (settingsManager.getDarkMode()) {
        "dark" -> true
        "light" -> false
        else -> {
            // System-Modus: Wir können hier nicht isSystemInDarkTheme() aufrufen,
            // da das eine @Composable-Funktion ist. Stattdessen fragen wir
            // die System-UiMode-Konfiguration ab.
            val uiMode = context.resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK
            uiMode == android.content.res.Configuration.UI_MODE_NIGHT_YES
        }
    }
}

@Composable
fun WalkieTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
