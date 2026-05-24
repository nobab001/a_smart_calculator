package com.example.smartcalculator

import android.content.Context

/**
 * Manages the saved order of calculator buttons.
 *
 * Buttons are identified by their resource-entry name (e.g. "btnClear", "btn7").
 * The default order mirrors the OnePlus-style layout defined in activity_main.xml.
 */
object ButtonLayout {

    val DEFAULT_ORDER = listOf(
        "btnClear", "btnPercent", "btnBackspace", "btnDivide",
        "btn7", "btn8", "btn9", "btnMultiply",
        "btn4", "btn5", "btn6", "btnSubtract",
        "btn1", "btn2", "btn3", "btnAdd",
        "btnDoubleZero", "btn0", "btnDecimal", "btnEquals"
    )

    private const val PREFS = "calc_button_layout"
    private const val KEY   = "button_order"

    fun getOrder(ctx: Context): List<String> {
        val saved = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return DEFAULT_ORDER
        val list = saved.split(",").filter { it.isNotBlank() }
        return if (list.size == DEFAULT_ORDER.size) list else DEFAULT_ORDER
    }

    fun saveOrder(ctx: Context, order: List<String>) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, order.joinToString(",")).apply()
    }

    fun reset(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY).apply()
    }
}
