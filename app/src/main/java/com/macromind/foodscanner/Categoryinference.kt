package com.macromind.foodscanner

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import org.json.JSONObject

/**
 * CategoryInference
 * ==================
 * Runs food_gap_model.tflite — the dual-input category classifier.
 *
 * Model inputs:
 *   [0] text_input   : float32[1, SEQ_LEN]   — tokenized ingredient text
 *   [1] nutri_input  : float32[1, N_NUTRI]   — normalized nutrition values
 *
 * Model output:
 *   [0]              : float32[1, 9]          — category probabilities
 *
 * Usage:
 *   val cat = CategoryInference(context)
 *   val result = cat.predict(textTokens, nutritionValues)
 *   cat.close()
 */
class CategoryInference(context: Context) : AutoCloseable {

    private val interpreter: Interpreter
    private val config = AssetLoader.modelConfig!!
    private val labels = AssetLoader.categoryLabels

    private val gapMean = FloatArray(7)
    private val gapStd = FloatArray(7)

    init {
        val model = loadModelFile(context, "food_gap_model.tflite")
        interpreter = Interpreter(model, Interpreter.Options().apply {
            numThreads = 2
        })

        try {
            val gapMeta = JSONObject(context.assets.open("metadata_GAP1.json").bufferedReader().readText())
            val norm = gapMeta.optJSONObject("inputs")?.optJSONObject("num_input")?.optJSONObject("normalisation")
            val meanArr = norm?.optJSONArray("mean")
            val stdArr = norm?.optJSONArray("std")
            for (i in 0 until 7) {
                gapMean[i] = meanArr?.optDouble(i, 0.0)?.toFloat() ?: 0f
                gapStd[i] = stdArr?.optDouble(i, 1.0)?.toFloat() ?: 1f
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ── Inference ────────────────────────────────────────────────────────────

    /**
     * Predict food category from tokenized text + raw nutrition values.
     *
     * @param textTokens     IntArray[SEQ_LEN] from TextVectorizer
     * @param nutritionRaw   FloatArray of raw nutrition values in the order
     *                       defined by model_config.json → nutrition_feature_names
     *                       (null values should be passed as 0f)
     * @return               CategoryResult with name, confidence, all probabilities
     */
    fun predict(
        textTokens:   IntArray,
        nutritionRaw: FloatArray
    ): CategoryResult {

        // ── Build input buffers ──────────────────────────────────────────────
        val seqLen    = config.textSeqLen
        val nNutri    = nutritionRaw.size

        val textBuf   = ByteBuffer.allocateDirect(1 * seqLen * 4)
            .order(ByteOrder.nativeOrder())
        val nutriBuf  = ByteBuffer.allocateDirect(1 * nNutri * 4)
            .order(ByteOrder.nativeOrder())

        // Text: pad or truncate to seqLen
        for (i in 0 until seqLen) {
            textBuf.putInt(if (i < textTokens.size) textTokens[i] else 0)
        }

        // Nutrition: normalize using scaler if available
        for (i in nutritionRaw.indices) {
            val v = if (gapMean.isNotEmpty() && gapStd.isNotEmpty() && i < gapMean.size && gapStd[i] != 0f)
                (nutritionRaw[i] - gapMean[i]) / gapStd[i]
            else
                nutritionRaw[i]
            nutriBuf.putFloat(v)
        }

        textBuf.rewind()
        nutriBuf.rewind()

        // ── Output buffer ────────────────────────────────────────────────────
        val nClasses  = labels.size
        val outputBuf = Array(1) { FloatArray(nClasses) }

        // ── Run model ────────────────────────────────────────────────────────
        // Dual-input: pass as map of index → buffer
        val inputs  = mapOf(0 to textBuf, 1 to nutriBuf)
        val outputs = mapOf(0 to outputBuf)

        interpreter.runForMultipleInputsOutputs(
            arrayOf(textBuf, nutriBuf),
            outputs
        )

        // ── Parse result ─────────────────────────────────────────────────────
        val probs = outputBuf[0]
        val maxIdx = probs.indices.maxByOrNull { probs[it] } ?: 0

        return CategoryResult(
            name          = labels.getOrElse(maxIdx) { "Unknown" },
            confidence    = probs[maxIdx],
            probabilities = labels.zip(probs.toList()).toMap(),
            rawIndex      = maxIdx
        )
    }

    // ── Cleanup ──────────────────────────────────────────────────────────────

    override fun close() = interpreter.close()

    // ── TFLite model loader ──────────────────────────────────────────────────

    private fun loadModelFile(context: Context, fileName: String): MappedByteBuffer {
        val fd    = context.assets.openFd(fileName)
        val input = FileInputStream(fd.fileDescriptor)
        val chan  = input.channel
        return chan.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
    }

    // ── Data class ───────────────────────────────────────────────────────────

    data class CategoryResult(
        val name:          String,
        val confidence:    Float,
        val probabilities: Map<String, Float>,
        val rawIndex:      Int
    )
}