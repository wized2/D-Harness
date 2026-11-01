package com.endroid.dharness

import android.content.Context
import android.graphics.Typeface
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate

object ThemeHelper {
    const val PREFS = "dharness_settings"
    const val KEY_MODE = "theme_mode"
    const val MODE_SYSTEM = "system"
    const val MODE_LIGHT = "light"
    const val MODE_DARK = "dark"

    @Volatile private var sans: Typeface? = null
    @Volatile private var mono: Typeface? = null

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

    fun sans(context: Context): Typeface {
        sans?.let { return it }
        return try {
            Typeface.createFromAsset(context.assets, "fonts/anthropic_sans.ttf").also { sans = it }
        } catch (_: Exception) {
            Typeface.SANS_SERIF
        }
    }

    fun mono(context: Context): Typeface {
        mono?.let { return it }
        return try {
            Typeface.createFromAsset(context.assets, "fonts/jetbrains_mono.ttf").also { mono = it }
        } catch (_: Exception) {
            Typeface.MONOSPACE
        }
    }

    fun applySansToTree(context: Context, root: android.view.View) {
        val tf = sans(context)
        fun walk(v: android.view.View) {
            when (v) {
                is TextView -> v.typeface = tf
                is ViewGroup -> {
                    for (i in 0 until v.childCount) walk(v.getChildAt(i))
                }
            }
        }
        walk(root)
    }
}
