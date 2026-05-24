package com.example.smartcalculator

import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.smartcalculator.databinding.ActivityHistoryBinding

class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding

    companion object {
        private const val PREFS_HISTORY = "calc_history"
        private const val KEY_SESSIONS  = "saved_sessions"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnClearAll.setOnClickListener { confirmClearAll() }

        loadAndDisplay()
    }

    private fun loadAndDisplay() {
        val prefs = getSharedPreferences(PREFS_HISTORY, Context.MODE_PRIVATE)
        val raw   = prefs.getString(KEY_SESSIONS, "") ?: ""

        if (raw.isBlank()) {
            binding.emptyState.visibility  = View.VISIBLE
            binding.scrollHistory.visibility = View.GONE
            return
        }

        binding.emptyState.visibility  = View.GONE
        binding.scrollHistory.visibility = View.VISIBLE
        binding.sessionsContainer.removeAllViews()

        // Sessions are newest-first for readability – reverse the split list
        val sessions = raw.split("\n\n").filter { it.isNotBlank() }.reversed()
        sessions.forEachIndexed { index, session ->
            addSessionCard(session, index == 0)
        }
    }

    private fun addSessionCard(sessionText: String, isLatest: Boolean) {
        val lines = sessionText.trim().split("\n")

        // Wrapper card
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = androidx.core.content.ContextCompat.getDrawable(this@HistoryActivity, R.drawable.bg_history_card)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(12)
            layoutParams = lp
        }

        lines.forEachIndexed { i, line ->
            val tv = TextView(this)
            when {
                line.startsWith("[") && line.endsWith("]") -> {
                    // Timestamp header
                    tv.text = line.removePrefix("[").removeSuffix("]")
                    tv.textSize = 11f
                    tv.setTextColor(getColor(R.color.text_secondary))
                    tv.alpha = 0.6f
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    lp.bottomMargin = dp(8)
                    tv.layoutParams = lp
                }
                line.startsWith("∑") -> {
                    // Total line
                    tv.text = line
                    tv.textSize = 17f
                    tv.setTextColor(getColor(R.color.btn_operator))
                    tv.typeface = android.graphics.Typeface.DEFAULT_BOLD
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    lp.topMargin = dp(6)
                    tv.layoutParams = lp
                    tv.gravity = Gravity.END
                }
                else -> {
                    // Operation line (e.g. "10 + 10 = 20")
                    tv.text = line
                    tv.textSize = 15f
                    tv.setTextColor(getColor(R.color.text_primary))
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    lp.bottomMargin = dp(2)
                    tv.layoutParams = lp
                    tv.gravity = Gravity.END
                }
            }
            card.addView(tv)
        }

        // "Latest" badge
        if (isLatest) {
            val badge = TextView(this).apply {
                text = "Latest"
                textSize = 10f
                setTextColor(ContextCompat.getColor(this@HistoryActivity, R.color.bg_dark))
                setBackgroundColor(ContextCompat.getColor(this@HistoryActivity, R.color.btn_operator))
                setPadding(dp(8), dp(2), dp(8), dp(2))
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.bottomMargin = dp(6)
                layoutParams = lp
            }
            card.addView(badge, 0)
        }

        binding.sessionsContainer.addView(card)
    }

    private fun confirmClearAll() {
        AlertDialog.Builder(this)
            .setTitle("Clear History")
            .setMessage("Delete all saved sessions?")
            .setPositiveButton("Clear") { _, _ ->
                getSharedPreferences(PREFS_HISTORY, Context.MODE_PRIVATE)
                    .edit().remove(KEY_SESSIONS).apply()
                loadAndDisplay()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
