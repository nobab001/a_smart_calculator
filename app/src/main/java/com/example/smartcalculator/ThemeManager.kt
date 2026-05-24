package com.example.smartcalculator

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

object ThemeManager {

    const val LIGHT  = 0
    const val DARK   = 1
    const val SYSTEM = 2

    private const val PREF_NAME = "smartcalc_prefs"
    private const val KEY_THEME = "theme_mode"

    fun applyTheme(mode: Int) {
        AppCompatDelegate.setDefaultNightMode(
            when (mode) {
                LIGHT  -> AppCompatDelegate.MODE_NIGHT_NO
                DARK   -> AppCompatDelegate.MODE_NIGHT_YES
                else   -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }

    fun saveAndApply(context: Context, mode: Int) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_THEME, mode).apply()
        applyTheme(mode)
    }

    fun getSaved(context: Context): Int =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_THEME, SYSTEM)

    fun applySaved(context: Context) = applyTheme(getSaved(context))
}
