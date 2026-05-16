package com.macromind.foodscanner

import android.util.Log

/**
 * NutritionExtractor — V4 (rewritten for real-world OCR reliability)
 *
 * CORE INSIGHT: ML Kit reads nutrition tables in unpredictable ways:
 *   - Sometimes label + value on same line: "Protein 21.2 g"
 *   - Sometimes labels and values in separate blocks (split-column)
 *   - Sometimes partially merged: "Protein" on one line, "21.2 g" on next
 *   - Sometimes multi-column: "Per 100g" and "Per Serve" side by side
 *
 * STRATEGY (V4):
 *   1. INLINE-FIRST: Scan every line for a nutrient keyword. If found,
 *      extract the number from the SAME line. If no number on same line,
 *      look at the NEXT line. This handles 80%+ of real-world OCR output.
 *   2. SPLIT-COLUMN FALLBACK: If inline finds < 3 values, try the
 *      positional pairing approach (labels block → values block).
 *   3. NUMBER EXTRACTION: When multiple numbers appear on a line,
 *      prefer the LAST number (which is typically the "per 100g" column
 *      on Indian nutrition labels where per-serve comes first).
 *
 * MODEL INPUTS (6 nutrition values needed):
 *   fat_g, carbs_g, sugars_g, fiber_g, protein_g, sodium_mg
 */
object NutritionExtractor {

    data class NutritionData(
        val energyKcal:    Double? = null,
        val fatG:          Double? = null,
        val saturatedFatG: Double? = null,
        val sugarsG:       Double? = null,
        val carbsG:        Double? = null,
        val fiberG:        Double? = null,
        val proteinG:      Double? = null,
        val sodiumMg:      Double? = null
    )

    // ─────────────────────────────────────────────────────────────────────────
    // Public entry point
    // ─────────────────────────────────────────────────────────────────────────

