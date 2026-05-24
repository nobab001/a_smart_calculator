package com.example.smartcalculator

import android.content.Context

/**
 * Stores and retrieves independent Dark/Light theme preferences
 * for the Manual and Smart floating pop-up windows.
 * Default is LIGHT for both.
 */
object PopupThemeManager {
    const val LIGHT = 0
    const val DARK  = 1

    private const val PREFS       = "popup_theme_prefs"
    private const val KEY_MANUAL  = "manual_theme"
    private const val KEY_SMART   = "smart_theme"

    fun getManualTheme(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_MANUAL, LIGHT)

    fun getSmartTheme(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_SMART, LIGHT)

    fun setManualTheme(ctx: Context, mode: Int) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_MANUAL, mode).apply()

    fun setSmartTheme(ctx: Context, mode: Int) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_SMART, mode).apply()
}
