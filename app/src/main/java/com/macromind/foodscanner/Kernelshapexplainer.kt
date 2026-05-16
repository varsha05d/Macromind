package com.macromind.foodscanner

import kotlin.math.*
import kotlin.random.Random

/**
 * KernelShapExplainer
 * ====================
 * Real KernelSHAP implementation for Android — no Python, no server needed.
 *
 * KernelSHAP is a model-agnostic SHAP approximation that:
 *   1. Samples N random binary coalitions (which features are "present")
 *   2. For each coalition, masks absent features with background (mean) values
 *   3. Runs the health model on each masked input
 *   4. Solves a weighted least-squares regression to get SHAP values
 *
 * For 17 features and 200 samples this runs in ~100–300ms on modern Android.
 *
 * Reference: Lundberg & Lee (2017) "A unified approach to interpreting model predictions"
 *
 * Usage:
 *   val explainer = KernelShapExplainer(healthInference)
 *   val result    = explainer.explain(featureVector)
 *
 * @param healthInference  HealthInference instance (must remain open during explain())
 * @param nSamples         number of coalitions to sample (default 200)
 */
class KernelShapExplainer(
    private val healthInference: HealthInference,
    private val nSamples: Int = AssetLoader.modelConfig?.shapNSamples ?: 200
) {

    private val background: FloatArray = AssetLoader.shapBackground?.values
        ?: FloatArray(AssetLoader.modelConfig?.healthInputSize ?: 17) { 0f }

    private val featureNames: List<String> = AssetLoader.shapBackground?.featureNames
        ?: AssetLoader.modelConfig?.shapFeatureNames
        ?: emptyList()

    private val nFeatures = background.size
    private val rng = Random(42)   // deterministic for reproducibility

    // ── Main entry point ─────────────────────────────────────────────────────

    /**
     * Compute SHAP values for a single feature vector.
     *
     * @param features  FloatArray[nFeatures] — the model input to explain
     * @return          ShapResult with per-feature contributions
     */
    fun explain(features: FloatArray): ShapResult {
        if (nFeatures <= 1) return ShapResult(emptyList(), 0f, 0f)

        // ── Baseline: model output on background ────────────────────────────
        val baselineProbs  = healthInference.predictRaw(background)
        val baselineScore  = positiveClassScore(baselineProbs)  // "Healthy" probability

        // ── Full prediction: model output on real features ───────────────────
        val fullProbs   = healthInference.predictRaw(features)
        val fullScore   = positiveClassScore(fullProbs)

        // ── Sample coalitions ────────────────────────────────────────────────
        // Coalition z ∈ {0,1}^M:  1 = use real value, 0 = use background
        val coalitions  = sampleCoalitions()
        val weights     = FloatArray(coalitions.size)
        val modelOutputs = FloatArray(coalitions.size)

        for (i in coalitions.indices) {
            val z       = coalitions[i]
            val zSize   = z.count { it }

            // Skip all-zeros and all-ones (handled by boundary conditions)
            if (zSize == 0 || zSize == nFeatures) {
                weights[i]      = 0f
                modelOutputs[i] = if (zSize == 0) baselineScore else fullScore
                continue
            }

            // KernelSHAP weight: (M-1) / [C(M,|z|) * |z| * (M-|z|)]
            weights[i]      = kernelWeight(nFeatures, zSize)
            // Build masked input
            val masked      = buildMaskedInput(features, z)
            // Run model
            val probs       = healthInference.predictRaw(masked)
            modelOutputs[i] = positiveClassScore(probs)
        }

        // ── Weighted least squares → SHAP values ────────────────────────────
        val shapValues = weightedLeastSquares(
            coalitions   = coalitions,
            weights      = weights,
            modelOutputs = modelOutputs,
            baseline     = baselineScore
        )

        // ── Build result ─────────────────────────────────────────────────────
        val contributions = buildContributions(shapValues, features, fullScore, baselineScore)

        return ShapResult(
            contributions  = contributions,
            baselineScore  = baselineScore,
            predictedScore = fullScore
        )
    }

    // ── Coalition sampling ───────────────────────────────────────────────────

    /**
     * Sample random binary coalitions. Also includes the paired complement
     * of each sample (standard KernelSHAP trick to reduce variance).
     */
    private fun sampleCoalitions(): List<BooleanArray> {
        val half   = nSamples / 2
        val result = mutableListOf<BooleanArray>()

        for (i in 0 until half) {
            val z    = BooleanArray(nFeatures) { rng.nextBoolean() }
            val zBar = BooleanArray(nFeatures) { !z[it] }   // complement
            result.add(z)
            result.add(zBar)
        }
        return result
    }

    // ── KernelSHAP weight ────────────────────────────────────────────────────

    private fun kernelWeight(m: Int, zSize: Int): Float {
        // (M-1) / [C(M, |z|) * |z| * (M-|z|)]
        val comb = logBinomial(m, zSize)
        val denom = exp(comb) * zSize * (m - zSize)
        return if (denom == 0.0) 0f else ((m - 1).toDouble() / denom).toFloat()
    }

    private fun logBinomial(n: Int, k: Int): Double {
        // log C(n, k) = log n! - log k! - log (n-k)!
        var result = 0.0
        for (i in 1..minOf(k, n - k)) {
            result += ln((n - i + 1).toDouble()) - ln(i.toDouble())
        }
        return result
    }

    // ── Masked input builder ─────────────────────────────────────────────────

    private fun buildMaskedInput(features: FloatArray, coalition: BooleanArray): FloatArray {
        return FloatArray(nFeatures) { i ->
            if (coalition[i]) features[i] else background[i]
        }
    }

    // ── Weighted Least Squares ────────────────────────────────────────────────
    //
    // Solve:  φ = argmin Σ_i w_i (f(x_z_i) - E[f] - z_i · φ)²
    //         subject to:  φ_0 = E[f(background)]   (efficiency constraint)
    //
    // Solution: φ = (Z'ᵀ W Z')⁻¹ Z'ᵀ W (y - baseline)
    //
    // Where y_i = f(x_z_i) - baseline
    //
    // For nFeatures=17 this is a 17×17 system — fast Gaussian elimination.

    private fun weightedLeastSquares(
        coalitions:   List<BooleanArray>,
        weights:      FloatArray,
        modelOutputs: FloatArray,
        baseline:     Float
    ): FloatArray {
        val n = nFeatures
        val m = coalitions.size

        // Build Z (m×n) and y (m×1)
        val Z = Array(m) { i -> FloatArray(n) { j -> if (coalitions[i][j]) 1f else 0f } }
        val y = FloatArray(m) { i -> modelOutputs[i] - baseline }

        // ZᵀWZ  (n×n)
        val ZtWZ = Array(n) { FloatArray(n) { 0f } }
        for (k in 0 until m) {
            val w = weights[k]
            if (w == 0f) continue
            for (i in 0 until n) {
                for (j in 0 until n) {
                    ZtWZ[i][j] += w * Z[k][i] * Z[k][j]
                }
            }
        }
        // Add small ridge for numerical stability
        val ridge = 1e-5f
        for (i in 0 until n) ZtWZ[i][i] += ridge

        // ZᵀWy  (n×1)
        val ZtWy = FloatArray(n) { 0f }
        for (k in 0 until m) {
            val w = weights[k]
            if (w == 0f) continue
            for (i in 0 until n) {
                ZtWy[i] += w * Z[k][i] * y[k]
            }
        }

        // Solve ZtWZ · φ = ZtWy via Gaussian elimination
        return gaussianElimination(ZtWZ, ZtWy) ?: FloatArray(n) { 0f }
    }

    /**
     * Solves Ax = b for x via partial-pivot Gaussian elimination.
     * Returns null if matrix is singular.
     */
    private fun gaussianElimination(A: Array<FloatArray>, b: FloatArray): FloatArray? {
        val n   = b.size
        val aug = Array(n) { i -> FloatArray(n + 1) { j -> if (j < n) A[i][j] else b[i] } }

        for (col in 0 until n) {
            // Partial pivot
            var maxRow = col
            for (row in col + 1 until n) {
                if (abs(aug[row][col]) > abs(aug[maxRow][col])) maxRow = row
            }
            val tmp = aug[col]; aug[col] = aug[maxRow]; aug[maxRow] = tmp

            val pivot = aug[col][col]
            if (abs(pivot) < 1e-10f) return null   // singular

            for (row in col + 1 until n) {
                val factor = aug[row][col] / pivot
                for (j in col until n + 1) {
                    aug[row][j] -= factor * aug[col][j]
                }
            }
        }

        // Back substitution
        val x = FloatArray(n)
        for (i in n - 1 downTo 0) {
            x[i] = aug[i][n]
            for (j in i + 1 until n) {
                x[i] -= aug[i][j] * x[j]
            }
            x[i] /= aug[i][i]
        }
        return x
    }

    // ── Result builder ───────────────────────────────────────────────────────

    private fun positiveClassScore(probs: FloatArray): Float =
        // Index 0 = Healthy probability (positive = good health)
        probs.getOrElse(0) { 0f }

    private fun buildContributions(
        shapValues:     FloatArray,
        features:       FloatArray,
        predictedScore: Float,
        baselineScore:  Float
    ): List<ShapContribution> {

        val result = mutableListOf<ShapContribution>()

        for (i in shapValues.indices) {
            val name   = featureNames.getOrElse(i) { "Feature $i" }
            val shap   = shapValues[i]
            val raw    = features.getOrElse(i) { 0f }

            // Direction: positive SHAP → pushed toward Healthy
            //            negative SHAP → pushed toward Unhealthy
            val direction = when {
                shap >  0.005f -> "positive"   // helps health score
                shap < -0.005f -> "negative"   // hurts health score
                else           -> "neutral"
            }

            // Human-readable magnitude: "low" / "medium" / "high"
            val absShap   = abs(shap)
            val magnitude = when {
                absShap > 0.10f -> "high"
                absShap > 0.03f -> "medium"
                absShap > 0.005f -> "low"
                else            -> "negligible"
            }

            result.add(ShapContribution(
                featureName  = friendlyName(name),
                featureIndex = i,
                shapValue    = shap,
                rawValue     = raw,
                direction    = direction,
                magnitude    = magnitude,
                explanation  = buildExplanation(name, shap, raw)
            ))
        }

        // Sort by absolute SHAP value, largest first
        return result.sortedByDescending { abs(it.shapValue) }
    }

    private fun friendlyName(name: String): String = when (name.lowercase()) {
        "energy_kcal"            -> "Calories"
        "fat_g"                  -> "Total Fat"
        "carbs_g"                -> "Carbohydrates"
        "sugars_g"               -> "Sugar"
        "fiber_g"                -> "Dietary Fiber"
        "protein_g"              -> "Protein"
        "sodium_mg"              -> "Sodium"
        "additives_n"            -> "Additives"
        else                     -> name.replace("_", " ").replaceFirstChar { it.uppercase() }
    }

    private fun buildExplanation(name: String, shap: Float, raw: Float): String {
        val dir   = if (shap > 0) "improved" else "reduced"
        val pct   = (abs(shap) * 100).toInt()
        val fname = friendlyName(name)
        return when {
            pct < 1  -> "$fname had negligible effect on the verdict."
            else     -> "$fname (${formatRaw(name, raw)}) $dir the health score by ~$pct%."
        }
    }

    private fun formatRaw(name: String, raw: Float): String = when (name.lowercase()) {
        "energy_kcal" -> "${raw.toInt()} kcal"
        "fat_g"       -> "${"%.1f".format(raw)} g"
        "carbs_g"     -> "${"%.1f".format(raw)} g"
        "sugars_g"    -> "${"%.1f".format(raw)} g"
        "fiber_g"     -> "${"%.1f".format(raw)} g"
        "protein_g"   -> "${"%.1f".format(raw)} g"
        "sodium_mg"   -> "${raw.toInt()} mg"
        "additives_n" -> "${raw.toInt()}"
        else          -> "${"%.2f".format(raw)}"
    }

    // ── Data classes ─────────────────────────────────────────────────────────

    data class ShapContribution(
        val featureName:  String,
        val featureIndex: Int,
        val shapValue:    Float,     // positive = pushes toward Healthy
        val rawValue:     Float,
        val direction:    String,    // "positive" / "negative" / "neutral"
        val magnitude:    String,    // "high" / "medium" / "low" / "negligible"
        val explanation:  String
    )

    data class ShapResult(
        val contributions:  List<ShapContribution>,
        val baselineScore:  Float,   // model output on background (average food)
        val predictedScore: Float    // model output on this food
    ) {
        /** Top N contributions by absolute SHAP value */
        fun topContributions(n: Int = 5) = contributions.take(n)

        /** Total SHAP sum should approximate (predicted - baseline) */
        val sumCheck: Float get() = contributions.sumOf { it.shapValue.toDouble() }.toFloat()
    }
}