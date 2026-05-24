package com.example.smartcalculator

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.DragEvent
import android.view.View
import android.widget.GridLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.smartcalculator.databinding.ActivityMainBinding
import net.objecthunter.exp4j.ExpressionBuilder
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Main calculator activity.
 *
 * Display has three zones:
 *   Zone 1 (top,  faded)  – session history: all completed operations since last AC
 *   Zone 2 (mid,  large)  – current input being typed
 *   Zone 3 (bot,  orange) – running session total (∑ of all = results in session)
 *
 * AC button: saves session to SharedPreferences, then clears everything.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // ── Expression engine (BODMAS/PEMDAS via exp4j) ──
    private val exprDisplay         = StringBuilder()  // "10+5×2÷3"  shown to user
    private val exprCalc            = StringBuilder()  // "10+5*2/3"  fed to exp4j
    private var hasDecimalInCurrent = false            // decimal in the number being typed
    private var justPressedEquals   = false            // result just shown, waiting for next action

    // ── 3-zone display state ──
    private val sessionLines = mutableListOf<String>() // completed "10+5×2 = 20"
    private var sessionTotal = 0.0                     // last = result

    // ── Misc ──
    private var savedThemeOnResume = ThemeManager.SYSTEM
    private var pendingManual      = true
    private var isEditMode         = false

    companion object {
        private const val OVERLAY_PERMISSION_REQUEST = 1001
        private const val PREFS_HISTORY = "calc_history"
        private const val KEY_SESSIONS  = "saved_sessions"
        const val EXTRA_EDIT_MODE       = "extra_edit_mode"
    }

    private val settingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result -> if (result.resultCode == RESULT_OK) recreate() }

    // ──────────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedThemeOnResume = ThemeManager.getSaved(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyButtonLayout()
        setupButtonListeners()
        refreshAll()
        handleSharedText(intent)
        if (intent.getBooleanExtra(EXTRA_EDIT_MODE, false)) enterEditMode()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleSharedText(intent)
        if (intent.getBooleanExtra(EXTRA_EDIT_MODE, false)) enterEditMode()
    }

    override fun onResume() {
        super.onResume()
        val current = ThemeManager.getSaved(this)
        if (current != savedThemeOnResume) { savedThemeOnResume = current; recreate() }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OVERLAY_PERMISSION_REQUEST) {
            if (Settings.canDrawOverlays(this)) startFloatService(pendingManual)
            else Toast.makeText(this, getString(R.string.overlay_permission_needed), Toast.LENGTH_LONG).show()
        }
    }

    // ──────────────────────────────────────────────
    // UI wiring
    // ──────────────────────────────────────────────

    private fun setupButtonListeners() = with(binding) {
        mapOf(
            btn0 to "0", btn1 to "1", btn2 to "2", btn3 to "3", btn4 to "4",
            btn5 to "5", btn6 to "6", btn7 to "7", btn8 to "8", btn9 to "9"
        ).forEach { (b, d) -> b.setOnClickListener { onDigitPressed(d) } }

        btnDoubleZero.setOnClickListener { onDoubleZeroPressed() }
        btnDecimal.setOnClickListener   { onDecimalPressed() }

        btnAdd.setOnClickListener      { onOperatorPressed("+") }
        btnSubtract.setOnClickListener { onOperatorPressed("−") }
        btnMultiply.setOnClickListener { onOperatorPressed("×") }
        btnDivide.setOnClickListener   { onOperatorPressed("÷") }
        btnPercent.setOnClickListener  { onPercentPressed() }

        btnEquals.setOnClickListener    { onEqualsPressed() }
        btnClear.setOnClickListener     { onClearPressed() }
        btnBackspace.setOnClickListener { onBackspacePressed() }
        btnBackspace.setOnLongClickListener { onClearPressed(); true }

        btnFloatManual.setOnClickListener     { launchFloatMode(manual = true) }
        btnFloatSmart.setOnClickListener      { launchFloatMode(manual = false) }
        btnAccessibility.setOnClickListener   { openAccessibilitySettings() }
        btnSettings.setOnClickListener        { openSettingsPage() }
        btnHistory.setOnClickListener          { openHistoryPage() }
        btnDiscount.setOnClickListener         { DiscountCalculatorDialog(this@MainActivity).show() }
        btnEditDone.setOnClickListener        { exitEditMode() }
    }

    // ──────────────────────────────────────────────
    // Input handlers
    // ──────────────────────────────────────────────

    private fun onDigitPressed(digit: String) {
        if (justPressedEquals) {
            exprDisplay.clear(); exprCalc.clear()
            hasDecimalInCurrent = false; justPressedEquals = false
        }
        if (exprCalc.length < 30) {
            exprDisplay.append(digit)
            exprCalc.append(digit)
        }
        refreshAll()
    }

    private fun onDoubleZeroPressed() {
        val cur = currentNumber()
        if (cur.isEmpty() || cur == "0") return
        if (justPressedEquals) {
            exprDisplay.clear(); exprCalc.clear()
            hasDecimalInCurrent = false; justPressedEquals = false
        }
        if (exprCalc.length < 29) {
            exprDisplay.append("00"); exprCalc.append("00")
        }
        refreshAll()
    }

    private fun onDecimalPressed() {
        if (hasDecimalInCurrent) return
        if (justPressedEquals) {
            exprDisplay.clear(); exprCalc.clear()
            hasDecimalInCurrent = false; justPressedEquals = false
        }
        if (endsWithOperator() || exprCalc.isEmpty()) {
            exprDisplay.append("0"); exprCalc.append("0")
        }
        exprDisplay.append("."); exprCalc.append(".")
        hasDecimalInCurrent = true
        refreshAll()
    }

    private fun onOperatorPressed(operator: String) {
        // Allow leading minus for negative number entry
        if (exprCalc.isEmpty() && operator == "−") {
            exprDisplay.append("−"); exprCalc.append("-")
            refreshAll(); return
        }
        if (exprCalc.isEmpty()) return
        if (justPressedEquals) justPressedEquals = false
        // Replace trailing operator
        if (endsWithOperator()) {
            exprDisplay.deleteCharAt(exprDisplay.length - 1)
            exprCalc.deleteCharAt(exprCalc.length - 1)
        }
        exprDisplay.append(operator)
        exprCalc.append(toCalcOp(operator))
        hasDecimalInCurrent = false
        refreshAll()
    }

    private fun onEqualsPressed() {
        if (exprCalc.isEmpty()) return
        // Strip trailing operator
        if (endsWithOperator()) {
            exprDisplay.deleteCharAt(exprDisplay.length - 1)
            exprCalc.deleteCharAt(exprCalc.length - 1)
        }
        if (exprCalc.isEmpty()) return

        val result = evalExpr(exprCalc.toString())
        if (result.isNaN() || result.isInfinite()) {
            binding.tvResult.text = "Error"; return
        }

        val resultStr = formatResult(result)
        sessionLines.add("${exprDisplay} = $resultStr")
        sessionTotal = result
        justPressedEquals = true
        hasDecimalInCurrent = resultStr.contains('.')

        // Set expression to result so user can chain (e.g., press + next)
        exprDisplay.clear(); exprDisplay.append(resultStr)
        exprCalc.clear(); exprCalc.append(resultStr)
        refreshAll()
    }

    /** Clears state without persisting the session (used by backspace-after-equals). */
    private fun clearWithoutSaving() {
        sessionLines.clear()
        exprDisplay.clear(); exprCalc.clear()
        hasDecimalInCurrent = false; justPressedEquals = false
        sessionTotal = 0.0
        refreshAll()
    }

    /** AC: save session → clear everything */
    private fun onClearPressed() {
        if (sessionLines.isNotEmpty()) saveSession()
        sessionLines.clear()
        exprDisplay.clear(); exprCalc.clear()
        hasDecimalInCurrent = false; justPressedEquals = false
        sessionTotal = 0.0
        refreshAll()
    }

    private fun onPercentPressed() {
        if (justPressedEquals) justPressedEquals = false
        val curNum = currentNumber()
        val num = curNum.toDoubleOrNull() ?: return

        val dispExpr = exprDisplay.toString()
        val calcExpr = exprCalc.toString()
        val lastOpD = dispExpr.indexOfLast { it == '+' || it == '−' || it == '×' || it == '÷' }
        val lastOpC = calcExpr.indexOfLast   { it == '+' || it == '-' || it == '*' || it == '/' }

        val pctVal: Double = if (lastOpD >= 0) {
            val base = try { ExpressionBuilder(calcExpr.substring(0, lastOpC)).build().evaluate() }
                       catch (_: Exception) { 0.0 }
            if (base.isNaN() || base.isInfinite()) num / 100.0 else base * (num / 100.0)
        } else num / 100.0

        val pctStr = formatResult(pctVal)
        if (lastOpD >= 0) {
            exprDisplay.clear(); exprDisplay.append(dispExpr.substring(0, lastOpD + 1) + pctStr)
            exprCalc.clear();    exprCalc.append(calcExpr.substring(0, lastOpC + 1) + pctStr)
        } else {
            exprDisplay.clear(); exprDisplay.append(pctStr)
            exprCalc.clear();    exprCalc.append(pctStr)
        }
        hasDecimalInCurrent = pctStr.contains('.')
        refreshAll()
    }

    private fun onBackspacePressed() {
        if (justPressedEquals) { clearWithoutSaving(); return }
        if (exprDisplay.isEmpty()) return
        val removed = exprDisplay.last()
        exprDisplay.deleteCharAt(exprDisplay.length - 1)
        exprCalc.deleteCharAt(exprCalc.length - 1)
        if (removed == '.') hasDecimalInCurrent = false
        else hasDecimalInCurrent = currentNumber().contains('.')
        refreshAll()
    }

    // ──────────────────────────────────────────────
    // 3-zone display refresh
    // ──────────────────────────────────────────────

    private fun refreshAll() {
        refreshZone2Input()
        refreshZone1History()
        refreshZone3Total()
    }

    /** Zone 2 – large number: current number being typed OR last result. */
    private fun refreshZone2Input() {
        val shown = when {
            justPressedEquals   -> formatResult(sessionTotal)
            exprDisplay.isEmpty() -> "0"
            else -> {
                val cur = currentNumber()
                if (cur.isEmpty()) "0" else formatNumber(cur)
            }
        }
        binding.tvResult.text = shown
        binding.tvResult.textSize = when {
            shown.length > 12 -> 32f
            shown.length > 9  -> 42f
            shown.length > 6  -> 52f
            else              -> 64f
        }
    }

    /**
     * Zone 1 – history + live expression.
     * Shows completed session lines above, then the expression currently being typed.
     */
    private fun refreshZone1History() {
        val parts = mutableListOf<String>()
        parts.addAll(sessionLines)
        // Show live expression when the user is still building it
        if (!justPressedEquals && exprDisplay.isNotEmpty()) {
            parts.add(exprDisplay.toString())
        }
        binding.tvHistory.text = parts.joinToString("\n")
        binding.scrollHistory.post {
            binding.scrollHistory.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }

    /**
     * Zone 3 – live running total.
     * Evaluates the current expression in real time so the user always sees
     * the up-to-date result while typing, without needing to press =.
     *
     * Logic:
     *  • justPressedEquals → show the exact = result (sessionTotal)
     *  • Typing   → strip any trailing operator, then eval the expression live
     *  • Empty    → show 0
     */
    private fun refreshZone3Total() {
        val display: String = when {
            // After = was pressed, show the committed result
            justPressedEquals -> formatResult(sessionTotal)

            exprCalc.isNotEmpty() -> {
                // Strip trailing operator so e.g. "10+" evaluates as "10"
                val calcStr = exprCalc.toString()
                    .trimEnd { it == '+' || it == '-' || it == '*' || it == '/' }

                if (calcStr.isNotEmpty()) {
                    val live = evalExpr(calcStr)
                    when {
                        !live.isNaN() && !live.isInfinite() -> formatResult(live)
                        sessionLines.isNotEmpty()           -> formatResult(sessionTotal)
                        else                                -> "0"
                    }
                } else {
                    if (sessionLines.isNotEmpty()) formatResult(sessionTotal) else "0"
                }
            }

            // Nothing typed, no history
            else -> if (sessionTotal == 0.0 && sessionLines.isEmpty()) "0"
                    else formatResult(sessionTotal)
        }
        binding.tvTotal.text = display
    }

    // ──────────────────────────────────────────────
    // Expression engine helpers
    // ──────────────────────────────────────────────

    /** Returns the number currently being typed (last segment after the last operator). */
    private fun currentNumber(): String {
        val expr = exprDisplay.toString()
        val lastOp = expr.indexOfLast { it == '+' || it == '−' || it == '×' || it == '÷' }
        return if (lastOp < 0) expr else expr.substring(lastOp + 1)
    }

    private fun endsWithOperator(): Boolean {
        val c = exprCalc.lastOrNull() ?: return false
        return c == '+' || c == '-' || c == '*' || c == '/'
    }

    private fun toCalcOp(displayOp: String): String = when (displayOp) {
        "×" -> "*"; "÷" -> "/"; "−" -> "-"; else -> displayOp
    }

    /** Evaluate a math expression string using exp4j (BODMAS/PEMDAS). */
    private fun evalExpr(expr: String): Double = try {
        ExpressionBuilder(expr).build().evaluate()
    } catch (_: Exception) { Double.NaN }

    // ──────────────────────────────────────────────
    // Session persistence (saved on AC press)
    // ──────────────────────────────────────────────

    private fun saveSession() {
        val ts = SimpleDateFormat("dd MMM  HH:mm", Locale.getDefault()).format(Date())
        // Compute grand-total from all = results in this session
        val grandTotal = sessionLines.sumOf { line ->
            line.substringAfterLast("= ").toDoubleOrNull() ?: 0.0
        }
        val text = "[$ts]\n" +
                   sessionLines.joinToString("\n") +
                   "\n∑ Total = ${formatResult(grandTotal)}"

        val prefs    = getSharedPreferences(PREFS_HISTORY, MODE_PRIVATE)
        val existing = prefs.getString(KEY_SESSIONS, "") ?: ""
        val separator = if (existing.isEmpty()) "" else "\n\n"
        prefs.edit().putString(KEY_SESSIONS, existing + separator + text).apply()
    }

    // (calc engine replaced by exp4j evalExpr() above)

    // ──────────────────────────────────────────────
    // Formatting
    // ──────────────────────────────────────────────

    private fun formatResult(value: Double): String {
        if (value.isNaN() || value.isInfinite()) return "Error"
        val bd = BigDecimal(value).setScale(10, RoundingMode.HALF_UP).stripTrailingZeros()
        val plain = bd.toPlainString()
        return if (plain.contains('.') && plain.length > 12)
            BigDecimal(value).setScale(6, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
        else plain
    }

    private fun formatNumber(input: String): String {
        if (input.isEmpty()) return "0"
        if (input == "-" || input.endsWith(".")) return input
        return try {
            val parts   = input.split(".")
            val intPart = parts[0].toLongOrNull()?.let { "%,d".format(it) } ?: parts[0]
            if (parts.size > 1) "$intPart.${parts[1]}" else intPart
        } catch (_: Exception) { input }
    }

    // ──────────────────────────────────────────────
    // Floating mode
    // ──────────────────────────────────────────────

    private fun launchFloatMode(manual: Boolean) {
        if (!Settings.canDrawOverlays(this)) {
            AlertDialog.Builder(this)
                .setTitle("Overlay Permission Required")
                .setMessage("To show the floating calculator over other apps, please grant 'Display over other apps'.")
                .setPositiveButton("Grant") { _, _ ->
                    pendingManual = manual
                    @Suppress("DEPRECATION")
                    startActivityForResult(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")),
                        OVERLAY_PERMISSION_REQUEST
                    )
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        startFloatService(manual)
    }

    private fun startFloatService(manual: Boolean) {
        val action = if (manual) FloatingWindowService.ACTION_SHOW_MANUAL
                     else FloatingWindowService.ACTION_SHOW_SMART
        startService(Intent(this, FloatingWindowService::class.java).apply { this.action = action })
        if (!manual && !isAccessibilityServiceEnabled()) {
            AlertDialog.Builder(this)
                .setTitle("Enable Accessibility Service")
                .setMessage(getString(R.string.accessibility_needed))
                .setPositiveButton("Open Settings") { _, _ ->
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                .setNegativeButton("Later", null)
                .show()
        }
    }

    // ──────────────────────────────────────────────
    // Settings / Accessibility
    // ──────────────────────────────────────────────

    private fun openSettingsPage() {
        settingsLauncher.launch(Intent(this, SettingsActivity::class.java))
    }

    private fun openHistoryPage() {
        startActivity(Intent(this, HistoryActivity::class.java))
    }

    // ──────────────────────────────────────────────
    // Button layout – apply saved order
    // ──────────────────────────────────────────────

    private fun applyButtonLayout() {
        val order = ButtonLayout.getOrder(this)
        reorderGridChildren(order)
    }

    /** Removes all GridLayout children and re-inserts them in [order]. */
    private fun reorderGridChildren(order: List<String>) {
        val grid = binding.buttonGrid
        val viewMap = (0 until grid.childCount).associate { i ->
            val v = grid.getChildAt(i)
            resources.getResourceEntryName(v.id) to v
        }
        val params = (0 until grid.childCount).associate { i ->
            val v = grid.getChildAt(i)
            resources.getResourceEntryName(v.id) to v.layoutParams
        }
        grid.removeAllViews()
        for (name in order) {
            val v  = viewMap[name]  ?: continue
            val lp = params[name]   ?: continue
            grid.addView(v, lp)
        }
    }

    // ──────────────────────────────────────────────
    // Edit mode (drag-and-drop button rearrangement)
    // ──────────────────────────────────────────────

    fun enterEditMode() {
        if (isEditMode) return
        isEditMode = true

        // Swap topBar: hide normal buttons, show edit hint + Done
        with(binding) {
            btnFloatManual.visibility  = View.GONE
            btnFloatSmart.visibility   = View.GONE
            btnAccessibility.visibility= View.GONE
            btnSettings.visibility     = View.GONE
            historyStrip.visibility    = View.GONE
            tvEditHint.visibility      = View.VISIBLE
            btnEditDone.visibility     = View.VISIBLE
        }

        // Enable drag on every button in the grid
        val grid = binding.buttonGrid
        for (i in 0 until grid.childCount) {
            enableDragOnButton(grid.getChildAt(i), grid)
        }
    }

    private fun exitEditMode() {
        if (!isEditMode) return
        isEditMode = false

        // Persist the current order
        val grid = binding.buttonGrid
        val newOrder = (0 until grid.childCount).map { i ->
            resources.getResourceEntryName(grid.getChildAt(i).id)
        }
        ButtonLayout.saveOrder(this, newOrder)

        // Restore every button's appearance
        for (i in 0 until grid.childCount) {
            val v = grid.getChildAt(i)
            v.alpha  = 1f
            v.scaleX = 1f
            v.scaleY = 1f
            v.setOnLongClickListener(null)
            v.setOnDragListener(null)
        }

        // Restore topBar
        with(binding) {
            btnFloatManual.visibility  = View.VISIBLE
            btnFloatSmart.visibility   = View.VISIBLE
            btnAccessibility.visibility= View.VISIBLE
            btnSettings.visibility     = View.VISIBLE
            historyStrip.visibility    = View.VISIBLE
            tvEditHint.visibility      = View.GONE
            btnEditDone.visibility     = View.GONE
        }

        // Re-wire click listeners so buttons work normally again
        setupButtonListeners()
    }

    private fun enableDragOnButton(v: View, grid: GridLayout) {
        v.setOnLongClickListener { view ->
            val name   = resources.getResourceEntryName(view.id)
            val shadow = View.DragShadowBuilder(view)
            view.startDragAndDrop(
                ClipData.newPlainText("btn", name), shadow, view, 0
            )
            view.alpha = 0.35f
            true
        }

        v.setOnDragListener { target, event ->
            when (event.action) {
                DragEvent.ACTION_DRAG_ENTERED -> {
                    if (event.localState != target) {
                        target.scaleX = 1.12f
                        target.scaleY = 1.12f
                    }
                    true
                }
                DragEvent.ACTION_DRAG_EXITED -> {
                    target.scaleX = 1f
                    target.scaleY = 1f
                    true
                }
                DragEvent.ACTION_DROP -> {
                    target.scaleX = 1f
                    target.scaleY = 1f
                    val dragged = event.localState as? View ?: return@setOnDragListener false
                    if (dragged != target) swapGridButtons(grid, dragged, target)
                    dragged.alpha = 1f
                    true
                }
                DragEvent.ACTION_DRAG_ENDED -> {
                    (event.localState as? View)?.let {
                        it.alpha  = 1f
                        it.scaleX = 1f
                        it.scaleY = 1f
                    }
                    true
                }
                else -> true
            }
        }
    }

    /** Swaps the positions of [a] and [b] within the GridLayout. */
    private fun swapGridButtons(grid: GridLayout, a: View, b: View) {
        val children = (0 until grid.childCount).map { grid.getChildAt(it) }.toMutableList()
        val ia = children.indexOf(a)
        val ib = children.indexOf(b)
        if (ia < 0 || ib < 0 || ia == ib) return

        children[ia] = b
        children[ib] = a

        // Collect LayoutParams before removing
        val lpMap = children.associateWith { it.layoutParams }
        grid.removeAllViews()
        children.forEach { grid.addView(it, lpMap[it]) }

        // Re-attach drag listeners to maintain drag capability
        for (i in 0 until grid.childCount) {
            enableDragOnButton(grid.getChildAt(i), grid)
        }
    }

    private fun openAccessibilitySettings() {
        if (isAccessibilityServiceEnabled()) {
            Toast.makeText(this, "✔ Accessibility service is enabled", Toast.LENGTH_SHORT).show()
        } else {
            AlertDialog.Builder(this)
                .setTitle("Enable Accessibility Service")
                .setMessage(getString(R.string.accessibility_needed))
                .setPositiveButton("Open Settings") { _, _ ->
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val service = "$packageName/${SmartAccessibilityService::class.java.canonicalName}"
        return try {
            val enabled = Settings.Secure.getString(
                contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            enabled.split(":").any { it.equals(service, ignoreCase = true) }
        } catch (_: Exception) { false }
    }

    // ──────────────────────────────────────────────
    // WhatsApp / external text hooks (public API)
    // ──────────────────────────────────────────────

    /** Evaluate a raw expression string (with any operator symbols) via exp4j. */
    fun evaluateExpression(raw: String): String? {
        val expr = normalizeToCalcExpr(raw)
        val result = evalExpr(expr)
        return if (result.isNaN() || result.isInfinite()) null else formatResult(result)
    }

    fun applyExternalExpression(raw: String) {
        onClearPressed()
        val calc = normalizeToCalcExpr(raw)
        val disp = raw.replace(",", "")
                      .replace("*", "×").replace("x", "×").replace("X", "×")
                      .replace("/", "÷").replace("-", "−")
        exprCalc.clear();    exprCalc.append(calc)
        exprDisplay.clear(); exprDisplay.append(disp)
        refreshAll()
    }

    fun handleSharedText(intent: Intent?) {
        if (intent == null) return
        val text = when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else               -> null
        } ?: return
        applyExternalExpression(text)
    }

    private fun normalizeToCalcExpr(raw: String): String =
        raw.replace(",", "")
           .replace("×", "*").replace("x", "*").replace("X", "*")
           .replace("÷", "/")
           .replace("−", "-")
}
