package com.macromind.foodscanner

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * CropActivity.kt
 *
 * Receives a Bitmap (from camera or gallery) via CropActivity.pendingBitmap.
 * Shows the image with an interactive crop overlay.
 * Lets the user scan the INGREDIENTS section and NUTRITION section separately.
 * Stores results in ScanSession and sends them to the backend when both are ready.
 *
 * Flow:
 *   1. Image displayed in ImageView.
 *   2. User selects mode (📋 Ingredients / 🥗 Nutrition) via toggle buttons.
 *   3. User draws a crop box over the relevant section.
 *   4. Taps "✓ Scan" → crop → preprocess → OCR → parse → store in ScanSession.
 *   5. Dialog shows result and progress.
 *   6. When both sections are done → "🚀 Analyze & Send" calls sendToBackend().
 */

class CropActivity : AppCompatActivity() {

    // ── Views ──────────────────────────────────────────────────────────────
    private lateinit var capturedImageView:  ImageView
    private lateinit var cropOverlayView:    CropOverlayView
    private lateinit var processingOverlay:  View
    private lateinit var processingText:     TextView
    private lateinit var analyzeButton:      Button
    private lateinit var retakeButton:       Button
    private lateinit var resetCropButton:    TextView        // now a floating TextView, not a Button
    private lateinit var typeManuallyButton: Button
    private lateinit var btnIngredients:     Button
    private lateinit var btnNutrition:       Button
    private lateinit var modeTitleText:      TextView
    private lateinit var sessionStatusText:  TextView

    // The full image loaded from camera or gallery
    private var fullBitmap: Bitmap? = null

    companion object {
        /**
         * CameraActivity stores the bitmap here before launching CropActivity.
         * We clear it immediately after loading so there is no static leak.
         */
        var pendingBitmap: Bitmap? = null
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_crop)

        // Edge-to-edge immersive layout
        WindowCompat.setDecorFitsSystemWindows(window, false)