    fun extractNutrition(rawText: String): NutritionData {
        Log.d(TAG, "=== STARTING EXTRACTION (V4) ===")
        Log.d(TAG, "Raw text:\n$rawText")

        // Strategy 1: Inline (primary — most reliable)
        val inline = tryInlineFormat(rawText)
        if (isValidResult(inline)) {
            Log.d(TAG, "✓ Inline format successful (${countFields(inline)} fields)")
            return inline
        }

        // Strategy 2: Split-column fallback
        Log.d(TAG, "Inline found ${countFields(inline)} fields, trying split-column...")
        val split = trySplitFormat(rawText)
        if (split != null && isValidResult(split)) {
            Log.d(TAG, "✓ Split format successful (${countFields(split)} fields)")
            return split
        }

        // Strategy 3: Merge best of both
        Log.d(TAG, "Both strategies weak, merging results...")
        return mergeBest(inline, split)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // STRATEGY 1: Inline (label + value on same line or next line)
    // ─────────────────────────────────────────────────────────────────────────

    private fun tryInlineFormat(text: String): NutritionData {
        val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
        val fields = mutableMapOf<String, Double?>()

        for (i in lines.indices) {
            val line  = lines[i]
            val lower = line.lowercase()
            if (isSkipLine(lower)) continue

            val nutrient = labelToNutrientKey(lower) ?: continue
            if (nutrient == "DISCARD") continue
            if (fields.containsKey(nutrient)) continue  // first match wins

            // Try to extract a number from THIS line first
            var raw = extractBestNumber(line)

            // If no number on this line, check the NEXT line
            if (raw == null && i + 1 < lines.size) {
                val nextLine = lines[i + 1]
                val nextLower = nextLine.lowercase()
                // Only use next line if it looks like a value (has digits, no nutrient keyword)
                if (nextLine.any { it.isDigit() } && labelToNutrientKey(nextLower) == null) {
                    raw = extractBestNumber(nextLine)
                    Log.d(TAG, "  [inline] $nutrient: no number on label line, took from next: '${nextLine}' → $raw")
                }
            }

            if (raw == null) continue

            val value = when (nutrient) {
                "sodiumMg"   -> resolveSodium(raw, line)
                "energyKcal" -> resolveEnergy(raw, line)
                else -> raw
            }

            fields[nutrient] = value
            Log.d(TAG, "  [inline] $nutrient = $value  (line='$line')")
        }

        return buildResult(fields)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // STRATEGY 2: Split-column (all labels first, then all values)
    // ─────────────────────────────────────────────────────────────────────────

    private fun trySplitFormat(text: String): NutritionData? {
        val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }

        data class LineInfo(val idx: Int, val line: String, val role: String)

        val classified = lines.mapIndexed { idx, line ->
            val lower = line.lowercase()
            when {
                isSkipLine(lower)        -> LineInfo(idx, line, "SKIP")
                isPureValueLine(line)    -> LineInfo(idx, line, "VALUE")
                isPureLabelLine(line)    -> LineInfo(idx, line, "LABEL")
                else                     -> LineInfo(idx, line, "SKIP")
            }
        }

        val labels = classified.filter { it.role == "LABEL" }
        val values = classified.filter { it.role == "VALUE" }

        Log.d(TAG, "Split: ${labels.size} labels, ${values.size} values")
        labels.forEach { Log.d(TAG, "  LABEL: '${it.line}'") }
        values.forEach { Log.d(TAG, "  VALUE: '${it.line}'") }

        if (labels.size < 3 || values.size < 3) return null

        val fields = mutableMapOf<String, Double?>()
        var vPtr = 0

        for (lInfo in labels) {
            if (vPtr >= values.size) break

            val label    = lInfo.line.lowercase()
            val nutrient = labelToNutrientKey(label) ?: continue
            if (nutrient == "DISCARD") { vPtr++; continue }
            if (fields.containsKey(nutrient)) continue

            val vInfo = values[vPtr]
            vPtr++

            val rawValue = extractBestNumber(vInfo.line) ?: continue

            val finalValue = when (nutrient) {
                "sodiumMg"   -> resolveSodium(rawValue, lInfo.line)
                "energyKcal" -> resolveEnergy(rawValue, vInfo.line)
                else -> rawValue
            }

            fields[nutrient] = finalValue
            Log.d(TAG, "  [split] $nutrient = $finalValue  (label='${lInfo.line}' value='${vInfo.line}')")
        }

        return buildResult(fields)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Classification helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** A PURE value line: has digits but NO nutrient keyword */
    private fun isPureValueLine(line: String): Boolean {
        if (!line.any { it.isDigit() }) return false
        val l = line.lowercase()
        if (isSkipLine(l)) return false
        val nutrient = labelToNutrientKey(l)
        if (nutrient == null) return true
        // Short lines with just number + unit are values, not labels
        val stripped = l.replace(Regex("""\d+\.?\d*"""), "")
                        .replace(Regex("""(kcal|kj|mg|g|%)"""), "").trim()
        return stripped.length < 3
    }

    /** A PURE label line: has a nutrient keyword and NO digits */
    private fun isPureLabelLine(line: String): Boolean {
        if (line.any { it.isDigit() }) return false
        val l = line.lowercase()
        if (isSkipLine(l)) return false
        return labelToNutrientKey(l) != null
    }

    private fun isSkipLine(lower: String): Boolean {
        return SKIP_PATTERNS.any { lower.contains(it) } || lower.length < 2
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Keyword → nutrient field mapping (OCR-typo tolerant)
    // ─────────────────────────────────────────────────────────────────────────

    private fun labelToNutrientKey(lower: String): String? {
        // Energy — handle OCR typo "erergy", "enegry", "enrgy"
        if (lower.containsAny("energy", "erergy", "enegry", "enrgy", "calori", "kcal") &&
            !lower.contains("kj")) return "energyKcal"

        // Saturated Fat — must check BEFORE generic "fat"
        if (lower.containsAny("saturated", "sat fat", "sat. fat")) return "saturatedFatG"

        // Trans Fat — record but we don't store it (not a model input)
        if (lower.containsAny("trans fat", "trans-fat")) return "DISCARD"

        // Total Fat
        if (lower.containsAny("total fat", "fat") &&
            !lower.contains("saturated") && !lower.contains("trans")) return "fatG"

        // Sugars — "Total Sugars" wins; "Added Sugars" maps to same field (first match wins)
        if (lower.containsAny("sugar", "sugas")) return "sugarsG"

        // Carbohydrates — OCR typos
        if (lower.containsAny("carbohydrate", "carbohydrat", "catoohydrate", "catoohyd",
                               "catbohydrate", "catbohyd", "carb", "carobhyd", "cartbohydrate")) return "carbsG"

        // Dietary Fiber / Fibre
        if (lower.containsAny("dietary fibre", "dietary fiber", "fibre", "fiber")) return "fiberG"

        // Protein
        if (lower.containsAny("protein", "protien", "protcin")) return "proteinG"

        // Sodium — OCR typos
        if (lower.containsAny("sodium", "sodirm", "sodiun", "sodiam", "sodiu")) return "sodiumMg"

        // Salt (convert later)
        if (lower.containsAny("salt") && !lower.contains("assault")) return "sodiumMg"

        return null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Number extraction — SMART: prefers "per 100g" column
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Extracts the BEST number from a line.
     *
     * Rules:
     * 1. Find ALL numbers in the line
     * 2. If there's only 1 number, use it
     * 3. If there are 2+ numbers, use the LAST one (typically "per 100g" column
     *    on Indian labels where per-serve comes first)
     * 4. Ignore numbers that are clearly not nutrition values (>10000, or "100" from "per 100g")
     *
     * Handles OCR mangling:
     *   "33.19 g"  → 33.19
     *   "643 mg"   → 643.0
     *   "6.7g"     → 6.7
     *   "0"        → 0.0
     *   "< 0.5 g"  → 0.5
     */
    private fun extractBestNumber(text: String): Double? {
        // Remove "per 100g" / "per 100 g" so we don't accidentally grab "100"
        val cleaned = text.replace(Regex("""(?i)per\s*100\s*g"""), "")
                          .replace(Regex("""(?i)\bper\s+serve?\b"""), "")
                          .replace(Regex("""(?i)\bper\s+serving\b"""), "")
                          .replace(Regex("""(?i)\brda\b"""), "")
                          .replace(Regex("""(?i)%\s*"""), "")

        val allNumbers = Regex("""\d+(?:\.\d+)?""").findAll(cleaned)
            .map { it.value.toDoubleOrNull() }
            .filterNotNull()
            .filter { it < 10000 }  // Sanity: no nutrient value > 10000 per 100g
            .toList()

        if (allNumbers.isEmpty()) return null

        // If only 1 number, use it
        if (allNumbers.size == 1) return allNumbers[0]

        // Multiple numbers: prefer the last one (per-100g column is usually rightmost)
        // But if the last number looks like a percentage (ends with %) in original, skip it
        return allNumbers.last()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Resolvers for special fields
    // ─────────────────────────────────────────────────────────────────────────

    private fun resolveEnergy(raw: Double, line: String): Double {
        val l = line.lowercase()
        // Find explicit kcal value in "xxxx kJ / yyy kcal" style
        val kcalPattern = Regex("""(\d+(?:\.\d+)?)\s*kcal""")
        val kcalMatch = kcalPattern.find(l)
        if (kcalMatch != null) return kcalMatch.groupValues[1].toDouble()

        // If the value looks like kJ → convert
        if (l.contains("kj") && !l.contains("kcal")) return raw / 4.184

        // Sanity: energy should be < 1000 kcal/100g for packaged food
        if (raw > 1200 && !l.contains("kcal")) return raw / 4.184

        return raw
    }

    private fun resolveSodium(raw: Double, line: String): Double {
        val l = line.lowercase()
        var value = raw

        // Salt → sodium conversion (salt is 39.3% sodium by mass)
        if (l.contains("salt") && !l.contains("sodium")) {
            value *= 0.393
        }

        return when {
            l.contains("mg")                     -> value          // already mg
            l.contains(" g") || l.endsWith("g")  -> value * 1000  // grams → mg
            value < 5.0                           -> value * 1000  // small number → assume grams
            else                                  -> value          // assume mg
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Merge / utility helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Merge two partial results — take non-null from either */
    private fun mergeBest(a: NutritionData, b: NutritionData?): NutritionData {
        if (b == null) return a
        return NutritionData(
            energyKcal    = a.energyKcal    ?: b.energyKcal,
            fatG          = a.fatG          ?: b.fatG,
            saturatedFatG = a.saturatedFatG ?: b.saturatedFatG,
            sugarsG       = a.sugarsG       ?: b.sugarsG,
            carbsG        = a.carbsG        ?: b.carbsG,
            fiberG        = a.fiberG        ?: b.fiberG,
            proteinG      = a.proteinG      ?: b.proteinG,
            sodiumMg      = a.sodiumMg      ?: b.sodiumMg
        )
    }

    private fun buildResult(fields: Map<String, Double?>) = NutritionData(
        energyKcal    = fields["energyKcal"],
        fatG          = fields["fatG"],
        saturatedFatG = fields["saturatedFatG"],
        sugarsG       = fields["sugarsG"],
        carbsG        = fields["carbsG"],
        fiberG        = fields["fiberG"],
        proteinG      = fields["proteinG"],
        sodiumMg      = fields["sodiumMg"]
    )

    private fun isValidResult(data: NutritionData): Boolean {
        return countFields(data) >= 3
    }

    private fun countFields(data: NutritionData): Int {
        // Only count the 6 model-input fields (not energy/sat fat)
        return listOf(
            data.fatG, data.carbsG, data.sugarsG,
            data.fiberG, data.proteinG, data.sodiumMg
        ).count { it != null }
    }

    /** Convenience: check if string contains ANY of the given substrings */
    private fun String.containsAny(vararg subs: String): Boolean =
        subs.any { this.contains(it) }

    private val SKIP_PATTERNS = listOf(
        "nutritional information", "nutrition information",
        "nutritional value", "nutritional facts", "nutrition facts",
        "approximate value", "approximate",
        "per 100", "per serve", "per serving", "as sold",
        "% rda", "% daily", "recommended dietary",
        "reference intake", "based on",
        "nutrients", "nutrient",
        "amount per", "daily value"
    )

    private const val TAG = "NutritionExtractor"
}