package com.macromind.foodscanner

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * AssetLoader
 * ============
 * Singleton that loads and caches all JSON asset files.
 * Call AssetLoader.init(context) once in Application.onCreate() or
 * the first OfflineAnalyzer call. All subsequent accesses are from memory.
 *
 * Assets expected in app/src/main/assets/:
 *   model_config.json
 *   vocab_GAP1.json
 *   metadata_GAP1.json
 *   health_model_metadata.json
 *   shap_background.json
 *   additive_database.json
 */
object AssetLoader {

    // ── Loaded data ──────────────────────────────────────────────────────────

    var modelConfig: ModelConfig? = null
        private set

    var vocab: Map<String, Int> = emptyMap()
        private set

    var categoryLabels: List<String> = emptyList()
        private set

    var healthLabels: List<String> = emptyList()
        private set

    var shapBackground: ShapBackground? = null
        private set

    var additiveDatabase: List<AdditiveEntry> = emptyList()
        private set

    var nutritionFeatureNames: List<String> = emptyList()
        private set

    var scalerMean: FloatArray = FloatArray(0)
        private set

    var scalerStd: FloatArray = FloatArray(0)
        private set

    private var initialized = false

    // ── Initialization ───────────────────────────────────────────────────────

    /**
     * Load all assets. Safe to call multiple times — only loads once.
     */
    fun init(context: Context) {
        if (initialized) return

        val cfg = loadJson(context, "model_config.json")
        modelConfig = parseModelConfig(cfg)

        vocab = loadVocab(context)

        // Load category labels from GAP metadata — these are the ACTUAL labels
        // the food_gap_model.tflite was trained with, not the ones in model_config
        val gapMeta = loadJson(context, "metadata_GAP1.json")
        val gapClassLabels = gapMeta.optJSONArray("class_labels")
        categoryLabels = if (gapClassLabels != null && gapClassLabels.length() > 0) {
            jsonArrayToList(gapClassLabels)
        } else {
            // Fallback to model_config if GAP metadata missing
            modelConfig!!.categoryLabels
        }

        healthLabels   = modelConfig!!.healthLabels
        nutritionFeatureNames = modelConfig!!.nutritionFeatureNames

        shapBackground = loadShapBackground(context)

        additiveDatabase = loadAdditiveDatabase(context)

        // Scaler params from health metadata
        val healthMeta = loadJson(context, "health_model_metadata.json")
        scalerMean = jsonArrayToFloatArray(healthMeta.optJSONArray("scaler_mean"))
        scalerStd  = jsonArrayToFloatArray(healthMeta.optJSONArray("scaler_std"))

        initialized = true
    }

    fun isInitialized() = initialized

    // ── Private loaders ──────────────────────────────────────────────────────

    private fun loadJson(context: Context, filename: String): JSONObject {
        val text = context.assets.open(filename).bufferedReader().readText()
        return JSONObject(text)
    }

    private fun loadJsonArray(context: Context, filename: String): JSONArray {
        val text = context.assets.open(filename).bufferedReader().readText()
        return JSONArray(text)
    }

    private fun parseModelConfig(json: JSONObject): ModelConfig {
        return ModelConfig(
            textSeqLen            = json.getInt("text_seq_len"),
            vocabSize             = json.getInt("vocab_size"),
            categoryLabels        = jsonArrayToList(json.getJSONArray("category_labels")),
            healthLabels          = jsonArrayToList(json.getJSONArray("health_labels")),
            healthInputSize       = json.getInt("health_input_size"),
            nutritionFeatureNames = jsonArrayToList(json.getJSONArray("nutrition_feature_names")),
            shapNSamples          = json.optInt("shap_n_samples", 200),
            shapFeatureNames      = jsonArrayToList(json.optJSONArray("shap_feature_names") ?: JSONArray())
        )
    }

