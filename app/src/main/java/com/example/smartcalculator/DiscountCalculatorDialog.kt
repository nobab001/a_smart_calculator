package com.example.smartcalculator

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale

/**
 * Quick Discount Calculator Dialog.
 *
 * User enters:
 *   • Total Amount  — the original price
 *   • Discount %    — the discount percentage
 *
 * The dialog auto-computes in real time:
 *   • Final Price  = Total × (1 − Discount/100)
 *   • You save     = Total − Final Price
 */
class DiscountCalculatorDialog(context: Context) : Dialog(context) {

    private lateinit var etTotal:     EditText
    private lateinit var etDiscount:  EditText
    private lateinit var cardResult:  LinearLayout
    private lateinit var tvFinal:     TextView
    private lateinit var tvSaved:     TextView
    private lateinit var btnCopy:     Button
    private lateinit var btnClose:    ImageButton

    private val fmt = NumberFormat.getNumberInstance(Locale.getDefault()).apply {
        maximumFractionDigits = 2
        minimumFractionDigits = 0
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.dialog_discount_calculator)

        // Transparent window background so the rounded card is visible
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window?.setLayout(
            (context.resources.displayMetrics.widthPixels * 0.92).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )
        window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        bindViews()
        wireListeners()
    }

    private fun bindViews() {
        etTotal    = findViewById(R.id.etTotalAmount)
        etDiscount = findViewById(R.id.etDiscountPercent)
        cardResult = findViewById(R.id.cardResult)
        tvFinal    = findViewById(R.id.tvFinalPrice)
        tvSaved    = findViewById(R.id.tvSavedAmount)
        btnCopy    = findViewById(R.id.btnCopyResult)
        btnClose   = findViewById(R.id.btnDiscountClose)
    }

    private fun wireListeners() {
        btnClose.setOnClickListener { dismiss() }

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = recalculate()
        }

        etTotal.addTextChangedListener(watcher)
        etDiscount.addTextChangedListener(watcher)

        btnCopy.setOnClickListener { copyResultToClipboard() }
    }

    // ── Core calculation ──────────────────────────────────────────────────

    private fun recalculate() {
        val total    = etTotal.text.toString().toDoubleOrNull()
        val discount = etDiscount.text.toString().toDoubleOrNull()

        if (total == null || discount == null) {
            cardResult.visibility = View.GONE
            return
        }

        // Clamp discount to [0, 100]
        val clampedDiscount = discount.coerceIn(0.0, 100.0)

        val saved      = round(total * clampedDiscount / 100.0)
        val finalPrice = round(total - saved)

        tvFinal.text = fmt.format(finalPrice)
        tvSaved.text = fmt.format(saved)

        cardResult.visibility = View.VISIBLE
    }

    private fun round(value: Double): Double =
        BigDecimal(value).setScale(2, RoundingMode.HALF_UP).toDouble()

    // ── Copy ──────────────────────────────────────────────────────────────

    private fun copyResultToClipboard() {
        val total    = etTotal.text.toString().toDoubleOrNull() ?: return
        val discount = etDiscount.text.toString().toDoubleOrNull() ?: return
        val saved      = round(total * discount.coerceIn(0.0, 100.0) / 100.0)
        val finalPrice = round(total - saved)

        val text = "Total: ${fmt.format(total)}\n" +
                   "Discount: ${fmt.format(discount)}%\n" +
                   "Final Price: ${fmt.format(finalPrice)}\n" +
                   "You save: ${fmt.format(saved)}"

        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Discount Result", text))
        Toast.makeText(context, "Result copied", Toast.LENGTH_SHORT).show()
    }
}