        bindViews()
        loadBitmap()
        setListeners()
        refreshModeUI()
        refreshSessionStatus()
        playEntranceAnimation()
    }

    override fun onDestroy() {
        super.onDestroy()
        recycleBitmap()
    }

    // ── Setup ──────────────────────────────────────────────────────────────
    private fun bindViews() {
        capturedImageView  = findViewById(R.id.capturedImageView)
        cropOverlayView    = findViewById(R.id.cropOverlayView)
        processingOverlay  = findViewById(R.id.processingOverlay)
        processingText     = findViewById(R.id.processingText)
        analyzeButton      = findViewById(R.id.analyzeButton)
        retakeButton       = findViewById(R.id.retakeButton)
        resetCropButton    = findViewById(R.id.resetCropButton)   // TextView in new layout
        typeManuallyButton = findViewById(R.id.typeManuallyButton)
        btnIngredients     = findViewById(R.id.btnIngredients)
        btnNutrition       = findViewById(R.id.btnNutrition)
        modeTitleText      = findViewById(R.id.modeTitleText)
        sessionStatusText  = findViewById(R.id.sessionStatusText)
    }

    private fun loadBitmap() {
        val bmp = pendingBitmap
        if (bmp == null || bmp.isRecycled) {
            Toast.makeText(this, "No image found. Please retake.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        fullBitmap    = bmp
        pendingBitmap = null                        // clear static ref — we now own it
        capturedImageView.setImageBitmap(bmp)
    }

    private fun setListeners() {
        retakeButton.setOnClickListener {
            recycleBitmap()
            finish()
        }

        resetCropButton.setOnClickListener {
            cropOverlayView.resetCrop()
        }

        typeManuallyButton.setOnClickListener {
            showManualEntryDialog()
        }

        analyzeButton.setOnClickListener {
            startAnalysis()
        }

        btnIngredients.setOnClickListener {
            ScanSession.currentMode = ScanSession.ScanMode.INGREDIENTS
            refreshModeUI()
        }

        btnNutrition.setOnClickListener {
            ScanSession.currentMode = ScanSession.ScanMode.NUTRITION
            refreshModeUI()
        }
    }

    // ── Mode UI ────────────────────────────────────────────────────────────
    private fun refreshModeUI() {
        val isIngr = ScanSession.currentMode == ScanSession.ScanMode.INGREDIENTS

        // Active tab: purple fill + white text. Inactive: transparent + dim text.
        btnIngredients.backgroundTintList = colorState(if (isIngr) "#2563EB" else "#00000000")
        btnIngredients.setTextColor(android.graphics.Color.parseColor(if (isIngr) "#FFFFFF" else "#88FFFFFF"))

        btnNutrition.backgroundTintList = colorState(if (!isIngr) "#2563EB" else "#00000000")
        btnNutrition.setTextColor(android.graphics.Color.parseColor(if (!isIngr) "#FFFFFF" else "#88FFFFFF"))

        modeTitleText.text = if (isIngr)
            "Draw a box over the INGREDIENTS section"
        else
            "Draw a box over the NUTRITION TABLE"

        analyzeButton.text = if (isIngr) "✓  Scan Ingredients" else "✓  Scan Nutrition"
    }

    private fun refreshSessionStatus() {
        val parts = mutableListOf<String>()
        if (ScanSession.hasIngredients) parts.add("✅ Ingredients")
        if (ScanSession.hasNutrition)   parts.add("✅ Nutrition")
        sessionStatusText.text = if (parts.isEmpty())
            "Scan each section separately for best results"
        else
            "Collected: ${parts.joinToString("  •  ")}"
    }

    private fun colorState(hex: String) =
        android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor(hex))

    // ── Step 1: Crop the bitmap ────────────────────────────────────────────
    private fun startAnalysis() {
        val bitmap = fullBitmap ?: run {
            Toast.makeText(this, "No image loaded.", Toast.LENGTH_SHORT).show()
            return
        }

        showProcessing("Cropping image...")

        val cropRect = cropOverlayView.getCropRectForBitmap(bitmap)

        if (cropRect.width() < 10 || cropRect.height() < 10) {
            hideProcessing()
            Toast.makeText(this, "Selection is too small. Draw a larger box.", Toast.LENGTH_SHORT).show()
            return
        }

        val cropped = try {
            Bitmap.createBitmap(
                bitmap,
                cropRect.left, cropRect.top,
                cropRect.width(), cropRect.height()
            )
        } catch (e: Exception) {
            hideProcessing()
            Log.e("MacroMind", "Crop failed: ${e.message}")
            Toast.makeText(this, "Crop failed. Try resizing the box.", Toast.LENGTH_SHORT).show()
            return
        }

        // Step 2: preprocess
        showProcessing("Preparing image...")
        val processed = ImageProcessor.prepareForOCR(cropped)
        if (processed !== cropped) cropped.recycle()

        // Step 3: OCR
        showProcessing("Reading text...")
        runOCR(processed)
    }

    // ── Step 2: Run OCR on the preprocessed crop ───────────────────────────
    private fun runOCR(bitmap: Bitmap) {
        val inputImage = InputImage.fromBitmap(bitmap, 0)

        // Always create a fresh recognizer — reusing accumulates state
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        recognizer.process(inputImage)
            .addOnSuccessListener { result ->
                bitmap.recycle()        // free crop bitmap immediately
                recognizer.close()      // free recognizer immediately

                // ── Assemble text in correct reading order ──────────────────
                // ML Kit sometimes returns text blocks in random or reverse
                // order depending on image orientation. Sort all blocks by
                // their Y position (top→bottom) to guarantee correct order.
                val sortedText = result.textBlocks
                    .sortedBy { it.boundingBox?.top ?: 0 }
                    .joinToString("\n") { block ->
                        // Within each block, lines are also sorted top→bottom
                        block.lines
                            .sortedBy { it.boundingBox?.top ?: 0 }
                            .joinToString("\n") { it.text }
                    }

                val text = sortedText
                DebugHelper.showOcrResult(this, text, ScanSession.currentMode.name)
                Log.d("MacroMind", "OCR [${ScanSession.currentMode}]:\n$text")

                if (text.isBlank()) {
                    hideProcessing()
                    showNoTextDialog()
                    return@addOnSuccessListener
                }

                showProcessing("Parsing...")
                parseAndStore(text)
            }
            .addOnFailureListener { e ->
                bitmap.recycle()
                recognizer.close()
                hideProcessing()
                Log.e("MacroMind", "OCR error: ${e.message}")
                Toast.makeText(this, "OCR failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    // ── Step 3: Parse and store in ScanSession ─────────────────────────────
    private fun parseAndStore(rawText: String) {
        when (ScanSession.currentMode) {

            ScanSession.ScanMode.INGREDIENTS -> {
                val result = TextParser.extractIngredients(rawText)
                hideProcessing()
                // Show editable dialog so user can correct OCR errors
                showIngredientsEditDialog(result ?: rawText)
            }

            ScanSession.ScanMode.NUTRITION -> {
                val result = NutritionExtractor.extractNutrition(rawText)
                // Don't store in ScanSession yet — let user verify & edit first
                hideProcessing()
                showNutritionEditDialog(result)
            }
        }
        refreshSessionStatus()
    }

    private fun buildNutritionPreview(n: NutritionExtractor.NutritionData): String {
        val sb = StringBuilder()
        n.energyKcal?.let    { sb.appendLine("Energy  : ${"%.0f".format(it)} kcal") }
        n.fatG?.let          { sb.appendLine("Fat     : ${"%.1f".format(it)} g") }
        n.carbsG?.let        { sb.appendLine("Carbs   : ${"%.1f".format(it)} g") }
        n.sugarsG?.let       { sb.appendLine("  Sugar : ${"%.1f".format(it)} g") }
        n.fiberG?.let        { sb.appendLine("Fiber   : ${"%.1f".format(it)} g") }
        n.proteinG?.let      { sb.appendLine("Protein : ${"%.1f".format(it)} g") }
        n.sodiumMg?.let      { sb.appendLine("Sodium  : ${"%.0f".format(it)} mg") }
        return sb.toString().trim().ifBlank {
            "Could not detect values.\nTry cropping closer to the nutrition table."
        }
    }

    // ── Editable Ingredients Form ───────────────────────────────────────────
    /**
     * Shows a dialog with the scanned ingredients text in an editable field.
     * User can correct OCR errors before saving and moving to nutrition scan.
     */
    private fun showIngredientsEditDialog(scannedText: String) {
        val ctx = this
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }

        // ── Root container ──
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            setBackgroundColor(Color.parseColor("#0F0F1A"))
        }

        // Title
        root.addView(TextView(ctx).apply {
            text = "📋  Scanned Ingredients"
            textSize = 17f
            setTextColor(Color.parseColor("#00E5FF"))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(4))
        })

        // Subtitle
        root.addView(TextView(ctx).apply {
            text = "Review & correct the scanned text below"
            textSize = 12f
            setTextColor(Color.parseColor("#888888"))
            setPadding(0, 0, 0, dp(12))
        })

        // Editable text field
        val editText = EditText(ctx).apply {
            setText(scannedText)
            textSize = 14f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#555555"))
            hint = "e.g. Wheat flour (70%), Sugar, Edible vegetable oil, Salt, E330..."
            setBackgroundColor(Color.parseColor("#1A1A2E"))
            minLines = 5
            maxLines = 10
            gravity = android.view.Gravity.TOP
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                        android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setLineSpacing(0f, 1.3f)
        }
        root.addView(editText)

        // ── "Save & Scan Nutrition" button ──
        val saveBtn = Button(ctx).apply {
            text = if (ScanSession.hasNutrition) "✅  Save & Analyze" else "🥗  Save & Scan Nutrition"
            textSize = 15f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                Color.parseColor("#2563EB"))
            stateListAnimator = null
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)
            ).apply { setMargins(0, dp(14), 0, dp(4)) }
        }
        root.addView(saveBtn)

        // ── "Re-scan" link ──
        val rescanBtn = TextView(ctx).apply {
            text = "🔄 Re-scan"
            textSize = 13f
            setTextColor(Color.parseColor("#2563EB"))
            gravity = android.view.Gravity.CENTER
            setPadding(0, dp(8), 0, dp(4))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        root.addView(rescanBtn)

        // Wrap in ScrollView
        val scroll = ScrollView(ctx).apply { addView(root) }

        // Show as dialog
        val dialog = AlertDialog.Builder(ctx)
            .setView(scroll)
            .setCancelable(false)
            .create()

        saveBtn.setOnClickListener {
            val corrected = editText.text.toString().trim()
            if (corrected.isBlank()) {
                Toast.makeText(ctx, "Please enter ingredients text.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            ScanSession.ingredients = corrected
            refreshSessionStatus()
            dialog.dismiss()

            if (ScanSession.hasNutrition) {
                // Both done — go to results
                sendToBackend()
            } else {
                // Switch to nutrition mode
                ScanSession.currentMode = ScanSession.ScanMode.NUTRITION
                refreshModeUI()
                cropOverlayView.resetCrop()
            }
        }

        rescanBtn.setOnClickListener {
            dialog.dismiss()
            cropOverlayView.resetCrop()
        }

        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    }

    // ── Editable Nutrition Form ────────────────────────────────────────────
    /**
     * Shows a dialog with 7 editable fields (matching health model inputs)
     * pre-filled from OCR results. User can correct values before analysis.
     */
    private fun showNutritionEditDialog(data: NutritionExtractor.NutritionData) {
        val ctx = this
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }

        // Auto-count additives from scanned ingredients
        val autoAdditiveCount = if (ScanSession.hasIngredients) {
            IngredientParser.detect(ScanSession.ingredients ?: "").size
        } else { 0 }

        // ── Root container ──
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            setBackgroundColor(Color.parseColor("#0F0F1A"))
        }

        // Title
        root.addView(TextView(ctx).apply {
            text = "🥗  Nutrition Facts (per 100g)"
            textSize = 17f
            setTextColor(Color.parseColor("#00E5FF"))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(4))
        })

        // Subtitle
        root.addView(TextView(ctx).apply {
            text = "Verify & correct the scanned values below"
            textSize = 12f
            setTextColor(Color.parseColor("#888888"))
            setPadding(0, 0, 0, dp(16))
        })

        // ── Field builder ──
        val editTexts = mutableListOf<EditText>()

        fun makeField(label: String, value: String): View {
            val wrapper = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ).apply { setMargins(0, 0, dp(8), 0) }
            }
            wrapper.addView(TextView(ctx).apply {
                text = label
                textSize = 10f
                setTextColor(Color.parseColor("#AAAAAA"))
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setPadding(dp(2), 0, 0, dp(4))
            })
            val et = EditText(ctx).apply {
                setText(value)
                textSize = 15f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#1A1A2E"))
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                            android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                isSingleLine = true
            }
            editTexts.add(et)
            wrapper.addView(et)
            return wrapper
        }

        fun makeRow(): LinearLayout {
            return LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, dp(10)) }
            }
        }

        // ── Row 1: Fat, Carbs, Sugars ──
        val row1 = makeRow()
        row1.addView(makeField("FAT (G)", "%.1f".format(data.fatG ?: 0.0)))
        row1.addView(makeField("CARBS (G)", "%.1f".format(data.carbsG ?: 0.0)))
        row1.addView(makeField("SUGARS (G)", "%.1f".format(data.sugarsG ?: 0.0)))
        root.addView(row1)

        // ── Row 2: Fiber, Protein ──
        val row2 = makeRow()
        row2.addView(makeField("FIBER (G)", "%.1f".format(data.fiberG ?: 0.0)))
        row2.addView(makeField("PROTEIN (G)", "%.1f".format(data.proteinG ?: 0.0)))
        root.addView(row2)

        // ── Row 3: Sodium, Additives ──
        val row3 = makeRow()
        row3.addView(makeField("SODIUM (MG)", "%.0f".format(data.sodiumMg ?: 0.0)))
        row3.addView(makeField("ADDITIVES (#)", "$autoAdditiveCount"))
        root.addView(row3)

        // ── "Run Full Analysis" button ──
        val analyzeBtn = Button(ctx).apply {
            text = "🚀  Run Full Analysis"
            textSize = 15f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                Color.parseColor("#2563EB"))
            stateListAnimator = null
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)
            ).apply { setMargins(0, dp(8), 0, dp(4)) }
        }
        root.addView(analyzeBtn)

        // ── "Re-scan" link ──
        val rescanBtn = TextView(ctx).apply {
            text = "🔄 Re-scan"
            textSize = 13f
            setTextColor(Color.parseColor("#2563EB"))
            gravity = android.view.Gravity.CENTER
            setPadding(0, dp(8), 0, dp(4))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        root.addView(rescanBtn)

        // Wrap in ScrollView
        val scroll = ScrollView(ctx).apply { addView(root) }

        // Show as dialog
        val dialog = AlertDialog.Builder(ctx)
            .setView(scroll)
            .setCancelable(false)
            .create()

        analyzeBtn.setOnClickListener {
            // Read corrected values from EditTexts
            // Order: [0]=fat, [1]=carbs, [2]=sugars, [3]=fiber, [4]=protein, [5]=sodium
            val correctedNutrition = NutritionExtractor.NutritionData(
                energyKcal    = data.energyKcal,      // preserve original OCR value
                fatG          = editTexts[0].text.toString().toDoubleOrNull() ?: 0.0,
                saturatedFatG = data.saturatedFatG,    // preserve original OCR value
                carbsG        = editTexts[1].text.toString().toDoubleOrNull() ?: 0.0,
                sugarsG       = editTexts[2].text.toString().toDoubleOrNull() ?: 0.0,
                fiberG        = editTexts[3].text.toString().toDoubleOrNull() ?: 0.0,
                proteinG      = editTexts[4].text.toString().toDoubleOrNull() ?: 0.0,
                sodiumMg      = editTexts[5].text.toString().toDoubleOrNull() ?: 0.0
            )
            ScanSession.nutrition = correctedNutrition
            refreshSessionStatus()
            dialog.dismiss()
            sendToBackend()
        }

        rescanBtn.setOnClickListener {
            dialog.dismiss()
            cropOverlayView.resetCrop()
        }

        dialog.show()

        // Transparent background so our dark custom layout shows cleanly
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    }

    // ── Result dialog after each scan ──────────────────────────────────────
    private fun showResultDialog(
        sectionTitle: String,
        found: Boolean,
        preview: String,
        nextMode: ScanSession.ScanMode,
        nextBtnLabel: String
    ) {
        val progress = buildString {
            appendLine(if (ScanSession.hasIngredients) "✅ Ingredients" else "⬜ Ingredients — not yet scanned")
            append(    if (ScanSession.hasNutrition)   "✅ Nutrition"   else "⬜ Nutrition — not yet scanned")
        }

        val message = "${if (found) "✅ Success!" else "⚠️ Not fully detected"}\n\n" +
                "$preview\n\n" +
                "─────────────────\n" +
                progress

        val builder = AlertDialog.Builder(this)
            .setTitle(sectionTitle)
            .setMessage(message)
            .setCancelable(false)

        if (ScanSession.isComplete) {
            // Both sections collected — offer to send
            builder.setPositiveButton("🚀 Analyze & Send") { _, _ ->
                sendToBackend()
            }
            builder.setNeutralButton("✂ Re-scan a Section") { _, _ ->
                // User stays on screen and chooses mode manually
            }
            builder.setNegativeButton("🔄 New Scan") { _, _ ->
                ScanSession.clear()
                recycleBitmap()
                finish()
            }
        } else {
            // One section still missing
            builder.setPositiveButton(nextBtnLabel) { _, _ ->
                // Switch mode and re-crop the SAME photo
                ScanSession.currentMode = nextMode
                refreshModeUI()
                cropOverlayView.resetCrop()
            }
            builder.setNeutralButton("📷 Flip to Other Side") { _, _ ->
                // Session is preserved — go back to camera for the other side
                recycleBitmap()
                finish()
            }
            // Allow partial send if user can't scan one section
            builder.setNegativeButton("Send Partial →") { _, _ ->
                sendToBackend()
            }
        }

        builder.show()
    }

    // ── Manual text entry ──────────────────────────────────────────────────
    private fun showManualEntryDialog() {
        if (ScanSession.currentMode == ScanSession.ScanMode.NUTRITION) {
            // Show the structured nutrition edit form with empty values
            showNutritionEditDialog(NutritionExtractor.NutritionData())
        } else {
            // Show the ingredients edit dialog with existing text or empty
            showIngredientsEditDialog(ScanSession.ingredients ?: "")
        }
    }

    // ── Send to results ─────────────────────────────────────────
    private fun sendToBackend() {
        val intent = Intent(this, ResultsActivity::class.java)
        startActivity(intent)
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.slide_up_in, R.anim.fade_out)
        finish()
    }

    // ── Entrance animation ──────────────────────────────────────
    private fun playEntranceAnimation() {
        val topBar    = findViewById<View>(R.id.topBar)
        val bottomBar = findViewById<View>(R.id.bottomBar)

        topBar.alpha = 0f
        topBar.translationY = -60f
        topBar.animate().alpha(1f).translationY(0f)
            .setDuration(400).setStartDelay(100)
            .setInterpolator(DecelerateInterpolator()).start()

        bottomBar.alpha = 0f
        bottomBar.translationY = 80f
        bottomBar.animate().alpha(1f).translationY(0f)
            .setDuration(400).setStartDelay(200)
            .setInterpolator(DecelerateInterpolator()).start()
    }

    // ── UI helpers ─────────────────────────────────────────────────────────
    private fun showProcessing(message: String) {
        runOnUiThread {
            processingText.text          = message
            processingOverlay.visibility = View.VISIBLE
            analyzeButton.isEnabled      = false
        }
    }

    private fun hideProcessing() {
        runOnUiThread {
            processingOverlay.visibility = View.GONE
            analyzeButton.isEnabled      = true
        }
    }

    private fun showNoTextDialog() {
        AlertDialog.Builder(this)
            .setTitle("No Text Detected")
            .setMessage(
                "Nothing could be read in the selected area.\n\n" +
                        "Tips:\n" +
                        "• Crop tightly around the text\n" +
                        "• Make sure lighting is good\n" +
                        "• Hold the phone steady"
            )
            .setPositiveButton("Try Again") { _, _ -> }
            .show()
    }

    // ── Memory ─────────────────────────────────────────────────────────────
    private fun recycleBitmap() {
        fullBitmap?.let { if (!it.isRecycled) it.recycle() }
        fullBitmap = null
    }

}
