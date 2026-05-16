package com.macromind.foodscanner

import android.os.Bundle
import android.widget.TextView
import android.widget.ScrollView
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

/**
 * TEMPORARY TEST ACTIVITY
 * Use this to verify all assets load correctly before running the main app
 * 
 * To use:
 * 1. Add this activity to AndroidManifest.xml
 * 2. Set it as launcher temporarily
 * 3. Run app and check the output
 * 4. Once verified, remove or set MainActivity back as launcher
 */
class AssetTestActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Create simple UI
        val scrollView = ScrollView(this)
        val textView = TextView(this).apply {
            textSize = 12f
            setPadding(16, 16, 16, 16)
            setTextIsSelectable(true)
        }
        scrollView.addView(textView)
        setContentView(scrollView)

        // Run tests
        val results = StringBuilder()
        results.appendLine("MacroMind Asset Test")
        results.appendLine("=".repeat(50))
        results.appendLine()

        // Test 1: List all assets
        results.appendLine("TEST 1: Listing all files in assets/")
        results.appendLine("-".repeat(50))
        try {
            val allFiles = assets.list("") ?: emptyArray()
            results.appendLine("Total files found: ${allFiles.size}")
            results.appendLine()

            if (allFiles.isEmpty()) {
                results.appendLine("⚠️ WARNING: No files found!")
                results.appendLine("Make sure files are in app/src/main/assets/")
            } else {
                allFiles.sorted().forEach { filename ->
                    results.appendLine("  ✓ $filename")
                }
            }
        } catch (e: Exception) {
            results.appendLine("✗ ERROR: ${e.message}")
        }
        results.appendLine()

        // Test 2: Check required files
        results.appendLine("TEST 2: Checking required files")
        results.appendLine("-".repeat(50))

        val requiredFiles = listOf(
            "model_config.json",
            "food_gap_model.tflite",
            "food_health_model.tflite",
            "vocab_GAP1.json",
            "metadata_GAP1.json",
            "health_model_metadata.json",
            "additive_database.json",
            "shap_background.json"
        )

        var allFound = true
        requiredFiles.forEach { filename ->
            val exists = try {
                assets.open(filename).use { true }
            } catch (e: Exception) {
                false
            }

            val status = if (exists) "✓ FOUND" else "✗ MISSING"
            results.appendLine("  $status - $filename")
            if (!exists) allFound = false
        }
        results.appendLine()

        // Test 3: Load and parse model_config.json
        results.appendLine("TEST 3: Loading model_config.json")
        results.appendLine("-".repeat(50))

        try {
            val configContent = assets.open("model_config.json")
                .bufferedReader()
                .use { it.readText() }

            results.appendLine("✓ File opened successfully")
            results.appendLine("  Size: ${configContent.length} characters")
            results.appendLine()

            // Parse JSON
            val json = JSONObject(configContent)
            results.appendLine("✓ Valid JSON format")
            results.appendLine()

            // Check structure
            results.appendLine("JSON Structure:")

            // Category model
            if (json.has("category_model")) {
                val catModel = json.getJSONObject("category_model")
                results.appendLine("  ✓ category_model:")
                results.appendLine("    - input_text_length: ${catModel.getInt("input_text_length")}")
                results.appendLine("    - num_categories: ${catModel.getInt("num_categories")}")
            } else {
                results.appendLine("  ✗ category_model: MISSING")
            }
            results.appendLine()

            // Health model
            if (json.has("health_model")) {
                val healthModel = json.getJSONObject("health_model")
                results.appendLine("  ✓ health_model:")
                results.appendLine("    - input_size: ${healthModel.getInt("input_size")}")
                results.appendLine("    - num_classes: ${healthModel.getInt("num_classes")}")

                val inputSize = healthModel.getInt("input_size")
                if (inputSize == 15) {
                    results.appendLine("    ✓ Correct input size (9 + 6)")
                } else {
                    results.appendLine("    ⚠️ WARNING: Expected 15, got $inputSize")
                }
            } else {
                results.appendLine("  ✗ health_model: MISSING")
            }
            results.appendLine()

            // Nutrition features
            if (json.has("nutrition_features")) {
                val nutritionFeatures = json.getJSONArray("nutrition_features")
                results.appendLine("  ✓ nutrition_features:")
                results.appendLine("    - count: ${nutritionFeatures.length()}")

                if (nutritionFeatures.length() == 6) {
                    results.appendLine("    ✓ Correct count (6 fields)")
                } else {
                    results.appendLine("    ⚠️ WARNING: Expected 6, got ${nutritionFeatures.length()}")
                }

                results.appendLine("    - fields:")
                for (i in 0 until nutritionFeatures.length()) {
                    results.appendLine("      ${i+1}. ${nutritionFeatures.getString(i)}")
                }
            } else {
                results.appendLine("  ✗ nutrition_features: MISSING")
            }
            results.appendLine()

            // SHAP config
            if (json.has("shap")) {
                val shap = json.getJSONObject("shap")
                results.appendLine("  ✓ shap:")
                results.appendLine("    - enabled: ${shap.getBoolean("enabled")}")
                results.appendLine("    - num_background_samples: ${shap.getInt("num_background_samples")}")
            }

        } catch (e: Exception) {
            results.appendLine("✗ ERROR loading model_config.json:")
            results.appendLine("  ${e.javaClass.simpleName}: ${e.message}")
            results.appendLine()
            results.appendLine("Stack trace:")
            e.stackTrace.take(5).forEach {
                results.appendLine("  $it")
            }
        }
        results.appendLine()

        // Test 4: Check TFLite models
        results.appendLine("TEST 4: Checking TFLite models")
        results.appendLine("-".repeat(50))

        listOf("food_gap_model.tflite", "food_health_model.tflite").forEach { filename ->
            try {
                val inputStream = assets.open(filename)
                val size = inputStream.available()
                inputStream.close()

                val sizeKB = size / 1024
                results.appendLine("  ✓ $filename: ${sizeKB} KB")

                if (sizeKB < 10) {
                    results.appendLine("    ⚠️ WARNING: File seems too small")
                }
            } catch (e: Exception) {
                results.appendLine("  ✗ $filename: ${e.message}")
            }
        }
        results.appendLine()

        // Final summary
        results.appendLine("=".repeat(50))
        results.appendLine("SUMMARY")
        results.appendLine("=".repeat(50))

        if (allFound) {
            results.appendLine("✅ All required files found!")
            results.appendLine("✅ model_config.json is valid")
            results.appendLine()
            results.appendLine("You can now proceed with the main app.")
            results.appendLine()
            results.appendLine("Next steps:")
            results.appendLine("1. Remove AssetTestActivity from launcher")
            results.appendLine("2. Set MainActivity as launcher")
            results.appendLine("3. Build and run the app")
        } else {
            results.appendLine("❌ Some files are missing")
            results.appendLine()
            results.appendLine("Please:")
            results.appendLine("1. Run ModelConverter.py")
            results.appendLine("2. Copy all 8 files to app/src/main/assets/")
            results.appendLine("3. Clean and rebuild project")
            results.appendLine("4. Run this test again")
        }

        // Display results
        textView.text = results.toString()
    }
}

/* 
ADD TO AndroidManifest.xml:

<activity
    android:name=".AssetTestActivity"
    android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent-filter>
</activity>

TEMPORARILY COMMENT OUT MainActivity's intent-filter while testing
*/