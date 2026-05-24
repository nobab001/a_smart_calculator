package com.example.smartcalculator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.widget.ImageViewCompat
import com.google.android.material.button.MaterialButton
import net.objecthunter.exp4j.ExpressionBuilder
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Foreground service that renders floating overlay windows via [WindowManager].
 *
 * Three states:
 *  1. MANUAL   – compact calculator, manual input
 *  2. SMART    – small total-display, populated by [SmartAccessibilityService]
 *  3. BUBBLE   – minimised circle at screen edge
 */
class FloatingWindowService : Service() {

    // ── Public API ────────────────────────────────
    companion object {
        const val ACTION_SHOW_MANUAL  = "com.example.smartcalculator.SHOW_MANUAL"
        const val ACTION_SHOW_SMART   = "com.example.smartcalculator.SHOW_SMART"
        const val ACTION_ADD_NUMBER   = "com.example.smartcalculator.ADD_NUMBER"
        const val ACTION_DOCK         = "com.example.smartcalculator.DOCK"
        const val ACTION_CLEAR_SMART  = "com.example.smartcalculator.CLEAR_SMART"
        const val EXTRA_NUMBER        = "extra_number"
        const val EXTRA_SOURCE        = "extra_source"

        private const val CHANNEL_ID = "smartcalc_float"
        private const val NOTIF_ID   = 9001
    }

    // ── Window state ──────────────────────────────
    private lateinit var wm: WindowManager
    private var floatView: View? = null
    private var bubbleView: View? = null
    private var currentMode = ""        // "manual" | "smart" | "bubble"
    private var preMinimiseMode = ""    // mode to restore when bubble is tapped

    // ── Manual calc state (exp4j expression engine) ───────────────────────
    private val floatExprDisplay = StringBuilder()   // "10+5×2"  shown to user
    private val floatExprCalc    = StringBuilder()   // "10+5*2"  fed to exp4j
    private var floatHasDecimal  = false
    private var floatJustEquals  = false

