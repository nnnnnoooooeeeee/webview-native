package com.webview.builder.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit

//========== SETTINGS ==========

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** User preferences, kept in SharedPreferences and exposed as Compose state. */
class Settings(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var themeMode by mutableStateOf(
        runCatching { ThemeMode.valueOf(prefs.getString(KEY_THEME, null)!!) }.getOrDefault(ThemeMode.SYSTEM)
    )
        private set

    /** Pure black backgrounds in dark mode (saves power on AMOLED screens). */
    var amoled by mutableStateOf(prefs.getBoolean(KEY_AMOLED, false))
        private set

    /** Colors from the wallpaper (Android 12+). */
    var dynamicColor by mutableStateOf(prefs.getBoolean(KEY_DYNAMIC, false))
        private set

    /** The versionCode the next build gets. Android only installs updates with a higher one. */
    var nextBuildNumber by mutableIntStateOf(prefs.getInt(KEY_BUILD, 1))
        private set

    fun updateTheme(mode: ThemeMode) {
        themeMode = mode
        prefs.edit { putString(KEY_THEME, mode.name) }
    }

    fun updateAmoled(enabled: Boolean) {
        amoled = enabled
        prefs.edit { putBoolean(KEY_AMOLED, enabled) }
    }

    fun updateDynamicColor(enabled: Boolean) {
        dynamicColor = enabled
        prefs.edit { putBoolean(KEY_DYNAMIC, enabled) }
    }

    fun updateNextBuildNumber(number: Int) {
        nextBuildNumber = number.coerceAtLeast(1)
        prefs.edit { putInt(KEY_BUILD, nextBuildNumber) }
    }

    private companion object {
        const val KEY_THEME = "theme"
        const val KEY_AMOLED = "amoled"
        const val KEY_DYNAMIC = "dynamic_color"
        const val KEY_BUILD = "next_build_number"
    }
}
