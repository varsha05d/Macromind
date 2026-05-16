package com.macromind.foodscanner

import android.animation.ObjectAnimator
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.*
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.macromind.foodscanner.data.AppDatabase
import com.macromind.foodscanner.data.ScanHistoryEntity
import kotlinx.coroutines.launch

/**
 * ResultsActivity — Clean, simplified results screen.
 *
 * Shows:
 *  • Verdict hero with score ring
 *  • Plain-English explanation of WHY the verdict was given
 *  • Simplified additive summary
 *  • Clean nutrition facts
 */
class ResultsActivity : AppCompatActivity() {

    // ── Views ─────────────────────────────────────────────────────────────────
    private lateinit var loadingOverlay:    View
    private lateinit var loadingText:       TextView
    private lateinit var contentScroll:     ScrollView
    private lateinit var verdictCard:       View
    private lateinit var verdictEmoji:      TextView
    private lateinit var verdictLabel:      TextView
    private lateinit var verdictTagline:    TextView
    private lateinit var verdictConfidence: TextView
    private lateinit var scoreRing:         com.macromind.foodscanner.ui.ScoreRingView
    private lateinit var categoryBadge:     TextView
    private lateinit var dataCompleteness:  TextView
    private lateinit var explanationText:   TextView
    private lateinit var additiveTitle:     TextView
    private lateinit var additiveContainer: LinearLayout
    private lateinit var nutritionContainer:LinearLayout
    private lateinit var scanAgainButton:   Button
    private lateinit var shareButton:       Button
    private lateinit var frequencyCard:     View
    private lateinit var frequencyIcon:     TextView
    private lateinit var frequencyLabel:    TextView
    private lateinit var frequencyReason:   TextView

    // Keep last result for sharing
    private var lastResult: OfflineAnalyzer.AnalysisResult? = null