    // ── Receivers ─────────────────────────────────
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                ACTION_ADD_NUMBER -> {
                    refreshSmartDisplay()
                    flashSmartTotal()   // visual feedback on each new selection
                }
                ACTION_DOCK -> if (currentMode != "bubble") dockToBubble()
            }
        }
    }

    // ─────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())

        val filter = IntentFilter().apply {
            addAction(ACTION_ADD_NUMBER)
            addAction(ACTION_DOCK)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        else
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter)

        HistoryManager.onChanged = { refreshSmartDisplay() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW_MANUAL -> showManual()
            ACTION_SHOW_SMART  -> showSmart()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        removeFloat()
        removeBubble()
        unregisterReceiver(receiver)
        HistoryManager.onChanged = null
    }

    // ─────────────────────────────────────────────
    // Manual mode
    // ─────────────────────────────────────────────

    private fun showManual() {
        removeFloat()
        currentMode = "manual"
        preMinimiseMode = "manual"
        resetCalcState()

        val view = inflate(R.layout.layout_floating_manual)
        floatView = view

        applyPopupTheme(view, PopupThemeManager.getManualTheme(this), isManual = true)

        // Fixed initial size: 300 × 415 dp — GridLayout fills remaining space
        val params = buildParams(300, 415)
        wireManualButtons(view)
        makeDraggable(view.findViewById(R.id.floatManualHeader), view, params)
        attachResizeHandle(view.findViewById(R.id.resizeHandle), view, params)
        wm.addView(view, params)
    }

    private fun wireManualButtons(v: View) {
        val display  = v.findViewById<TextView>(R.id.tvFloatResult)
        val exprView = v.findViewById<TextView>(R.id.tvFloatExpression)

        // ── helpers ──────────────────────────────────────────────────────

        fun currentNumber(): String {
            val expr = floatExprDisplay.toString()
            val lastOp = expr.indexOfLast { it == '+' || it == '−' || it == '×' || it == '÷' }
            return if (lastOp < 0) expr else expr.substring(lastOp + 1)
        }

        fun endsWithOp() = floatExprCalc.lastOrNull()
            ?.let { it == '+' || it == '-' || it == '*' || it == '/' } ?: false

        fun toCalcOp(op: String) = when (op) { "×" -> "*"; "÷" -> "/"; "−" -> "-"; else -> op }

        fun update() {
            if (floatJustEquals) {
                display.text  = fmtNum(floatExprDisplay.toString().ifEmpty { "0" })
                exprView.text = ""
            } else {
                val cur = currentNumber()
                display.text = fmtNum(cur.ifEmpty { "0" })
                val expr   = floatExprDisplay.toString()
                val lastOp = expr.indexOfLast { it == '+' || it == '−' || it == '×' || it == '÷' }
                exprView.text = if (lastOp >= 0) expr.substring(0, lastOp + 1) else ""
            }
        }

        // ── input handlers ───────────────────────────────────────────────

        fun onDigit(d: String) {
            if (floatJustEquals) {
                floatExprDisplay.clear(); floatExprCalc.clear()
                floatHasDecimal = false; floatJustEquals = false
            }
            if (floatExprCalc.length < 30) {
                floatExprDisplay.append(d); floatExprCalc.append(d)
            }
            update()
        }

        fun onDoubleZero() {
            val cur = currentNumber()
            if (cur.isEmpty() || cur == "0") return
            if (floatJustEquals) {
                floatExprDisplay.clear(); floatExprCalc.clear()
                floatHasDecimal = false; floatJustEquals = false
            }
            if (floatExprCalc.length < 29) {
                floatExprDisplay.append("00"); floatExprCalc.append("00")
            }
            update()
        }

        fun onDecimal() {
            if (floatHasDecimal) return
            if (floatJustEquals) {
                floatExprDisplay.clear(); floatExprCalc.clear()
                floatHasDecimal = false; floatJustEquals = false
            }
            if (endsWithOp() || floatExprCalc.isEmpty()) {
                floatExprDisplay.append("0"); floatExprCalc.append("0")
            }
            floatExprDisplay.append("."); floatExprCalc.append(".")
            floatHasDecimal = true
            update()
        }

        fun onOperator(op: String) {
            if (floatExprCalc.isEmpty() && op == "−") {
                floatExprDisplay.append("−"); floatExprCalc.append("-")
                update(); return
            }
            if (floatExprCalc.isEmpty()) return
            if (floatJustEquals) floatJustEquals = false
            if (endsWithOp()) {
                floatExprDisplay.deleteCharAt(floatExprDisplay.length - 1)
                floatExprCalc.deleteCharAt(floatExprCalc.length - 1)
            }
            floatExprDisplay.append(op)
            floatExprCalc.append(toCalcOp(op))
            floatHasDecimal = false
            update()
        }

        fun onEquals() {
            if (floatExprCalc.isEmpty()) return
            if (endsWithOp()) {
                floatExprDisplay.deleteCharAt(floatExprDisplay.length - 1)
                floatExprCalc.deleteCharAt(floatExprCalc.length - 1)
            }
            if (floatExprCalc.isEmpty()) return
            val result = try {
                ExpressionBuilder(floatExprCalc.toString()).build().evaluate()
            } catch (_: Exception) { Double.NaN }
            if (result.isNaN() || result.isInfinite()) {
                display.text = "Error"; exprView.text = ""; return
            }
            val resultStr = fmtResult(result)
            floatExprDisplay.clear(); floatExprDisplay.append(resultStr)
            floatExprCalc.clear();    floatExprCalc.append(resultStr)
            floatHasDecimal = resultStr.contains('.')
            floatJustEquals = true
            update()
        }

        fun onPercent() {
            if (floatJustEquals) floatJustEquals = false
            val num = currentNumber().toDoubleOrNull() ?: return
            val dispExpr = floatExprDisplay.toString()
            val calcExpr = floatExprCalc.toString()
            val lastOpD = dispExpr.indexOfLast { it == '+' || it == '−' || it == '×' || it == '÷' }
            val lastOpC = calcExpr.indexOfLast  { it == '+' || it == '-' || it == '*' || it == '/' }
            val pctVal: Double = if (lastOpD >= 0) {
                val base = try { ExpressionBuilder(calcExpr.substring(0, lastOpC)).build().evaluate() }
                           catch (_: Exception) { 0.0 }
                if (base.isNaN() || base.isInfinite()) num / 100.0 else base * (num / 100.0)
            } else num / 100.0
            val pctStr = fmtResult(pctVal)
            if (lastOpD >= 0) {
                floatExprDisplay.clear(); floatExprDisplay.append(dispExpr.substring(0, lastOpD + 1) + pctStr)
                floatExprCalc.clear();    floatExprCalc.append(calcExpr.substring(0, lastOpC + 1) + pctStr)
            } else {
                floatExprDisplay.clear(); floatExprDisplay.append(pctStr)
                floatExprCalc.clear();    floatExprCalc.append(pctStr)
            }
            floatHasDecimal = pctStr.contains('.')
            update()
        }

        fun onBackspace() {
            if (floatJustEquals) {
                resetCalcState(); display.text = "0"; exprView.text = ""; return
            }
            if (floatExprDisplay.isEmpty()) return
            val removed = floatExprDisplay.last()
            floatExprDisplay.deleteCharAt(floatExprDisplay.length - 1)
            floatExprCalc.deleteCharAt(floatExprCalc.length - 1)
            floatHasDecimal = if (removed == '.') false else currentNumber().contains('.')
            update()
        }

        // ── button wiring ─────────────────────────────────────────────────

        val digitMap = mapOf(
            R.id.btnFloat0 to "0", R.id.btnFloat1 to "1", R.id.btnFloat2 to "2",
            R.id.btnFloat3 to "3", R.id.btnFloat4 to "4", R.id.btnFloat5 to "5",
            R.id.btnFloat6 to "6", R.id.btnFloat7 to "7", R.id.btnFloat8 to "8",
            R.id.btnFloat9 to "9"
        )
        digitMap.forEach { (id, d) ->
            v.findViewById<MaterialButton>(id).setOnClickListener { onDigit(d) }
        }

        v.findViewById<MaterialButton>(R.id.btnFloat00).setOnClickListener        { onDoubleZero() }
        v.findViewById<MaterialButton>(R.id.btnFloatDecimal).setOnClickListener   { onDecimal() }
        v.findViewById<MaterialButton>(R.id.btnFloatClear).setOnClickListener     { resetCalcState(); display.text = "0"; exprView.text = "" }
        v.findViewById<MaterialButton>(R.id.btnFloatBackspace).setOnClickListener { onBackspace() }
        v.findViewById<MaterialButton>(R.id.btnFloatPercent).setOnClickListener   { onPercent() }
        v.findViewById<MaterialButton>(R.id.btnFloatAdd).setOnClickListener       { onOperator("+") }
        v.findViewById<MaterialButton>(R.id.btnFloatSubtract).setOnClickListener  { onOperator("−") }
        v.findViewById<MaterialButton>(R.id.btnFloatMultiply).setOnClickListener  { onOperator("×") }
        v.findViewById<MaterialButton>(R.id.btnFloatDivide).setOnClickListener    { onOperator("÷") }
        v.findViewById<MaterialButton>(R.id.btnFloatEquals).setOnClickListener    { onEquals() }

        v.findViewById<ImageButton>(R.id.btnFloatMinimize).setOnClickListener { dockToBubble() }
        v.findViewById<ImageButton>(R.id.btnFloatClose).setOnClickListener {
            removeFloat()
            removeBubble()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
                stopForeground(STOP_FOREGROUND_REMOVE)
            else
                @Suppress("DEPRECATION") stopForeground(true)
            stopSelf()
        }
    }

    // ─────────────────────────────────────────────
    // Smart mode
    // ─────────────────────────────────────────────

    private fun showSmart() {
        removeFloat()
        currentMode = "smart"
        preMinimiseMode = "smart"

        val view = inflate(R.layout.layout_floating_smart)
        floatView = view

        applyPopupTheme(view, PopupThemeManager.getSmartTheme(this), isManual = false)

        val params = buildParams(210, LayoutParams.WRAP_CONTENT)
        wireSmartButtons(view)
        makeDraggable(view.findViewById(R.id.smartHeader), view, params)
        wm.addView(view, params)

        refreshSmartDisplay()
    }

    private fun wireSmartButtons(v: View) {
        val histPanel = v.findViewById<LinearLayout>(R.id.layoutHistoryPanel)
        val histItems = v.findViewById<LinearLayout>(R.id.layoutHistoryItems)
        val tvHTotal  = v.findViewById<TextView>(R.id.tvHistoryTotal)

        v.findViewById<ImageButton>(R.id.btnSmartHistory).setOnClickListener {
            if (histPanel.visibility == View.GONE) {
                populateHistory(histItems, tvHTotal)
                histPanel.visibility = View.VISIBLE
            } else {
                histPanel.visibility = View.GONE
            }
        }

        // Copy: includes the expression + total for context
        v.findViewById<MaterialButton>(R.id.btnSmartAction).setOnClickListener {
            val expr   = HistoryManager.expressionString()
            val result = HistoryManager.formattedTotal()
            val text   = if (HistoryManager.hasEntries()) "$expr = $result" else result
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("result", text))
            Toast.makeText(this, "Copied: $result", Toast.LENGTH_SHORT).show()
        }

        // Undo: removes the last captured selection
        v.findViewById<MaterialButton>(R.id.btnSmartUndo).setOnClickListener {
            val removed = HistoryManager.removeLast()
            if (removed == null) {
                Toast.makeText(this, "Nothing to undo", Toast.LENGTH_SHORT).show()
            } else {
                val fmt = java.math.BigDecimal(removed)
                    .setScale(4, java.math.RoundingMode.HALF_UP)
                    .stripTrailingZeros().toPlainString()
                Toast.makeText(this, "Removed: $fmt", Toast.LENGTH_SHORT).show()
            }
        }

        v.findViewById<MaterialButton>(R.id.btnClearHistory).setOnClickListener {
            HistoryManager.clear()
            histPanel.visibility = View.GONE
            sendBroadcast(Intent(ACTION_CLEAR_SMART))
            Toast.makeText(this, "Session cleared", Toast.LENGTH_SHORT).show()
        }

        v.findViewById<ImageButton>(R.id.btnSmartMinimize).setOnClickListener { dockToBubble() }
        v.findViewById<ImageButton>(R.id.btnSmartClose).setOnClickListener {
            removeFloat()
            removeBubble()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
                stopForeground(STOP_FOREGROUND_REMOVE)
            else
                @Suppress("DEPRECATION") stopForeground(true)
            stopSelf()
        }
    }

    private fun refreshSmartDisplay() {
        val v = floatView ?: return
        if (currentMode != "smart") return

        v.post {
            // Total
            v.findViewById<TextView>(R.id.tvSmartTotal)?.text = HistoryManager.formattedTotal()

            // Selection count label
            val n = HistoryManager.count
            v.findViewById<TextView>(R.id.tvSmartCount)?.text =
                if (n == 0) "" else "$n selection${if (n > 1) "s" else ""}"

            // Live expression: "355 + 877 + 98 …"
            val tvExpr   = v.findViewById<TextView>(R.id.tvSmartExpression)
            val scrollExpr = v.findViewById<android.widget.HorizontalScrollView>(R.id.scrollSmartExpression)
            tvExpr?.text = if (HistoryManager.hasEntries()) HistoryManager.expressionString() else ""
            // Auto-scroll expression to the right so the newest value is always visible
            scrollExpr?.post { scrollExpr.fullScroll(android.view.View.FOCUS_RIGHT) }

            // Refresh history panel if it is open
            val histPanel = v.findViewById<LinearLayout>(R.id.layoutHistoryPanel)
            if (histPanel?.visibility == View.VISIBLE) {
                populateHistory(
                    v.findViewById(R.id.layoutHistoryItems),
                    v.findViewById(R.id.tvHistoryTotal)
                )
            }
        }
    }

    /**
     * Subtle scale-pulse on the TOTAL number to give visual feedback
     * each time a new selection is captured and added.
     */
    private fun flashSmartTotal() {
        val v  = floatView ?: return
        if (currentMode != "smart") return
        val tv = v.findViewById<TextView>(R.id.tvSmartTotal) ?: return
        tv.animate()
            .scaleX(1.15f).scaleY(1.15f)
            .setDuration(90)
            .withEndAction {
                tv.animate().scaleX(1f).scaleY(1f).setDuration(180).start()
            }
            .start()
    }

    private fun populateHistory(container: LinearLayout, tvTotal: TextView) {
        container.removeAllViews()
        val entries = HistoryManager.entries
        entries.forEachIndexed { i, entry ->
            val row = TextView(this).apply {
                text = if (i == 0) HistoryManager.fmt(entry.value)
                       else "+ ${HistoryManager.fmt(entry.value)}"
                textSize   = 14f
                setTextColor(resources.getColor(R.color.text_primary, null))
                setPadding(0, 4, 0, 4)
            }
            container.addView(row)
        }
        tvTotal.text = "Total: ${HistoryManager.formattedTotal()}"
    }

    // ─────────────────────────────────────────────
    // Bubble (minimised)
    // ─────────────────────────────────────────────

    private fun dockToBubble() {
        if (currentMode == "bubble") return
        preMinimiseMode = currentMode
        removeFloat()
        currentMode = "bubble"

        val view = inflate(R.layout.layout_floating_bubble)
        bubbleView = view

        val sizePx = dpToPx(20)
        val params = buildParams(sizePx, sizePx).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0; y = 300
        }

        var initX = 0; var initY = 0; var initRx = 0f; var initRy = 0f; var moved = false

        view.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = params.x; initY = params.y
                    initRx = ev.rawX; initRy = ev.rawY
                    moved = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initX + (ev.rawX - initRx).toInt()
                    params.y = initY + (ev.rawY - initRy).toInt()
                    wm.updateViewLayout(view, params)
                    if (Math.abs(ev.rawX - initRx) > 8 || Math.abs(ev.rawY - initRy) > 8) moved = true
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        removeBubble()
                        if (preMinimiseMode == "smart") showSmart() else showManual()
                    } else {
                        snapBubble(params)
                    }
                    false
                }
                else -> false
            }
        }

        wm.addView(view, params)
    }

    private fun snapBubble(params: LayoutParams) {
        val bv = bubbleView ?: return
        val screenW = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            wm.currentWindowMetrics.bounds.width()
        else
            @Suppress("DEPRECATION") wm.defaultDisplay.width
        params.x = if (params.x + dpToPx(10) < screenW / 2) 0 else screenW - dpToPx(20)
        wm.updateViewLayout(bv, params)
    }

    // ─────────────────────────────────────────────
    // Drag helper
    // ─────────────────────────────────────────────

    private fun makeDraggable(handle: View, root: View, params: LayoutParams) {
        var initX = 0; var initY = 0; var initRx = 0f; var initRy = 0f

        handle.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = params.x; initY = params.y
                    initRx = ev.rawX; initRy = ev.rawY; true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initX + (ev.rawX - initRx).toInt()
                    params.y = initY + (ev.rawY - initRy).toInt()
                    wm.updateViewLayout(root, params); true
                }
                else -> false
            }
        }
    }

    /**
     * Attaches a corner-drag resize listener to [handle].
     * Dragging the handle changes the window's width and height in real time.
     * Minimum: 240 × 330 dp  |  Maximum: 90 % of screen dimensions.
     */
    private fun attachResizeHandle(handle: View, root: View, params: LayoutParams) {
        val minW = dpToPx(240); val minH = dpToPx(330)
        var startW = 0; var startH = 0; var startRx = 0f; var startRy = 0f

        val screenW: Int; val screenH: Int
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            screenW = bounds.width(); screenH = bounds.height()
        } else {
            @Suppress("DEPRECATION")
            screenW = wm.defaultDisplay.width
            @Suppress("DEPRECATION")
            screenH = wm.defaultDisplay.height
        }
        val maxW = (screenW * 0.9).toInt(); val maxH = (screenH * 0.9).toInt()

        handle.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    startW  = params.width.takeIf { it > 0 } ?: root.width
                    startH  = params.height.takeIf { it > 0 } ?: root.height
                    startRx = ev.rawX; startRy = ev.rawY; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val newW = (startW + (ev.rawX - startRx).toInt()).coerceIn(minW, maxW)
                    val newH = (startH + (ev.rawY - startRy).toInt()).coerceIn(minH, maxH)
                    params.width = newW; params.height = newH
                    wm.updateViewLayout(root, params); true
                }
                else -> false
            }
        }
    }

    // ─────────────────────────────────────────────
    // Manual calc logic
    // ─────────────────────────────────────────────

    private fun resetCalcState() {
        floatExprDisplay.clear(); floatExprCalc.clear()
        floatHasDecimal = false; floatJustEquals = false
    }

    // ─────────────────────────────────────────────
    // Formatting
    // ─────────────────────────────────────────────

    private fun fmtResult(v: Double): String {
        if (v.isNaN() || v.isInfinite()) return "Error"
        val bd = BigDecimal(v).setScale(10, RoundingMode.HALF_UP).stripTrailingZeros()
        val plain = bd.toPlainString()
        return if (plain.contains('.') && plain.length > 12)
            BigDecimal(v).setScale(6, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
        else plain
    }

    private fun fmtNum(input: String): String {
        if (input.isEmpty()) return "0"
        if (input == "-" || input.endsWith(".")) return input
        return try {
            val parts = input.split(".")
            val intPart = parts[0].toLongOrNull()?.let { "%,d".format(it) } ?: parts[0]
            if (parts.size > 1) "$intPart.${parts[1]}" else intPart
        } catch (_: Exception) { input }
    }

    // ─────────────────────────────────────────────
    // Utility
    // ─────────────────────────────────────────────

    // ─────────────────────────────────────────────
    // Popup theme application
    // ─────────────────────────────────────────────

    /**
     * Applies Light or Dark theme to an inflated floating popup view.
     * Dark theme = default layout colours (no-op).
     * Light theme = translucent white background, dark text.
     */
    private fun applyPopupTheme(root: View, theme: Int, isManual: Boolean) {
        if (theme == PopupThemeManager.DARK) return   // layout already dark by default

        // For Manual mode the root is now a FrameLayout wrapper; apply background to the
        // inner LinearLayout so clip-to-outline and rounded corners work correctly.
        val contentView: View = if (isManual)
            root.findViewById(R.id.manualFloatContent) ?: root
        else root

        // Background – translucent white rounded card
        contentView.background = ContextCompat.getDrawable(this, R.drawable.bg_float_window_light)
        contentView.clipToOutline = true

        val headerId  = if (isManual) R.id.floatManualHeader else R.id.smartHeader
        val colorPrimary   = Color.parseColor("#1C1C1E")
        val colorSecondary = Color.parseColor("#8E8E93")
        val headerBg       = Color.parseColor("#CCEDF1F8")
        val dividerColor   = Color.parseColor("#22000000")

        // Header background
        root.findViewById<View>(headerId)?.setBackgroundColor(headerBg)

        // All TextViews inside root – set appropriate text colours
        setAllTextColors(root, colorPrimary, colorSecondary)

        // Divider lines
        setDividerColors(root, dividerColor)

        // Icon tints (ImageButtons in header)
        tintImageButtons(root, colorSecondary)
    }

    private fun setAllTextColors(parent: View, primary: Int, secondary: Int) {
        if (parent !is android.view.ViewGroup) return
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            when (child) {
                is TextView -> {
                    val size = child.textSize
                    child.setTextColor(if (size >= 28f) primary else secondary)
                }
                is android.view.ViewGroup -> setAllTextColors(child, primary, secondary)
            }
        }
    }

    private fun setDividerColors(parent: View, color: Int) {
        if (parent !is android.view.ViewGroup) return
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            if (child.javaClass.simpleName == "View" &&
                child !is android.view.ViewGroup &&
                child.layoutParams?.height == 1
            ) {
                child.setBackgroundColor(color)
            } else if (child is android.view.ViewGroup) {
                setDividerColors(child, color)
            }
        }
    }

    private fun tintImageButtons(parent: View, tint: Int) {
        if (parent !is android.view.ViewGroup) return
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            if (child is ImageButton) {
                ImageViewCompat.setImageTintList(child, ColorStateList.valueOf(tint))
            } else if (child is android.view.ViewGroup) {
                tintImageButtons(child, tint)
            }
        }
    }

    private fun buildParams(widthDp: Int, heightDp: Int): LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") LayoutParams.TYPE_PHONE

        val w = if (widthDp == LayoutParams.WRAP_CONTENT) LayoutParams.WRAP_CONTENT else dpToPx(widthDp)
        val h = if (heightDp == LayoutParams.WRAP_CONTENT) LayoutParams.WRAP_CONTENT else dpToPx(heightDp)

        return LayoutParams(w, h, type,
            LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 80; y = 200
        }
    }

    private fun inflate(layoutRes: Int): View {
        val ctx = android.view.ContextThemeWrapper(applicationContext, R.style.Theme_SmartCalculator)
        return LayoutInflater.from(ctx).inflate(layoutRes, null)
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    private fun removeFloat() {
        floatView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        floatView = null
    }

    private fun removeBubble() {
        bubbleView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        bubbleView = null
    }

    // ─────────────────────────────────────────────
    // Notification (required for foreground service)
    // ─────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = getString(R.string.channel_desc) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(R.drawable.ic_float_mode)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }
}

// Extension to expose fmt() from HistoryManager
fun HistoryManager.fmt(v: Double): String {
    if (v.isNaN() || v.isInfinite()) return "Error"
    val bd = java.math.BigDecimal(v).setScale(10, java.math.RoundingMode.HALF_UP).stripTrailingZeros()
    val plain = bd.toPlainString()
    return if (plain.contains('.') && plain.length > 10)
        java.math.BigDecimal(v).setScale(4, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    else plain
}
