package com.macromind.foodscanner

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import android.content.Intent
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.macromind.foodscanner.data.AppDatabase
import com.macromind.foodscanner.data.ScanHistoryEntity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * HistoryActivity — Shows all past scans in a premium dark-themed RecyclerView.
 *
 * Features:
 *   • List of scan cards with verdict, category, confidence, additive count
 *   • Tap → shows detail dialog with ingredient preview
 *   • Long-press → delete confirmation
 *   • "Clear All" button in header
 *   • Empty state when no scans exist
 */
class HistoryActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyState: LinearLayout
    private lateinit var headerCount: TextView
    private lateinit var clearAllBtn: TextView

    private val adapter = HistoryAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        recyclerView = findViewById(R.id.historyRecyclerView)
        emptyState   = findViewById(R.id.emptyState)
        headerCount  = findViewById(R.id.headerCount)
        clearAllBtn  = findViewById(R.id.clearAllBtn)

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        // Back button
        findViewById<View>(R.id.backButton).setOnClickListener { finish() }

        // Clear all
        clearAllBtn.setOnClickListener { confirmClearAll() }

        // Observe history
        lifecycleScope.launch {
            AppDatabase.get(this@HistoryActivity).scanHistoryDao().getAll()
                .collectLatest { items ->
                    adapter.items = items
                    adapter.notifyDataSetChanged()

                    if (items.isEmpty()) {
                        recyclerView.visibility = View.GONE
                        emptyState.visibility = View.VISIBLE
                        clearAllBtn.visibility = View.GONE
                        headerCount.text = "No scans yet"
                    } else {
                        recyclerView.visibility = View.VISIBLE
                        emptyState.visibility = View.GONE
                        clearAllBtn.visibility = View.VISIBLE
                        headerCount.text = "${items.size} scan${if (items.size != 1) "s" else ""}"
                    }
                }
        }

        playEntranceAnimation()
    }

    private fun confirmClearAll() {
        AlertDialog.Builder(this)
            .setTitle("Clear All History")
            .setMessage("Delete all ${adapter.items.size} scan records? This cannot be undone.")
            .setPositiveButton("Delete All") { _, _ ->
                lifecycleScope.launch {
                    AppDatabase.get(this@HistoryActivity).scanHistoryDao().deleteAll()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDelete(entity: ScanHistoryEntity) {
        AlertDialog.Builder(this)
            .setTitle("Delete Scan")
            .setMessage("Remove this ${entity.category} scan from history?")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    AppDatabase.get(this@HistoryActivity).scanHistoryDao().delete(entity)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDetail(entity: ScanHistoryEntity) {
        if (entity.rawJson.isBlank()) {
            Toast.makeText(this, "Detailed result not available for this older scan.", Toast.LENGTH_SHORT).show()
            return
        }
        
        try {
            val result = com.google.gson.Gson().fromJson(entity.rawJson, OfflineAnalyzer.AnalysisResult::class.java)
            if (result != null) {
                ScanSession.historyResult = result
                val intent = Intent(this, ResultsActivity::class.java).apply {
                    putExtra("fromHistory", true)
                }
                startActivity(intent)
                @Suppress("DEPRECATION")
                overridePendingTransition(R.anim.slide_up_in, R.anim.fade_out)
            } else {
                Toast.makeText(this, "Could not load history details.", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error reading history data.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun playEntranceAnimation() {
        val topBar = findViewById<View>(R.id.topBar)
        topBar.alpha = 0f; topBar.translationY = -20f
        topBar.animate().alpha(1f).translationY(0f)
            .setDuration(250).setStartDelay(50)
            .setInterpolator(DecelerateInterpolator()).start()
    }

    // ── Adapter ──────────────────────────────────────────────────────────────

    inner class HistoryAdapter : RecyclerView.Adapter<HistoryAdapter.VH>() {

        var items: List<ScanHistoryEntity> = emptyList()
            set(value) { field = value; animatedPositions.clear() }
        private val animatedPositions = mutableSetOf<Int>()

        inner class VH(val root: LinearLayout) : RecyclerView.ViewHolder(root) {
            val emoji:      TextView = root.findViewWithTag("emoji")
            val category:   TextView = root.findViewWithTag("category")
            val verdict:    TextView = root.findViewWithTag("verdict")
            val date:       TextView = root.findViewWithTag("date")
            val confidence: TextView = root.findViewWithTag("confidence")
            val addBadge:   TextView = root.findViewWithTag("addBadge")
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val dp = { v: Int -> (v * parent.resources.displayMetrics.density).toInt() }

            // ── Card root ──
            val card = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#FFFFFF"))
                    cornerRadius = dp(14).toFloat()
                }
                setPadding(dp(14), dp(12), dp(14), dp(12))
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(dp(12), dp(4), dp(12), dp(4)) }
            }

            // ── Emoji (left) ──
            card.addView(TextView(parent.context).apply {
                tag = "emoji"; textSize = 32f
                layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply {
                    setMargins(0, 0, dp(12), 0)
                }
                gravity = Gravity.CENTER
            })

            // ── Center column ──
            val center = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            center.addView(TextView(parent.context).apply {
                tag = "category"; textSize = 15f; setTextColor(Color.parseColor("#1A1D26"))
                typeface = Typeface.DEFAULT_BOLD
            })
            center.addView(TextView(parent.context).apply {
                tag = "verdict"; textSize = 12f; setTextColor(Color.parseColor("#6B7280"))
            })
            center.addView(TextView(parent.context).apply {
                tag = "date"; textSize = 10f; setTextColor(Color.parseColor("#9CA3AF"))
                setPadding(0, dp(2), 0, 0)
            })
            card.addView(center)

            // ── Right column ──
            val right = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
            }
            right.addView(TextView(parent.context).apply {
                tag = "confidence"; textSize = 14f; setTextColor(Color.parseColor("#1A1D26"))
                typeface = Typeface.DEFAULT_BOLD
            })
            right.addView(TextView(parent.context).apply {
                tag = "addBadge"; textSize = 10f
                setPadding(dp(6), dp(2), dp(6), dp(2))
            })
            card.addView(right)

            return VH(card)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            val dateStr = SimpleDateFormat("MMM dd · HH:mm", Locale.getDefault())
                .format(Date(item.timestamp))

            holder.emoji.text = when (item.verdict.lowercase()) {
                "healthy" -> "✅"; "moderate" -> "⚠️"; "unhealthy" -> "❌"; else -> "❓"
            }
            holder.category.text = item.category.ifBlank { "Unknown" }
            holder.verdict.text = "${item.verdict.replaceFirstChar { it.uppercase() }}  ·  ${item.additiveCount} additives"
            holder.date.text = dateStr
            holder.confidence.text = "${(item.confidence * 100).toInt()}%"

            // Additive badge
            if (item.harmfulAdditiveCount > 0) {
                holder.addBadge.text = "⚠ ${item.harmfulAdditiveCount}"
                holder.addBadge.setTextColor(Color.parseColor("#DC2626"))
                holder.addBadge.background = GradientDrawable().apply {
                    setColor(Color.parseColor("#FEF2F2"))
                    cornerRadius = 8f
                }
                holder.addBadge.visibility = View.VISIBLE
            } else if (item.additiveCount > 0) {
                holder.addBadge.text = "✓ safe"
                holder.addBadge.setTextColor(Color.parseColor("#16A34A"))
                holder.addBadge.background = GradientDrawable().apply {
                    setColor(Color.parseColor("#F0FDF4"))
                    cornerRadius = 8f
                }
                holder.addBadge.visibility = View.VISIBLE
            } else {
                holder.addBadge.visibility = View.GONE
            }

            // Tap → detail
            holder.root.setOnClickListener { showDetail(item) }
            // Long-press → delete
            holder.root.setOnLongClickListener { confirmDelete(item); true }

            // Entrance animation for each card (only on first appearance)
            if (position !in animatedPositions) {
                animatedPositions.add(position)
                holder.root.alpha = 0f; holder.root.translationX = 30f
                holder.root.animate().alpha(1f).translationX(0f)
                    .setDuration(200).setStartDelay(position * 30L)
                    .setInterpolator(DecelerateInterpolator()).start()
            }
        }

        override fun getItemCount() = items.size
    }
}
