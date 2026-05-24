package com.example.smartcalculator

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Singleton that holds the Smart Mode calculation history for the current session.
 * Both [FloatingWindowService] and [SmartAccessibilityService] read/write this object.
 */
object HistoryManager {

    data class Entry(
        val value: Double,
        val source: String = "",
        val timestamp: Long = System.currentTimeMillis()
    )

    private val _entries = mutableListOf<Entry>()

    val entries: List<Entry> get() = _entries.toList()

    val total: Double get() = _entries.sumOf { it.value }

    val count: Int get() = _entries.size

    /** Called whenever the history changes — UI updates hook here. */
    var onChanged: (() -> Unit)? = null

    fun addEntry(value: Double, source: String = "") {
        _entries.add(Entry(value, source))
        onChanged?.invoke()
    }

    /** Called after clearing — UI and service can hook here to reset their state. */
    var onCleared: (() -> Unit)? = null

    fun clear() {
        _entries.clear()
        onChanged?.invoke()
        onCleared?.invoke()
    }

    /** Removes the last added entry. Returns the removed value, or null if empty. */
    fun removeLast(): Double? {
        if (_entries.isEmpty()) return null
        val removed = _entries.removeAt(_entries.lastIndex)
        onChanged?.invoke()
        return removed.value
    }

    fun hasEntries() = _entries.isNotEmpty()

    /**
     * Returns a readable expression string like "100 + 250.5 + 30"
     */
    fun expressionString(): String {
        if (_entries.isEmpty()) return "—"
        return _entries.joinToString(" + ") { fmt(it.value) }
    }

    fun formattedTotal(): String = fmt(total)

    private fun fmt(v: Double): String {
        if (v.isNaN() || v.isInfinite()) return "Error"
        val bd = BigDecimal(v).setScale(10, RoundingMode.HALF_UP).stripTrailingZeros()
        val plain = bd.toPlainString()
        return if (plain.contains('.') && plain.length > 10)
            BigDecimal(v).setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
        else plain
    }
}
