package com.endroid.dharness

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

object ThemeHelper {
    const val PREFS = "dharness_settings"
    const val KEY_MODE = "theme_mode"
    const val MODE_SYSTEM = "system"
    const val MODE_LIGHT = "light"
    const val MODE_DARK = "dark"

    fun applyFromPrefs(context: Context) {
        val mode = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MODE, MODE_SYSTEM) ?: MODE_SYSTEM
        apply(mode)
    }

    fun apply(mode: String) {
        val night = when (mode) {
            MODE_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            MODE_DARK -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(night)
    }

    fun save(context: Context, mode: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_MODE, mode).apply()
        apply(mode)
    }

    fun current(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MODE, MODE_SYSTEM) ?: MODE_SYSTEM
}