    private fun loadVocab(context: Context): Map<String, Int> {
        val json = loadJson(context, "vocab_GAP1.json")
        val map  = mutableMapOf<String, Int>()
        json.keys().forEach { key -> map[key] = json.getInt(key) }
        return map
    }

    private fun loadShapBackground(context: Context): ShapBackground {
        val json       = loadJson(context, "shap_background.json")
        val background = jsonArrayToFloatArray(json.getJSONArray("background_values"))
        val names      = if (json.has("feature_names"))
            jsonArrayToList(json.getJSONArray("feature_names"))
        else
            modelConfig!!.shapFeatureNames
        return ShapBackground(featureNames = names, values = background)
    }

    private fun loadAdditiveDatabase(context: Context): List<AdditiveEntry> {
        val text = context.assets.open("additive_database.json").bufferedReader().readText()
        val list = mutableListOf<AdditiveEntry>()

        return try {
            // First try as JSON array (flat list format)
            val arr = JSONArray(text)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(parseAdditiveEntry(obj))
            }
            list
        } catch (_: Exception) {
            // Try as JSON object — handle nested {"metadata":{}, "additives":{...}} format
            val root = JSONObject(text)

            // Check for nested "additives" object
            val additivesObj = if (root.has("additives")) {
                root.getJSONObject("additives")
            } else {
                root // flat object keyed by code
            }

            additivesObj.keys().forEach { key ->
                // Skip non-additive keys like "metadata"
                if (key == "metadata" || key == "version") return@forEach
                try {
                    val entry = additivesObj.getJSONObject(key)
                    entry.put("code", key)
                    list.add(parseAdditiveEntry(entry))
                } catch (_: Exception) {
                    // Skip malformed entries
                }
            }
            list
        }
    }

    private fun parseAdditiveEntry(obj: JSONObject): AdditiveEntry {
        // "common_in" may be a JSON array — convert to comma-separated string
        val commonFoods = try {
            val arr = obj.optJSONArray("common_in")
            if (arr != null) {
                (0 until arr.length()).joinToString(", ") { arr.getString(it) }
            } else {
                obj.optString("common_foods", obj.optString("common_in", ""))
            }
        } catch (_: Exception) {
            obj.optString("common_foods", "")
        }

        return AdditiveEntry(
            code        = obj.optString("code", ""),
            insCode     = obj.optString("ins", obj.optString("ins_code", "")),
            name        = obj.optString("name", "Unknown Additive"),
            category    = obj.optString("category", ""),
            severity    = obj.optString("severity", "none"),
            // "explanation" is the detailed description; "health_impact" is just "safe"/"concerning"
            healthImpact= obj.optString("explanation",
                            obj.optString("health_impact",
                              obj.optString("why_harmful", ""))),
            adiLimit    = obj.optString("adr_limit", obj.optString("adi_limit", "")),
            commonFoods = commonFoods
        )
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun jsonArrayToList(arr: JSONArray): List<String> =
        (0 until arr.length()).map { arr.getString(it) }

    private fun jsonArrayToFloatArray(arr: JSONArray?): FloatArray {
        if (arr == null) return FloatArray(0)
        return FloatArray(arr.length()) { arr.getDouble(it).toFloat() }
    }

    // ── Data classes ─────────────────────────────────────────────────────────

    data class ModelConfig(
        val textSeqLen:            Int,
        val vocabSize:             Int,
        val categoryLabels:        List<String>,
        val healthLabels:          List<String>,
        val healthInputSize:       Int,
        val nutritionFeatureNames: List<String>,
        val shapNSamples:          Int,
        val shapFeatureNames:      List<String>
    )

    data class ShapBackground(
        val featureNames: List<String>,
        val values:       FloatArray
    )

    data class AdditiveEntry(
        val code:         String,
        val insCode:      String,
        val name:         String,
        val category:     String,
        val severity:     String,    // none / minor / moderate / high
        val healthImpact: String,
        val adiLimit:     String,
        val commonFoods:  String
    )
}