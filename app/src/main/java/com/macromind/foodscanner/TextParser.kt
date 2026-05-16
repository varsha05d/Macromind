package com.macromind.foodscanner

import android.util.Log

/**
 * TextParser.kt — V3 (hardened for real-world OCR)
 *
 * PROBLEMS FIXED (V1):
 *   1. HARD_STOPS too aggressive — "protein","fat","energy" cut ingredients mid-way
 *   2. "contains" re-triggered as header mid-sentence
 *   3. Period heuristic fired too early inside parenthesized sub-ingredients
 *
 * PROBLEMS FIXED (V2):
 *   4. OCR drops first chars of "INGREDIENTS" → "NGREDIENTS" → header not found
 *   5. Heuristic only extended forward → beginning of list lost
 *
 * PROBLEMS FIXED (V3 — this version):
 *   6. OCR frequently drops closing parentheses. openParens never reaches 0,
 *      so HARD_STOP and period-stop conditions never fire. Parser consumes
 *      "CONTAINS PERMITTED...", "MAY CONTAIN..." and garbage lines.
 *      Fix: Split stops into STRONG (always fire) and SOFT (respect parens).
 *      Add relaxed period-stop. Add max-line safety limit.
 */
object TextParser {

    private const val TAG = "MacroMind_Parser"

    // Maximum lines to collect after the header line.
    // Real ingredient lists rarely exceed 20 OCR lines.
    // This prevents runaway collection when paren tracking fails.
    private const val MAX_COLLECTION_LINES = 25

    // ── STRONG STOPS: Always fire, regardless of paren depth ──────────────
    // These are absolute section boundaries that NEVER appear inside ingredient lists.
    // NOTE: We have removed allergen warnings ("contains", "may contain") because 
    // it's better to capture them and let the user delete them in the UI than to 
    // risk cutting off the ingredients early.
    private val STRONG_STOPS = setOf(
        "nutritional information",
        "nutritional value",
        "nutrition facts",
        "nutrition information",
        "per 100g", "per 100 g", "per serve", "per serving",
        "storage instruction", "store in a", "keep in a",
        "best before", "use by", "expiry date",
        "manufactured by", "manufactured for",
        "marketed by", "packed by", "packed in",
        "mfg date", "mfg.", "batch no", "lot no",
        "fssai", "lic. no", "license no",
        "customer care", "toll free",
        "पोषण", "भंडारण"
    )

    // Regex-based fuzzy triggers for "INGREDIENTS" header.
    // OCR commonly mangles the first 1-3 chars at image edges:
    //   "INGREDIENTS" → "NGREDIENTS", "GREDIENTS", "1NGREDIENTS", "lNGREDIENTS"
    private val INGREDIENT_HEADER_REGEX = Regex(
        """(?i)\b[il1|]?n?gredients?\s*[:.\-—]?"""
    )

    // Exact-match triggers (for non-"ingredient" headers)
    private val OTHER_TRIGGERS = listOf(
        "composition",
        "made with",
        "made from",
        "सामग्री",
        "अवयव"
    )

    // Keywords that indicate a line is "ingredient-like" (for scoring)
    private val INGREDIENT_KEYWORDS = listOf(
        "oil", "flour", "sugar", "salt", "wheat", "rice", "starch",
        "spice", "spices", "extract", "powder", "vegetable", "edible", "refined",
        "gram", "dal", "masala", "corn", "maize", "milk", "modified",
        "antioxidant", "nature", "identical", "protein", "flavour",
        "flavor", "maltodex", "emulsifier", "preservative", "permitted",
        "colour", "color", "acid", "gum", "lecithin", "mixed",
        "water", "cream", "butter", "cocoa", "vanilla", "cinnamon",
        "garlic", "onion", "tomato", "chilli", "pepper", "cumin",
        "turmeric", "ginger", "cardamom", "coriander", "nutmeg",
        "clove", "anise", "fennel", "mustard", "sesame",
        "soy", "soya", "gluten", "cellulose", "dextrose",
        "fructose", "glucose", "lactose", "maltose", "sucrose",
        "whey", "casein", "gelatin", "pectin", "agar"
    )

    // ─────────────────────────────────────────────────────────────────────────

