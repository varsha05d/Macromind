package com.macromind.foodscanner

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Diagnostic utility to check asset files
 * Use this to debug "model_config.json failed" errors
 */
object AssetDiagnostics {

    private const val TAG = "AssetDiagnostics"

    /**
     * Run complete diagnostics on all asset files
     * Call this from onCreate() in your main activity
     */
    fun runDiagnostics(context: Context): String {
        val results = StringBuilder()
        results.appendLine("=" .repeat(60))
        results.appendLine("ASSET DIAGNOSTICS")
        results.appendLine("=" .repeat(60))

        // List of required files
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

        results.appendLine("\n1. CHECKING ASSET FILES:")
        results.appendLine("-" .repeat(60))

        var allFilesFound = true

        for (filename in requiredFiles) {
            val exists = checkFileExists(context, filename)
            val status = if (exists) "✓ FOUND" else "✗ MISSING"
            results.appendLine("  $status - $filename")

            if (!exists) allFilesFound = false
        }

        results.appendLine("\n2. CHECKING model_config.json CONTENT:")
        results.appendLine("-" .repeat(60))

        try {
            val configContent = context.assets.open("model_config.json")
                .bufferedReader()
                .use { it.readText() }

            results.appendLine("  ✓ File can be read")
            results.appendLine("  Size: ${configContent.length} characters")

            // Try to parse as JSON
            val json = JSONObject(configContent)
            results.appendLine("  ✓ Valid JSON format")

            // Check required fields
            val hasNutritionFeatures = json.has("nutrition_features")
            val hasHealthModel = json.has("health_model")
            val hasCategoryModel = json.has("category_model")

            results.appendLine("  ✓ Has 'nutrition_features': $hasNutritionFeatures")
            results.appendLine("  ✓ Has 'health_model': $hasHealthModel")
            results.appendLine("  ✓ Has 'category_model': $hasCategoryModel")

            if (hasNutritionFeatures) {
                val nutritionFeatures = json.getJSONArray("nutrition_features")
                results.appendLine("  ✓ Nutrition feature count: ${nutritionFeatures.length()}")
                results.appendLine("  Features:")
                for (i in 0 until nutritionFeatures.length()) {
                    results.appendLine("    - ${nutritionFeatures.getString(i)}")
                }
            }

            if (hasHealthModel) {
                val healthModel = json.getJSONObject("health_model")
                val inputSize = healthModel.getInt("input_size")
                results.appendLine("  ✓ Health model input size: $inputSize")

                if (inputSize != 15) {
                    results.appendLine("  ⚠ WARNING: Expected 15 inputs (9 category + 6 nutrition)")
                }
            }

        } catch (e: Exception) {
            results.appendLine("  ✗ ERROR reading/parsing model_config.json:")
            results.appendLine("    ${e.message}")
            results.appendLine("    ${e.stackTraceToString()}")
        }

        results.appendLine("\n3. CHECKING ASSETS FOLDER LOCATION:")
        results.appendLine("-" .repeat(60))

        // Try to list all files in assets
        try {
            val allAssets = context.assets.list("")
            results.appendLine("  Total files in assets/: ${allAssets?.size ?: 0}")

            if (allAssets != null && allAssets.isNotEmpty()) {
                results.appendLine("  Files found:")
                allAssets.sorted().forEach { filename ->
                    results.appendLine("    - $filename")
                }
            } else {
                results.appendLine("  ⚠ WARNING: Assets folder appears empty!")
                results.appendLine("  Make sure files are in:")
                results.appendLine("    MacroMind/app/src/main/assets/")
                results.appendLine("  NOT in a subfolder!")
            }
        } catch (e: Exception) {
            results.appendLine("  ✗ ERROR listing assets: ${e.message}")
        }

        results.appendLine("\n4. FINAL STATUS:")
        results.appendLine("-" .repeat(60))

        if (allFilesFound) {
            results.appendLine("  ✓ All required files found")
        } else {
            results.appendLine("  ✗ Some files are missing - see list above")
        }

        results.appendLine("\n" + "=" .repeat(60))

        val finalReport = results.toString()
        Log.d(TAG, finalReport)
        return finalReport
    }

    /**
     * Check if a specific file exists in assets
     */
    private fun checkFileExists(context: Context, filename: String): Boolean {
        return try {
            context.assets.open(filename).use { true }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Simple test - just check if model_config.json can be loaded
     */
    fun testModelConfig(context: Context): Boolean {
        return try {
            val content = context.assets.open("model_config.json")
                .bufferedReader()
                .use { it.readText() }

            val json = JSONObject(content)
            val hasRequired = json.has("nutrition_features") &&
                    json.has("health_model") &&
                    json.has("category_model")

            Log.d(TAG, "model_config.json test: ${if (hasRequired) "PASS" else "FAIL"}")
            hasRequired
        } catch (e: Exception) {
            Log.e(TAG, "model_config.json test FAILED: ${e.message}")
            e.printStackTrace()
            false
        }
    }
}