    // Cards that will be stagger-animated
    private lateinit var scoreCard:    View
    private lateinit var additiveCard: View
    private lateinit var nutritionCard:View
    // frequencyCard is already declared above

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_results)

        // Edge-to-edge immersive layout
        WindowCompat.setDecorFitsSystemWindows(window, false)

        bindViews()
        showLoading("Analysing with AI…")
        runAnalysis()

        // Back press transition
        onBackPressedDispatcher.addCallback(this) {
            isEnabled = false
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.fade_in, R.anim.slide_down_out)
            onBackPressedDispatcher.onBackPressed()
        }
    }

    // ── Analysis ─────────────────────────────────────────────────────────────

    private fun runAnalysis() {
        if (intent.getBooleanExtra("fromHistory", false)) {
            val result = ScanSession.historyResult
            if (result != null) {
                displayResult(result)
            } else {
                showError("History data is missing.")
            }
            return
        }

        lifecycleScope.launch {
            try {
                val result = OfflineAnalyzer.analyze(this@ResultsActivity, ScanSession)
                displayResult(result)
            } catch (e: Exception) {
                showError("Analysis failed: ${e.message}")
            }
        }
    }

    // ── Display ───────────────────────────────────────────────────────────────

    private fun displayResult(result: OfflineAnalyzer.AnalysisResult) {
        hideLoading()
        lastResult = result
        autoSaveToHistory(result)

        // ── Verdict card ──────────────────────────────────────────────────────
        verdictEmoji.text = result.verdictEmoji
        verdictLabel.text = result.verdict.uppercase()
        verdictTagline.text = verdictTagline(result.verdict, result.confidence)
        verdictConfidence.text = "${(result.confidence * 100).toInt()}%"
        val ringColor = when(result.verdict.lowercase()) {
            "healthy" -> Color.parseColor("#16A34A")
            "moderate" -> Color.parseColor("#D97706")
            "unhealthy" -> Color.parseColor("#DC2626")
            else -> Color.parseColor("#6B7280")
        }
        scoreRing.setProgress(result.confidence, ringColor, true)

        // Soft tinted background for verdict area
        val verdictBgColor = when(result.verdict.lowercase()) {
            "healthy" -> "#F0FDF4"
            "moderate" -> "#FFFBEB"
            "unhealthy" -> "#FEF2F2"
            else -> "#F7F8FA"
        }
        val grad = GradientDrawable()
        grad.setColor(Color.parseColor(verdictBgColor))
        verdictCard.background = grad

        // ── Category ─────────────────────────────────────────────────────────
        categoryBadge.text = "${result.category}  ·  ${formatPct(result.categoryConfidence)} match"

        // Data completeness
        if (!result.ingredientsScanned || !result.nutritionScanned) {
            val parts = buildList {
                if (result.ingredientsScanned) add("✅ Ingredients") else add("⬜ Ingredients")
                if (result.nutritionScanned) add("✅ Nutrition") else add("⬜ Nutrition")
            }
            dataCompleteness.text = "Partial: ${parts.joinToString("  •  ")}"
            dataCompleteness.visibility = View.VISIBLE
        }

        // ── Consumption Frequency ─────────────────────────────────────────
        buildConsumptionFrequency(result)

        // ── Explanation ──────────────────────────────────────────────────────
        buildExplanation(result)

        // ── Sections ─────────────────────────────────────────────────────────
        buildAdditiveSection(result)
        buildNutritionSection(result)

        // ── Scan Again ────────────────────────────────────────────────────────
        scanAgainButton.setOnClickListener {
            ScanSession.clear()
            startActivity(Intent(this, MainActivity::class.java)
                .apply { flags = Intent.FLAG_ACTIVITY_CLEAR_TOP })
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.fade_in, R.anim.slide_down_out)
            finish()
        }

        // ── Share button ──────────────────────────────────────────────────────
        shareButton.setOnClickListener {
            lastResult?.let { r -> ShareHelper.shareResult(this, r) }
        }

        contentScroll.visibility = View.VISIBLE

        // ── Staggered entrance animations ─────────────────────────────────
        playEntranceAnimations()
    }

    // ── Entrance animations ───────────────────────────────────────────────

    private fun playEntranceAnimations() {
        // Verdict hero: scale in with overshoot
        verdictEmoji.scaleX = 0f; verdictEmoji.scaleY = 0f
        verdictEmoji.animate()
            .scaleX(1f).scaleY(1f)
            .setDuration(350).setStartDelay(50)
            .setInterpolator(OvershootInterpolator(1.3f))
            .start()

        verdictLabel.alpha = 0f; verdictLabel.translationY = 12f
        verdictLabel.animate().alpha(1f).translationY(0f)
            .setDuration(250).setStartDelay(80)
            .setInterpolator(DecelerateInterpolator()).start()

        verdictTagline.alpha = 0f
        verdictTagline.animate().alpha(1f)
            .setDuration(200).setStartDelay(130).start()

        // Cards stagger in
        val cards = listOf(frequencyCard, scoreCard, additiveCard, nutritionCard, scanAgainButton as View, shareButton as View)
        cards.forEachIndexed { i, card ->
            card.alpha = 0f
            card.translationY = 24f
            card.animate()
                .alpha(1f).translationY(0f)
                .setDuration(250)
                .setStartDelay(200L + i * 50L)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    // ── Consumption Frequency ──────────────────────────────────────────────

    /**
     * Calculates how often a user can safely eat this product.
     *
     * Algorithm:
     *   1. Start from a base level based on the health verdict
     *   2. Adjust DOWN for bad nutrition (high sugar, fat, sodium)
     *   3. Adjust DOWN for harmful additives
     *   4. Adjust UP for good nutrition (fiber, protein)
     *   5. Adjust UP if zero additives
     *   6. Clamp to range 1-6
     *
     * Levels:
     *   1 = Can eat daily
     *   2 = A few times a week
     *   3 = Once or twice a week
     *   4 = 2-3 times a month
     *   5 = Once a month or less
     *   6 = Best avoided regularly
     */
    private fun buildConsumptionFrequency(result: OfflineAnalyzer.AnalysisResult) {
        var level: Int
        val reasons = mutableListOf<String>()

        // ── Step 1: Base level from verdict ──────────────────────────────────
        level = when (result.verdict.lowercase()) {
            "healthy"   -> 1   // start at daily
            "moderate"  -> 3   // start at once/twice a week
            "unhealthy" -> 5   // start at once a month
            else        -> 3
        }

        // ── Step 2: Adjust based on nutrition ────────────────────────────────
        val n = result.nutrition
        if (n != null) {
            // Bad signals → push DOWN (higher level = worse)
            // Using UK FSA Traffic Light 'Red' (High) thresholds per 100g
            n.sugarsG?.let {
                if (it > 22.5) { level += 1; reasons.add("High sugar (${String.format("%.0f", it)}g)") }
            }
            n.fatG?.let {
                if (it > 17.5) { level += 1; reasons.add("High total fat (${String.format("%.0f", it)}g)") }
            }
            n.sodiumMg?.let {
                if (it > 600.0) { level += 1; reasons.add("High sodium (${String.format("%.0f", it)}mg)") }
            }

            // Good signals → pull UP (lower level = better)
            n.fiberG?.let {
                if (it >= 6.0) { level -= 1; reasons.add("Good fiber content") }
            }
            n.proteinG?.let {
                if (it >= 12.0) { level -= 1; reasons.add("Good protein content") }
            }
        }

        // ── Step 3: Adjust based on additives ───────────────────────────────
        val highSeverity = result.detectedAdditives.count {
            it.severity.lowercase() == "high"
        }
        val moderateSeverity = result.detectedAdditives.count {
            it.severity.lowercase() == "moderate"
        }
        if (highSeverity > 0) {
            level += 1
            reasons.add("$highSeverity harmful additive${if (highSeverity > 1) "s" else ""}")
        }
        if (highSeverity >= 2) {
            level += 1  // extra penalty for multiple harmful additives
        }
        if (moderateSeverity >= 3) {
            level += 1  // penalty for many moderate-risk additives
            reasons.add("$moderateSeverity moderate-risk additives")
        }
        if (result.additiveCount == 0 && result.ingredientsScanned) {
            level -= 1  // reward no additives
            reasons.add("No additives")
        }

        // ── Step 4: Clamp to 1-6 ────────────────────────────────────────────
        level = level.coerceIn(1, 6)

        // ── Step 5: Map to label, icon, color ───────────────────────────────
        data class FreqInfo(val label: String, val icon: String, val color: String)
        val info = when (level) {
            1 -> FreqInfo("Can eat daily",           "🟢", "#16A34A")
            2 -> FreqInfo("A few times a week",      "🟢", "#22C55E")
            3 -> FreqInfo("Once or twice a week",    "🟡", "#D97706")
            4 -> FreqInfo("2–3 times a month",       "🟠", "#EA580C")
            5 -> FreqInfo("Once a month or less",    "🔴", "#DC2626")
            else -> FreqInfo("Best avoided regularly", "⛔", "#991B1B")
        }

        // ── Step 6: Populate UI ──────────────────────────────────────────────
        frequencyIcon.text  = info.icon
        frequencyLabel.text = info.label
        frequencyLabel.setTextColor(Color.parseColor(info.color))

        // Show why this frequency was chosen
        if (reasons.isNotEmpty()) {
            frequencyReason.text = reasons.joinToString(" · ")
            frequencyReason.visibility = View.VISIBLE
        } else {
            frequencyReason.visibility = View.GONE
        }
    }

    // ── Plain-English explanation ──────────────────────────────────────────

    private fun buildExplanation(result: OfflineAnalyzer.AnalysisResult) {
        val sb = StringBuilder()
        val verdict = result.verdict.lowercase()
        val n = result.nutrition

        // Opening line based on verdict
        sb.append(when (verdict) {
            "healthy" -> "This product looks like a good choice. "
            "moderate" -> "This product is okay but has some areas to watch. "
            "unhealthy" -> "This product has several nutritional concerns. "
            else -> "Here's what we found about this product. "
        })

        // Nutrition-based reasoning
        if (n != null) {
            val concerns = mutableListOf<String>()
            val positives = mutableListOf<String>()

            n.energyKcal?.let {
                if (it > 400) concerns.add("calorie-dense (${String.format("%.0f", it)} kcal)")
            }
            n.carbsG?.let {
                if (it > 60) concerns.add("very high in carbs (${String.format("%.1f", it)}g)")
            }
            // Following EU Regulation 1924/2006 & UK FSA Traffic Light standards
            n.sugarsG?.let {
                if (it > 22.5) concerns.add("high in sugar (${String.format("%.1f", it)}g)")
                else if (it <= 0.5) positives.add("sugar-free")
                else if (it <= 5.0) positives.add("low in sugar")
            }
            n.fatG?.let {
                if (it > 17.5) concerns.add("high in fat (${String.format("%.1f", it)}g)")
                else if (it <= 0.5) positives.add("fat-free")
                else if (it <= 3.0) positives.add("low in fat")
            }
            n.sodiumMg?.let {
                if (it > 600) concerns.add("high in sodium (${String.format("%.0f", it)}mg)")
                else if (it <= 5) positives.add("sodium-free")
                else if (it <= 120) positives.add("low in sodium")
            }
            n.fiberG?.let {
                if (it >= 6.0) positives.add("high in fiber (${String.format("%.1f", it)}g)")
                else if (it >= 3.0) positives.add("source of fiber (${String.format("%.1f", it)}g)")
            }
            n.proteinG?.let {
                if (it >= 12.0) positives.add("good source of protein (${String.format("%.1f", it)}g)")
            }

            if (positives.isNotEmpty()) {
                sb.append("It's ${positives.joinToString(" and ")}. ")
            }
            
            if (concerns.isNotEmpty()) {
                sb.append("However, it's ${concerns.joinToString(", ")}. ")
            } else if (verdict == "unhealthy" || verdict == "moderate") {
                // Failsafe: if hardcoded rules found no concerns but AI flagged it, use SHAP!
                val topNegatives = result.shapContributions.filter { it.direction == "negative" }.take(2)
                if (topNegatives.isNotEmpty()) {
                    val reasons = topNegatives.joinToString(" and ") { it.featureName.lowercase() }
                    sb.append("However, the AI model penalized its score primarily due to its $reasons. ")
                }
            }
        }

        // Additive reasoning
        if (result.additiveCount > 0) {
            if (result.harmfulAdditiveCount > 0) {
                sb.append("It contains ${result.additiveCount} additive${if (result.additiveCount > 1) "s" else ""}, " +
                        "${result.harmfulAdditiveCount} of which may be concerning. ")
            } else {
                sb.append("It has ${result.additiveCount} additive${if (result.additiveCount > 1) "s" else ""}, " +
                        "but none are considered harmful. ")
            }
        } else {
            sb.append("No concerning additives were found. ")
        }

        // Closing advice
        sb.append(when (verdict) {
            "healthy" -> "Overall, a solid pick for a balanced diet."
            "moderate" -> "Fine as an occasional choice — just don't overdo it."
            "unhealthy" -> "Best consumed sparingly or look for healthier alternatives."
            else -> ""
        })

        explanationText.text = sb.toString().trim()
    }

    // ── Auto-save to Room history ──────────────────────────────────────────────

    private fun autoSaveToHistory(result: OfflineAnalyzer.AnalysisResult) {
        if (intent.getBooleanExtra("fromHistory", false)) return // Don't save duplicates
        
        val jsonString = try {
            com.google.gson.Gson().toJson(result)
        } catch (e: Exception) {
            android.util.Log.e("MacroMind", "Gson serialization failed: ${e.message}")
            ""
        }

        lifecycleScope.launch {
            try {
                val entity = ScanHistoryEntity(
                    category             = result.category,
                    verdict              = result.verdict,
                    confidence           = result.confidence,
                    additiveCount        = result.additiveCount,
                    harmfulAdditiveCount = result.harmfulAdditiveCount,
                    ingredientPreview    = result.rawIngredients.take(200),
                    categoryConfidence   = result.categoryConfidence,
                    ingredientsScanned   = result.ingredientsScanned,
                    nutritionScanned     = result.nutritionScanned,
                    rawJson              = jsonString
                )
                AppDatabase.get(this@ResultsActivity).scanHistoryDao().insert(entity)
            } catch (e: Exception) {
                android.util.Log.e("MacroMind", "Failed to save history: ${e.message}")
            }
        }
    }

    // ── Additives section — SIMPLIFIED ────────────────────────────────────────

    private fun buildAdditiveSection(result: OfflineAnalyzer.AnalysisResult) {
        additiveContainer.removeAllViews()
        val count = result.additiveCount

        if (count == 0) {
            additiveTitle.text = "Additives"
            val noAdd = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = makeRoundedBg("#F0FDF4", 10f)
                setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8))
            }
            noAdd.addView(TextView(this).apply {
                text = "✅  No concerning additives detected"
                textSize = 13f; setTextColor(Color.parseColor("#16A34A"))
            })
            additiveContainer.addView(noAdd)
            return
        }

        additiveTitle.text = when {
            result.harmfulAdditiveCount > 0 -> "Additives  ·  ${result.harmfulAdditiveCount} of concern"
            else -> "Additives  ·  $count found"
        }

        // Show each additive with its info
        result.detectedAdditives.forEach { additive ->
            val sevColor = when (additive.severity.lowercase()) {
                "high" -> "#DC2626"; "moderate" -> "#D97706"; "minor" -> "#CA8A04"; else -> "#16A34A"
            }
            val sevBgColor = when (additive.severity.lowercase()) {
                "high" -> "#FEF2F2"; "moderate" -> "#FFFBEB"; "minor" -> "#FEFCE8"; else -> "#F0FDF4"
            }
            val sevDot = when (additive.severity.lowercase()) {
                "high" -> "🔴"; "moderate" -> "🟡"; "minor" -> "🟡"; else -> "🟢"
            }

            // Additive card wrapper
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = makeRoundedBg(sevBgColor, 8f)
                setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, dpToPx(4), 0, dpToPx(4)) }
            }

            // ── Row 1: Name + severity badge ──
            val headerRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val nameText = if (additive.code.isNotBlank())
                "$sevDot  ${additive.code} — ${additive.name}"
            else
                "$sevDot  ${additive.name}"

            headerRow.addView(TextView(this).apply {
                text = nameText
                textSize = 13f
                setTextColor(Color.parseColor("#1A1D26"))
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })

            // Severity badge
            if (additive.severity.lowercase() in listOf("high", "moderate")) {
                val badgeLabel = if (additive.severity.lowercase() == "high") "Avoid" else "Caution"
                headerRow.addView(TextView(this).apply {
                    text = badgeLabel
                    textSize = 10f; setTextColor(Color.WHITE)
                    background = makeRoundedBg(sevColor, 6f)
                    setPadding(dpToPx(6), dpToPx(2), dpToPx(6), dpToPx(2))
                })
            }

            card.addView(headerRow)

            // ── Row 2: Category label ──
            if (additive.category.isNotBlank() && additive.category != "Unknown") {
                card.addView(TextView(this).apply {
                    text = additive.category
                    textSize = 11f
                    setTextColor(Color.parseColor("#6B7280"))
                    setPadding(dpToPx(20), dpToPx(2), 0, 0)
                })
            }

            // ── Row 3: Health impact (side effects) ──
            if (additive.healthImpact.isNotBlank()) {
                card.addView(TextView(this).apply {
                    text = "⚕️ ${additive.healthImpact}"
                    textSize = 12f
                    setTextColor(Color.parseColor("#4B5563"))
                    setPadding(dpToPx(20), dpToPx(4), 0, 0)
                    setLineSpacing(0f, 1.2f)
                })
            }

            // ── Row 4: Common foods ──
            if (additive.commonFoods.isNotBlank()) {
                card.addView(TextView(this).apply {
                    text = "📦 Found in: ${additive.commonFoods}"
                    textSize = 11f
                    setTextColor(Color.parseColor("#9CA3AF"))
                    setPadding(dpToPx(20), dpToPx(3), 0, 0)
                })
            }

            additiveContainer.addView(card)
        }
    }

    // ── Nutrition section — clean rows ─────────────────────────────────────────

    private fun buildNutritionSection(result: OfflineAnalyzer.AnalysisResult) {
        nutritionContainer.removeAllViews()
        val n = result.nutrition
        if (n == null) {
            nutritionContainer.addView(TextView(this).apply {
                text = "No nutrition data was scanned."
                setTextColor(Color.parseColor("#9CA3AF"))
                textSize = 12f
            })
            return
        }

        data class NRow(val label: String, val value: String, val unit: String,
                        val amount: Float, val maxRef: Float, val color: String)

        val rows = listOfNotNull(
            n.energyKcal?.let  { NRow("Energy",       "%.0f".format(it), "kcal", it.toFloat(), 2000f, "#D97706") },
            n.proteinG?.let    { NRow("Protein",      "%.1f".format(it), "g",    it.toFloat(), 50f,   "#2563EB") },
            n.carbsG?.let      { NRow("Carbs",        "%.1f".format(it), "g",    it.toFloat(), 260f,  "#D97706") },
            n.sugarsG?.let     { NRow("Sugar",        "%.1f".format(it), "g",    it.toFloat(), 50f,   "#DC2626") },
            n.fatG?.let        { NRow("Fat",          "%.1f".format(it), "g",    it.toFloat(), 70f,   "#CA8A04") },
            n.fiberG?.let      { NRow("Fiber",        "%.1f".format(it), "g",    it.toFloat(), 30f,   "#16A34A") },
            n.sodiumMg?.let    { NRow("Sodium",       "%.0f".format(it), "mg",   it.toFloat(), 2300f, "#7C3AED") }
        )

        rows.forEachIndexed { index, row ->
            val fraction = (row.amount / row.maxRef).coerceIn(0f, 1f)
            val pct = (fraction * 100).toInt()

            // Row wrapper
            val rowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dpToPx(4), 0, dpToPx(4))
            }

            // Label + value on same line
            val labelRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            labelRow.addView(TextView(this).apply {
                text = row.label; textSize = 13f; setTextColor(Color.parseColor("#4B5563"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            labelRow.addView(TextView(this).apply {
                text = "${row.value} ${row.unit}"
                textSize = 13f; setTextColor(Color.parseColor("#1A1D26"))
                typeface = Typeface.DEFAULT_BOLD
            })
            labelRow.addView(TextView(this).apply {
                text = "  ${pct}%"
                textSize = 10f; setTextColor(Color.parseColor("#9CA3AF"))
            })
            rowLayout.addView(labelRow)

            // Thin animated bar
            val targetWidth = (fraction * resources.displayMetrics.widthPixels * 0.65f).toInt()
            val barBg = LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(3)).apply {
                    setMargins(0, dpToPx(3), 0, 0)
                }
                background = makeRoundedBg("#E5E7EB", 2f)
            }
            val fill = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, dpToPx(3))
                background = makeRoundedBg(row.color, 2f)
            }
            barBg.addView(fill)
            rowLayout.addView(barBg)

            nutritionContainer.addView(rowLayout)

            // Divider (except last)
            if (index < rows.size - 1) {
                nutritionContainer.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                        setMargins(0, dpToPx(2), 0, dpToPx(2))
                    }
                    setBackgroundColor(Color.parseColor("#F0F1F5"))
                })
            }

            // Animate bar
            fill.pivotX = 0f
            fill.scaleX = 0f
            fill.post {
                fill.layoutParams.width = targetWidth
                fill.requestLayout()
                fill.scaleX = 0f
                fill.animate()
                    .scaleX(1f)
                    .setDuration(400)
                    .setStartDelay(400L + index * 40L)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Create a rounded rectangle background programmatically */
    private fun makeRoundedBg(colorHex: String, radiusDp: Float): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(colorHex))
            cornerRadius = dpF(radiusDp)
        }
    }

    private fun dpToPx(dp: Int) = (dp * resources.displayMetrics.density).toInt()
    private fun dpF(dp: Float) = dp * resources.displayMetrics.density
    private fun dpF(dp: Int) = dp.toFloat() * resources.displayMetrics.density
    private fun formatPct(f: Float) = "${(f * 100).toInt()}%"

    private fun verdictTagline(verdict: String, confidence: Float): String {
        val pct = (confidence * 100).toInt()
        return when (verdict.lowercase()) {
            "healthy" -> when {
                pct >= 80 -> "Great choice — this food scores well overall"
                pct >= 60 -> "Generally a healthy option"
                else      -> "Leans healthy, but check the details below"
            }
            "moderate" -> when {
                pct >= 80 -> "Okay in moderation — not the best, not the worst"
                else      -> "Mixed signals — read the breakdown below"
            }
            "unhealthy" -> when {
                pct >= 80 -> "High in things that aren't great for regular consumption"
                else      -> "This product has some concerns — see below"
            }
            else -> "See the breakdown below"
        }
    }

    // ── Loading / Error ───────────────────────────────────────────────────────

    private fun showLoading(message: String) {
        loadingText.text          = message
        loadingOverlay.visibility = View.VISIBLE
        contentScroll.visibility  = View.GONE
    }

    private fun hideLoading() {
        // Fade out loading overlay
        loadingOverlay.animate().alpha(0f).setDuration(250)
            .withEndAction { loadingOverlay.visibility = View.GONE }
            .start()
    }

    private fun showError(message: String) {
        hideLoading()
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }

    // ── View binding ──────────────────────────────────────────────────────────

    private fun bindViews() {
        loadingOverlay     = findViewById(R.id.loadingOverlay)
        loadingText        = findViewById(R.id.loadingText)
        contentScroll      = findViewById(R.id.contentScroll)
        verdictCard        = findViewById(R.id.verdictCard)
        verdictEmoji       = findViewById(R.id.verdictEmoji)
        verdictLabel       = findViewById(R.id.verdictLabel)
        verdictTagline     = findViewById(R.id.verdictTagline)
        verdictConfidence  = findViewById(R.id.verdictConfidence)
        scoreRing          = findViewById(R.id.scoreRing)
        categoryBadge      = findViewById(R.id.categoryBadge)
        dataCompleteness   = findViewById(R.id.dataCompleteness)
        explanationText    = findViewById(R.id.explanationText)
        additiveTitle      = findViewById(R.id.additiveTitle)
        additiveContainer  = findViewById(R.id.additiveContainer)
        nutritionContainer  = findViewById(R.id.nutritionContainer)
        scanAgainButton     = findViewById(R.id.scanAgainButton)
        shareButton         = findViewById(R.id.shareButton)
        frequencyCard       = findViewById(R.id.frequencyCard)
        frequencyIcon       = findViewById(R.id.frequencyIcon)
        frequencyLabel      = findViewById(R.id.frequencyLabel)
        frequencyReason     = findViewById(R.id.frequencyReason)

        // Card wrappers for stagger animation
        scoreCard    = findViewById(R.id.scoreCard)
        additiveCard = findViewById(R.id.additiveCardWrapper)
        nutritionCard= findViewById(R.id.nutritionCardWrapper)
    }
}