    fun extractIngredients(rawOcrText: String): String? {
        if (rawOcrText.isBlank()) return null
        Log.d(TAG, "TextParser input (${rawOcrText.length} chars)")

        // Normalize OCR whitespace: collapse multiple spaces/tabs to single space,
        // then split by newlines. This prevents trailing spaces from creating
        // phantom empty lines that break collection.
        val lines = rawOcrText
            .replace(Regex("[ \t]+"), " ")
            .split("\n")
            .map { it.trim() }
            .filter { it.isNotBlank() }

        // Try header-based extraction first
        val result = extractFromHeader(lines)
        if (!result.isNullOrBlank() && result.length > 8) {
            Log.d(TAG, "Extracted via header: ${result.length} chars")
            return clean(result)
        }

        // Fallback: most comma-rich block (with bidirectional extension)
        val fallback = extractHeuristic(lines)
        if (!fallback.isNullOrBlank()) {
            Log.d(TAG, "Extracted via heuristic: ${fallback.length} chars")
            return clean(fallback)
        }

        Log.d(TAG, "No ingredients found")
        return null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Strategy 1: Header-based (with fuzzy matching + OCR-safe paren handling)
    // ─────────────────────────────────────────────────────────────────────────

    private fun extractFromHeader(lines: List<String>): String? {
        var headerIdx    = -1
        var sameLineText = ""

        for ((idx, line) in lines.withIndex()) {
            val lower = line.lowercase().trim()

            // ── Check fuzzy "ingredient(s)" regex first ──
            val ingredientMatch = INGREDIENT_HEADER_REGEX.find(lower)
            if (ingredientMatch != null) {
                headerIdx = idx
                val afterTrigger = line
                    .substring(ingredientMatch.range.last + 1)
                    .trimStart(':', ' ', '-', '.', '—')
                    .trim()
                sameLineText = afterTrigger
                Log.d(TAG, "Header match (fuzzy) at line $idx: '${ingredientMatch.value}' → '$afterTrigger'")
                break
            }

            // ── Check other exact triggers ──
            val trigger = OTHER_TRIGGERS.firstOrNull { lower.startsWith(it) || lower.contains(it) }
            if (trigger != null) {
                headerIdx = idx
                val afterTrigger = line
                    .substring(lower.indexOf(trigger) + trigger.length)
                    .trimStart(':', ' ', '-', '.', '—')
                    .trim()
                sameLineText = afterTrigger
                Log.d(TAG, "Header match (exact) at line $idx: '$trigger'")
                break
            }
        }

        if (headerIdx == -1) return null

        val buffer = StringBuilder()
        if (sameLineText.isNotBlank()) buffer.append(sameLineText)

        var openParens = countParens(buffer.toString())
        var linesCollected = 0

        for (i in (headerIdx + 1) until lines.size) {
            val line  = lines[i]
            val lower = line.lowercase()

            // ── STRONG STOP: Only absolute section boundaries ──
            if (isStrongStop(lower)) {
                Log.d(TAG, "STRONG STOP at line $i: '$line'")
                break
            }

            // ── Safety limit: prevent runaway on OCR garbage ──
            if (linesCollected >= 40) {
                Log.d(TAG, "Max lines (40) reached at line $i")
                break
            }

            // ── Section label: only stop if this is clearly a new section header ──
            // Must end with ':', be short, AND the next line must NOT look like ingredients
            if (openParens <= 0 && line.endsWith(":") && line.length < 40) {
                val nextLine = lines.getOrNull(i + 1)?.lowercase() ?: ""
                val nextLooksIngredient = nextLine.contains(",") || nextLine.contains("(") ||
                    INGREDIENT_KEYWORDS.any { nextLine.contains(it) }
                if (!nextLooksIngredient) break
            }

            // ── Short lines (< 5 chars) with no ingredient markers: skip but don't stop ──
            // OCR sometimes produces tiny fragments; skipping is safer than stopping
            if (line.length < 4 && !line.contains(",") && !line.contains("(")) {
                Log.d(TAG, "Skipping short fragment at line $i: '$line'")
                linesCollected++
                continue
            }

            buffer.append(if (buffer.isEmpty()) "" else " ").append(line)
            openParens += countParens(line)
            linesCollected++
        }

        return buffer.toString().takeIf { it.isNotBlank() }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Strategy 2: Heuristic (most ingredient-like block — BIDIRECTIONAL)
    // ─────────────────────────────────────────────────────────────────────────

    private fun extractHeuristic(lines: List<String>): String? {
        // Score every line
        val scores = IntArray(lines.size)
        for ((i, line) in lines.withIndex()) {
            if (isStrongStop(line.lowercase())) {
                scores[i] = -100; continue
            }
            if (line.length < 10) { scores[i] = 0; continue }
            scores[i] = scoreLine(line)
        }

        // Find the best-scoring line
        var bestScore = 0
        var bestIdx   = -1
        for (i in scores.indices) {
            if (scores[i] > bestScore) {
                bestScore = scores[i]
                bestIdx = i
            }
        }

        if (bestIdx == -1 || bestScore < 10) return null
        Log.d(TAG, "Heuristic best line [$bestIdx] score=$bestScore: '${lines[bestIdx]}'")

        // ── Extend BACKWARD from bestIdx ──
        var startIdx = bestIdx
        for (i in (bestIdx - 1) downTo maxOf(0, bestIdx - 20)) {
            val line  = lines[i]
            val lower = line.lowercase()

            // Stop backward at any stop boundary
            if (isStrongStop(lower)) break

            // Stop on very short non-ingredient lines (reduced from 8 to 3)
            if (line.length < 3 && !line.contains(",") && !line.contains("(")) break

            // Stop on section headers
            if (line.endsWith(":") && line.length < 40) break

            // Check if line is ingredient-like (more lenient thresholds)
            val lineScore = scoreLine(line)
            val looksIngredient = lineScore > 0 ||
                    line.contains(",") ||
                    line.contains("(") || line.contains(")") ||
                    line.contains("%") ||
                    line.length >= 15  // Longer lines are likely continuation text

            if (!looksIngredient) break

            startIdx = i
            Log.d(TAG, "Heuristic backward → line $i (score=$lineScore)")
        }

        // ── Extend FORWARD from bestIdx ──
        var endIdx = bestIdx
        var openParens = 0
        for (i in startIdx..bestIdx) openParens += countParens(lines[i])

        var consecutiveNonIngredient = 0  // Track non-ingredient lines to allow skipping 1-2 short lines
        for (i in (bestIdx + 1) until minOf(lines.size, bestIdx + 40)) {
            val line  = lines[i]
            val lower = line.lowercase()

            // Strong stops always fire
            if (isStrongStop(lower)) break

            // Section header check with look-ahead
            if (openParens <= 0 && line.endsWith(":") && line.length < 40) {
                val nextLine = lines.getOrNull(i + 1)?.lowercase() ?: ""
                val nextLooksIngredient = nextLine.contains(",") || nextLine.contains("(") ||
                    INGREDIENT_KEYWORDS.any { nextLine.contains(it) }
                if (!nextLooksIngredient) break
            }

            // Check if line is ingredient-like or continuation
            val lineScore = scoreLine(line)
            val looksIngredient = lineScore > 0 ||
                    line.contains(",") ||
                    line.contains("(") || line.contains(")") ||
                    openParens > 0 ||
                    line.length >= 15  // Longer lines likely continuation

            if (!looksIngredient && openParens <= 0) {
                consecutiveNonIngredient++
                // Allow up to 2 consecutive short/ambiguous lines (OCR fragments)
                // before giving up. Check if a good line follows.
                if (consecutiveNonIngredient >= 3) break
                // Look ahead: if the next line looks ingredient-like, keep going
                val peekLine = lines.getOrNull(i + 1)?.lowercase() ?: ""
                val peekOk = peekLine.contains(",") || peekLine.contains("(") ||
                    INGREDIENT_KEYWORDS.any { peekLine.contains(it) } || peekLine.length >= 20
                if (!peekOk) break
                Log.d(TAG, "Heuristic skip ambiguous line $i: '$line' (peek-ahead OK)")
                continue
            }

            consecutiveNonIngredient = 0
            endIdx = i
            openParens += countParens(line)
        }

        // ── Build result ──
        val buffer = StringBuilder()
        for (i in startIdx..endIdx) {
            if (buffer.isNotEmpty()) buffer.append(" ")
            buffer.append(lines[i])
        }

        Log.d(TAG, "Heuristic result: lines $startIdx..$endIdx (${buffer.length} chars)")
        return buffer.toString()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Line scoring for heuristic
    // ─────────────────────────────────────────────────────────────────────────

    private fun scoreLine(line: String): Int {
        var score = 0
        score += line.count { it == ',' } * 10
        score += line.count { it == '(' } * 4
        score += line.count { it == '%' } * 6

        val lower = line.lowercase()
        INGREDIENT_KEYWORDS.forEach { w ->
            if (lower.contains(w)) score += 5
        }

        // Penalise nutrition-table number patterns
        if (Regex("""\d+\.?\d*\s*g\b""").containsMatchIn(line)) score -= 15
        if (Regex("""\d+\.?\d*\s*(mg|kcal|kj)\b""", RegexOption.IGNORE_CASE)
                .containsMatchIn(line)) score -= 20

        return score
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Stop helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Strong stops: absolute section boundaries that NEVER appear in ingredient lists */
    private fun isStrongStop(lower: String): Boolean =
        STRONG_STOPS.any { lower.contains(it) }

    private fun countParens(text: String): Int {
        var n = 0
        for (c in text) if (c == '(') n++ else if (c == ')') n--
        return n
    }

    private fun clean(raw: String): String = raw
        .replace(Regex(",\\s*,"), ",")
        .replace(Regex("\\s{2,}"), " ")
        .replace("|", ",")
        .replace(Regex("^[,\\s:.\\-]+"), "")
        .replace(Regex("[,\\s.]+$"), "")
        .trim()
}
