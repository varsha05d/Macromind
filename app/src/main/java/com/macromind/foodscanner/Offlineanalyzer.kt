package com.macromind.foodscanner

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * OfflineAnalyzer
 * ================
 * Orchestrates the complete offline analysis pipeline.
 * Replaces the Flask backend entirely — everything runs on-device.
 *
 * PIPELINE:
 *   ingredients text  →  TextVectorizer  →  CategoryInference
 *                                                  ↓
 *   nutrition data    →  HealthInference.buildFeatureVector()
 *                                                  ↓
 *                        HealthInference  →  verdict + confidence
 *                                                  ↓
 *                        KernelShapExplainer  →  SHAP contributions
 *                                                  ↓
 *   ingredients text  →  IngredientParser  →  detected additives
 *                                                  ↓
 *                              AnalysisResult
 *
 * Usage (from a coroutine scope in ResultsActivity or CropActivity):
 *
 *   val result = OfflineAnalyzer.analyze(context, scanSession)
 *
 * All heavy work runs on Dispatchers.Default (background thread).
 * This function is safe to call from the main thread via withContext.
 */
object OfflineAnalyzer {

    /**
     * Run the full offline analysis pipeline.
     *
     * @param context    Android context (needed for TFLite + asset loading)
     * @param session    completed (or partial) ScanSession
     * @return           AnalysisResult containing all verdicts and explanations
     */
    suspend fun analyze(
        context: Context,
        session: ScanSession
    ): AnalysisResult = withContext(Dispatchers.Default) {

        // ── 0. Ensure assets are loaded ──────────────────────────────────────
        AssetLoader.init(context)
        val config = AssetLoader.modelConfig!!

        val ingredients = session.ingredients ?: ""
        val nutrition   = session.nutrition

        // ── 1. Detect additives (fast, no model needed) ──────────────────────
        val additives    = IngredientParser.detect(ingredients)
        val additiveCount = additives.size
        val harmfulCount  = IngredientParser.harmfulCount(additives)

        // ── 2. Tokenize ingredient text ──────────────────────────────────────
        val tokens = if (ingredients.isNotBlank()) {
            TextVectorizer.vectorize(
                text    = ingredients,
                vocab   = AssetLoader.vocab,
                seqLen  = config.textSeqLen
            )
        } else {
            IntArray(config.textSeqLen) { 0 }
        }

        // ── 3. Build nutrition array for category model ───────────────────────
        val nutritionArray = buildNutritionArray(nutrition, config.nutritionFeatureNames, additiveCount)

        // ── 4. Category inference ─────────────────────────────────────────────
        val categoryResult = CategoryInference(context).use { catModel ->
            catModel.predict(tokens, nutritionArray)
        }

        // ── 5. Build health model feature vector ─────────────────────────────
        val healthFeatureVec: FloatArray
        val healthResult:     HealthInference.HealthResult
        val shapResult:       KernelShapExplainer.ShapResult

        HealthInference(context).use { healthModel ->

            healthFeatureVec = healthModel.buildFeatureVector(
                categoryIndex = categoryResult.rawIndex,
                nutrition     = nutrition,
                additiveCount = additiveCount
            )

            // ── 6. Health inference ───────────────────────────────────────────
            healthResult = healthModel.predict(healthFeatureVec)

            // ── 7. KernelSHAP explanations ───────────────────────────────────
            val explainer = KernelShapExplainer(
                healthInference = healthModel,
                nSamples        = config.shapNSamples
            )
            shapResult = explainer.explain(healthFeatureVec)
        }

        // ── 8. Assemble final result ─────────────────────────────────────────
        AnalysisResult(
            // Health verdict
            verdict             = healthResult.verdict,
            confidence          = healthResult.confidence,
            healthProbabilities = healthResult.probabilities,

            // Category
            category            = categoryResult.name,
            categoryConfidence  = categoryResult.confidence,

            // Additives
            detectedAdditives   = additives,
            additiveCount       = additiveCount,
            harmfulAdditiveCount = harmfulCount,
            hasHarmfulAdditives = harmfulCount > 0,

            // SHAP
            shapContributions   = shapResult.topContributions(8),
            shapBaselineScore   = shapResult.baselineScore,
            shapPredictedScore  = shapResult.predictedScore,

            // Input summary
            ingredientsScanned  = ingredients.isNotBlank(),
            nutritionScanned    = nutrition != null,
            rawIngredients      = ingredients,
            nutrition           = nutrition
        )
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun buildNutritionArray(
        nutrition:    NutritionExtractor.NutritionData?,
        featureNames: List<String>,
        additiveCount: Int
    ): FloatArray {
        return FloatArray(featureNames.size) { i ->
            when (featureNames[i].lowercase()) {
                "energy_kcal" -> nutrition?.energyKcal?.toFloat() ?: 0f
                "fat_g"       -> nutrition?.fatG?.toFloat()        ?: 0f
                "saturated_fat_g" -> nutrition?.saturatedFatG?.toFloat() ?: 0f
                "carbs_g"     -> nutrition?.carbsG?.toFloat()      ?: 0f
                "sugars_g"    -> nutrition?.sugarsG?.toFloat()     ?: 0f
                "fiber_g"     -> nutrition?.fiberG?.toFloat()      ?: 0f
                "protein_g"   -> nutrition?.proteinG?.toFloat()    ?: 0f
                "sodium_mg"   -> nutrition?.sodiumMg?.toFloat()    ?: 0f
                "additives_n" -> additiveCount.toFloat()
                else          -> 0f
            }
        }
    }

    // ── Result data class ────────────────────────────────────────────────────

    data class AnalysisResult(
        // Health
        val verdict:              String,
        val confidence:           Float,
        val healthProbabilities:  Map<String, Float>,

        // Category
        val category:             String,
        val categoryConfidence:   Float,

        // Additives
        val detectedAdditives:    List<IngredientParser.DetectedAdditive>,
        val additiveCount:        Int,
        val harmfulAdditiveCount: Int,
        val hasHarmfulAdditives:  Boolean,

        // SHAP
        val shapContributions:    List<KernelShapExplainer.ShapContribution>,
        val shapBaselineScore:    Float,
        val shapPredictedScore:   Float,

        // Input info
        val ingredientsScanned:   Boolean,
        val nutritionScanned:     Boolean,
        val rawIngredients:       String,
        val nutrition:            NutritionExtractor.NutritionData?
    ) {
        /** Color for the verdict card — Green / Yellow / Red */
        val verdictColor: Int get() = when (verdict.lowercase()) {
            "healthy"   -> android.graphics.Color.parseColor("#2E7D32")
            "moderate"  -> android.graphics.Color.parseColor("#F57F17")
            "unhealthy" -> android.graphics.Color.parseColor("#B71C1C")
            else        -> android.graphics.Color.parseColor("#546E7A")
        }

        val verdictEmoji: String get() = when (verdict.lowercase()) {
            "healthy"   -> "✅"
            "moderate"  -> "⚠️"
            "unhealthy" -> "❌"
            else        -> "❓"
        }

        /** One-line summary for notification or share */
        val summary: String get() =
            "$verdictEmoji $verdict (${"%.0f".format(confidence * 100)}% confidence) · " +
                    "Category: $category · Additives: $additiveCount"
    }
}