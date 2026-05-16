package com.macromind.foodscanner

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * HealthInference
 * ================
 * Runs food_health_model.tflite — the health verdict classifier.
 *
 * Model input:
 *   float32[1, 17]  — 9 category one-hot + 8 nutrition features
 *                     (in the order defined by health_model_metadata.json)
 *
 * Model output:
 *   float32[1, 3]   — [Healthy, Moderate, Unhealthy] probabilities
 *
 * This class is also used by KernelShapExplainer, which calls
 * predictRaw() many times with perturbed feature vectors.
 */
class HealthInference(context: Context) : AutoCloseable {

    private val interpreter: Interpreter
    private val config = AssetLoader.modelConfig!!
    private val labels = AssetLoader.healthLabels

    init {
        val model = loadModelFile(context, "food_health_model.tflite")
        interpreter = Interpreter(model, Interpreter.Options().apply {
            numThreads = 2
        })
    }

    // ── Public inference ─────────────────────────────────────────────────────

    /**
     * Predict health verdict from the 17-feature input vector.
     *
     * @param features   FloatArray[17] — already normalized, in model's expected order.
     *                   Build via buildFeatureVector().
     */
    fun predict(features: FloatArray): HealthResult {
        val probs = predictRaw(features)
        val maxIdx = probs.indices.maxByOrNull { probs[it] } ?: 0
        return HealthResult(
            verdict       = labels.getOrElse(maxIdx) { "Unknown" },
            confidence    = probs[maxIdx],
            probabilities = labels.zip(probs.toList()).toMap()
        )
    }

    /**
     * Low-level inference used by KernelShapExplainer.
     * Returns raw probability array for the "positive" class (index 0 = Healthy).
     *
     * @param features  FloatArray[N_FEATURES]
     * @return          FloatArray[N_CLASSES]
     */
    fun predictRaw(features: FloatArray): FloatArray {
        val n         = config.healthInputSize
        val inputBuf  = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder())

        for (i in 0 until n) {
            inputBuf.putFloat(if (i < features.size) features[i] else 0f)
        }
        inputBuf.rewind()

        val output = Array(1) { FloatArray(labels.size) }
        interpreter.run(inputBuf, output)
        return output[0]
    }

    // ── Feature vector builder ───────────────────────────────────────────────

    /**
     * Build the normalized 17-feature vector from a category result + nutrition data.
     *
     * Feature layout (must match training):
     *   [0..8]   = category one-hot (9 classes)
     *   [9..16]  = nutrition values, normalized using scaler
     *
     * @param categoryIndex  integer index of the predicted category (0–8)
     * @param nutrition      NutritionExtractor.NutritionData from the scan
     * @param additiveCount  total number of detected additives
     * @return               FloatArray[healthInputSize]
     */
    fun buildFeatureVector(
        categoryIndex: Int,
        nutrition:     NutritionExtractor.NutritionData?,
        additiveCount: Int
    ): FloatArray {
        val vec         = FloatArray(config.healthInputSize) { 0f }
        val nOneHot     = 10

        // One-hot category
        if (categoryIndex in 0 until 9) {
            vec[categoryIndex] = 1f
        } else {
            vec[9] = 1f
        }

        // Nutrition features (after the one-hot block)
        val mean = AssetLoader.scalerMean
        val std  = AssetLoader.scalerStd
        val featNames = AssetLoader.nutritionFeatureNames

        fun normalize(rawValue: Float, featureIndex: Int): Float {
            val scalerIdx = featureIndex
            return if (mean.isNotEmpty() && std.isNotEmpty() &&
                scalerIdx < mean.size && std[scalerIdx] != 0f)
                (rawValue - mean[scalerIdx]) / std[scalerIdx]
            else
                rawValue
        }

        featNames.forEachIndexed { featIdx, name ->
            val vecIdx   = nOneHot + featIdx
            if (vecIdx >= config.healthInputSize) return@forEachIndexed

            val rawVal: Float = when (name.lowercase()) {
                "energy_kcal"   -> nutrition?.energyKcal?.toFloat() ?: 0f
                "fat_g"         -> nutrition?.fatG?.toFloat()        ?: 0f
                "saturated_fat_g" -> nutrition?.saturatedFatG?.toFloat() ?: 0f
                "carbs_g"       -> nutrition?.carbsG?.toFloat()      ?: 0f
                "sugars_g"      -> nutrition?.sugarsG?.toFloat()     ?: 0f
                "fiber_g"       -> nutrition?.fiberG?.toFloat()      ?: 0f
                "protein_g"     -> nutrition?.proteinG?.toFloat()    ?: 0f
                "sodium_mg"     -> nutrition?.sodiumMg?.toFloat()    ?: 0f
                "additives_n"   -> additiveCount.toFloat()
                else            -> 0f
            }
            vec[vecIdx] = normalize(rawVal, featIdx)
        }

        return vec
    }

    // ── Cleanup ──────────────────────────────────────────────────────────────

    override fun close() = interpreter.close()

    // ── TFLite loader ────────────────────────────────────────────────────────

    private fun loadModelFile(context: Context, fileName: String): MappedByteBuffer {
        val fd    = context.assets.openFd(fileName)
        val input = FileInputStream(fd.fileDescriptor)
        return input.channel.map(FileChannel.MapMode.READ_ONLY,
            fd.startOffset, fd.declaredLength)
    }

    // ── Data class ───────────────────────────────────────────────────────────

    data class HealthResult(
        val verdict:       String,
        val confidence:    Float,
        val probabilities: Map<String, Float>
    )
}