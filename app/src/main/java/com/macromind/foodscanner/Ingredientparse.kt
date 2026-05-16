package com.macromind.foodscanner

/**
 * IngredientParser
 * =================
 * Kotlin port of MacroMind_Backend/Parsing/ingredient_parser.py
 *
 * Detects food additives in an ingredient string and looks each one up
 * in the additive_database.json loaded by AssetLoader.
 *
 * DETECTION METHODS (95%+ coverage):
 *   1. E-numbers         →  E330, E-330, E 330, e330, E330a
 *   2. INS codes         →  INS 1422, INS412
 *   3. Bare numbers      →  "635", "508" (validated against DB)
 *   4. Chemical names    →  MSG, citric acid, monosodium glutamate
 *   5. Category keywords →  "flavour enhancer", "thickener", "preservative"
 *
 * Usage:
 *   val results = IngredientParser.detect(ingredientsText)
 */
object IngredientParser {

    // ── Regex patterns ────────────────────────────────────────────────────────

    // E-numbers: E330, E-330, E 330, e330a, E1422
    private val E_NUMBER_REGEX  = Regex("""[Ee]-?\s*(\d{3,4}[a-zA-Z]?)""")

    // INS codes: INS 1422, INS412, ins1422
    private val INS_REGEX       = Regex("""[Ii][Nn][Ss]\.?\s*(\d{3,4})""")

    // Bare numbers standing alone, with optional letter suffix (e.g., "635", "160c")
    private val BARE_NUM_REGEX  = Regex("""(?<![.\d])(\d{3,4}[a-zA-Z]?)(?![.\d%])""")

    // Parenthesized groups containing additive numbers: (330,296,334) or (551) or (160c)
    // Only match short groups (no more than ~60 chars) to avoid matching long ingredient sub-lists
    private val PAREN_GROUP_REGEX = Regex("""\(([^)]{1,60})\)""")
    private val NUM_IN_GROUP     = Regex("""(\d{3,4}[a-zA-Z]?)""")

    // ── Chemical / common name lookup ─────────────────────────────────────────

    // Maps common names and synonyms → E/INS code
    // Extend this as needed; the additive_database.json is the authoritative source
    private val CHEMICAL_NAME_MAP = mapOf(
        "monosodium glutamate"      to "E621",
        "msg"                       to "E621",
        "aspartame"                 to "E951",
        "saccharin"                 to "E954",
        "acesulfame"                to "E950",
        "acesulfame k"              to "E950",
        "acesulfame potassium"      to "E950",
        "sodium benzoate"           to "E211",
        "potassium sorbate"         to "E202",
        "sorbic acid"               to "E200",
        "benzoic acid"              to "E210",
        "citric acid"               to "E330",
        "tartaric acid"             to "E334",
        "malic acid"                to "E296",
        "lactic acid"               to "E270",
        "phosphoric acid"           to "E338",
        "acetic acid"               to "E260",
        "ascorbic acid"             to "E300",
        "vitamin c"                 to "E300",
        "tocopherol"                to "E306",
        "vitamin e"                 to "E306",
        "sodium nitrate"            to "E251",
        "sodium nitrite"            to "E250",
        "sodium sulphite"           to "E221",
        "sodium sulfite"            to "E221",
        "sulphur dioxide"           to "E220",
        "sulfur dioxide"            to "E220",
        "sodium metabisulphite"     to "E223",
        "sodium metabisulfite"      to "E223",
        "calcium propionate"        to "E282",
        "sodium propionate"         to "E281",
        "propionic acid"            to "E280",
        "carrageenan"               to "E407",
        "xanthan gum"               to "E415",
        "guar gum"                  to "E412",
        "locust bean gum"           to "E410",
        "gellan gum"                to "E418",
        "pectin"                    to "E440",
        "lecithin"                  to "E322",
        "soy lecithin"              to "E322",
        "sunflower lecithin"        to "E322",
        "mono and diglycerides"     to "E471",
        "mono- and diglycerides"    to "E471",
        "sodium stearoyl lactylate" to "E481",
        "ssl"                       to "E481",
        "calcium stearoyl lactylate" to "E482",
        "csl"                       to "E482",
        "polysorbate 80"            to "E433",
        "polysorbate 60"            to "E435",
        "polysorbate 20"            to "E432",
        "sodium chloride"           to "E508",
        "modified starch"           to "E1422",
        "hydroxypropyl distarch phosphate" to "E1442",
        "acetylated distarch adipate"      to "E1422",
        "disodium guanylate"        to "E627",
        "disodium inosinate"        to "E631",
        "disodium ribonucleotide"   to "E635",
        "ribonucleotide"            to "E635",
        "curcumin"                  to "E100",
        "annatto"                   to "E160b",
        "caramel"                   to "E150",
        "caramel colour"            to "E150d",
        "turmeric"                  to "E100",
        "beta carotene"             to "E160a",
        "tartrazine"                to "E102",
        "sunset yellow"             to "E110",
        "carmoisine"                to "E122",
        "brilliant blue"            to "E133",
        "indigotine"                to "E132",
        "erythrosine"               to "E127",
        "bha"                       to "E320",
        "butylated hydroxyanisole"  to "E320",
        "bht"                       to "E321",
        "butylated hydroxytoluene"  to "E321",
        "tbhq"                      to "E319",
        "tertiary butylhydroquinone" to "E319",
        "sodium saccharin"          to "E954",
        "steviol glycosides"        to "E960",
        "stevia"                    to "E960",
        "sucralose"                 to "E955",
        "sorbitol"                  to "E420",
        "mannitol"                  to "E421",
        "maltitol"                  to "E965",
        "xylitol"                   to "E967"
    )

    // Category keyword → generic severity (when no DB entry found)
    private val CATEGORY_KEYWORDS = mapOf(
        "flavour enhancer"   to "moderate",
        "flavor enhancer"    to "moderate",
        "preservative"       to "minor",
        "antioxidant"        to "none",
        "emulsifier"         to "none",
        "stabilizer"         to "none",
        "stabiliser"         to "none",
        "thickener"          to "none",
        "colour"             to "minor",
        "color"              to "minor",
        "food colour"        to "minor",
        "food color"         to "minor",
        "artificial colour"  to "moderate",
        "artificial color"   to "moderate",
        "acidity regulator"  to "none",
        "anti-caking agent"  to "none",
        "anticaking agent"   to "none",
        "raising agent"      to "none",
        "leavening agent"    to "none",
        "bulking agent"      to "none",
        "glazing agent"      to "none",
        "humectant"          to "none",
        "sequestrant"        to "none",
        "bleaching agent"    to "minor",
        "firming agent"      to "none",
        "foaming agent"      to "none",
        "anti-foaming agent" to "none",
        "propellant"         to "minor",
        "sweetener"          to "minor",
        "artificial sweetener" to "moderate"
    )

    // ── Main detection function ───────────────────────────────────────────────

    /**
     * Detect all food additives in an ingredient string.
     *
     * @param  ingredientText  raw ingredient string from OCR + TextParser
     * @return                 list of detected additives, deduplicated by code
     */
    fun detect(ingredientText: String): List<DetectedAdditive> {
        val text      = ingredientText.lowercase()
        val detected  = mutableMapOf<String, DetectedAdditive>()  // code → result
        val db        = AssetLoader.additiveDatabase

        // Build lookup maps from DB (case-insensitive)
        val dbByECode = db.associateBy { it.code.uppercase().replace("-", "").replace(" ", "") }
        val dbByIns   = db.associateBy { it.insCode.replace(" ", "") }

        // ── 1. E-numbers ──────────────────────────────────────────────────────
        E_NUMBER_REGEX.findAll(ingredientText).forEach { match ->
            val raw  = match.groupValues[1]
            val code = "E${raw.uppercase()}"
            val normalized = code.replace("-", "").replace(" ", "")
            val entry = dbByECode[normalized] ?: dbByECode["E${raw.uppercase()}"]
            if (entry != null) {
                detected[code] = fromDbEntry(entry, matchedAs = code)
            } else {
                detected[code] = DetectedAdditive(
                    code         = code,
                    name         = code,
                    category     = "Unknown",
                    severity     = "none",
                    healthImpact = "",
                    matchMethod  = "e_number_unmatched"
                )
            }
        }

        // ── 2. INS codes ──────────────────────────────────────────────────────
        INS_REGEX.findAll(ingredientText).forEach { match ->
            val ins   = match.groupValues[1]
            val entry = dbByIns[ins] ?: db.firstOrNull {
                it.insCode.replace(" ", "") == ins
            }
            val key   = "INS$ins"
            if (entry != null) {
                detected[key] = fromDbEntry(entry, matchedAs = "INS $ins")
            } else {
                detected[key] = DetectedAdditive(
                    code         = key,
                    name         = "INS $ins",
                    category     = "Unknown",
                    severity     = "none",
                    healthImpact = "",
                    matchMethod  = "ins_unmatched"
                )
            }
        }

        // ── 3. Chemical names ─────────────────────────────────────────────────
        CHEMICAL_NAME_MAP.forEach { (chemName, eCode) ->
            if (text.contains(chemName)) {
                val normalized = eCode.replace("-", "").replace(" ", "").uppercase()
                val entry      = dbByECode[normalized]
                val key        = eCode
                if (!detected.containsKey(key)) {
                    if (entry != null) {
                        detected[key] = fromDbEntry(entry, matchedAs = chemName)
                    } else {
                        detected[key] = DetectedAdditive(
                            code         = eCode,
                            name         = chemName.replaceFirstChar { it.uppercase() },
                            category     = "Chemical",
                            severity     = "none",
                            healthImpact = "",
                            matchMethod  = "chemical_name"
                        )
                    }
                }
            }
        }

        // ── 4. Parenthesized groups: (330,296,334) or (551) or (160c) ────────────
        PAREN_GROUP_REGEX.findAll(ingredientText).forEach { groupMatch ->
            val groupContent = groupMatch.groupValues[1]
            // Only process groups that look like additive lists (mostly numbers/commas)
            // Skip groups that are clearly ingredient sub-lists (lots of letters)
            val letterCount = groupContent.count { it.isLetter() }
            val digitCount  = groupContent.count { it.isDigit() }
            if (digitCount == 0 || (letterCount > digitCount * 4 && digitCount < 3)) return@forEach

            NUM_IN_GROUP.findAll(groupContent).forEach { numMatch ->
                val num   = numMatch.groupValues[1].uppercase()
                val numDigits = num.filter { it.isDigit() }
                val entry = dbByECode["E$num"] ?: dbByECode["E${num.lowercase()}"]
                    ?: dbByIns[numDigits] ?: dbByECode["E$numDigits"]
                    ?: dbByECode["E${numDigits.trimStart('0')}"]
                if (entry != null) {
                    val key = entry.code.ifBlank { "E$num" }
                    if (!detected.containsKey(key)) {
                        detected[key] = fromDbEntry(entry, matchedAs = num)
                    }
                } else {
                    val key = "E$num"
                    if (!detected.containsKey(key) && !detected.containsKey("INS$numDigits")) {
                        detected[key] = DetectedAdditive(
                            code         = key,
                            name         = "Additive $num",
                            category     = "Food Additive",
                            severity     = "none",
                            healthImpact = "Additive $num detected but not in local database.",
                            matchMethod  = "paren_group_unmatched"
                        )
                    }
                }
            }
        }

        // ── 5. Bare numbers (matched in DB → full entry; unmatched → still added) ──
        BARE_NUM_REGEX.findAll(ingredientText).forEach { match ->
            val num   = match.groupValues[1].uppercase()
            val numDigits = num.filter { it.isDigit() }
            val entry = dbByECode["E$num"] ?: dbByECode["E${num.lowercase()}"]
                ?: dbByIns[numDigits] ?: dbByECode["E$numDigits"]
                ?: dbByECode["E${numDigits.trimStart('0')}"]
            if (entry != null) {
                val key = entry.code.ifBlank { "E$num" }
                if (!detected.containsKey(key)) {
                    detected[key] = fromDbEntry(entry, matchedAs = num)
                }
            } else {
                // Still register the additive even if not in our limited DB
                val key = "E$num"
                if (!detected.containsKey(key) && !detected.containsKey("INS$numDigits")) {
                    detected[key] = DetectedAdditive(
                        code         = key,
                        name         = "Additive $num",
                        category     = "Food Additive",
                        severity     = "none",
                        healthImpact = "Additive $num detected but not in local database.",
                        matchMethod  = "bare_number_unmatched"
                    )
                }
            }
        }

        // ── 6. Category keywords ──────────────────────────────────────────────
        CATEGORY_KEYWORDS.forEach { (keyword, severity) ->
            if (text.contains(keyword)) {
                // Only add if we haven't already identified an additive for this keyword
                val syntheticKey = "CAT:$keyword"
                if (!detected.values.any { it.category.lowercase() == keyword }) {
                    detected[syntheticKey] = DetectedAdditive(
                        code         = "",
                        name         = keyword.replaceFirstChar { it.uppercase() },
                        category     = keyword.replaceFirstChar { it.uppercase() },
                        severity     = severity,
                        healthImpact = "Contains $keyword.",
                        matchMethod  = "category_keyword"
                    )
                }
            }
        }

        // Suppress category_keyword entries only when a specific additive with
        // a matching category has already been detected
        val specificCategories = detected.values
            .filter { it.matchMethod != "category_keyword" }
            .map { it.category.lowercase() }
            .toSet()

        return detected.values.toList()
            .filter { entry ->
                if (entry.matchMethod == "category_keyword") {
                    // Only keep if no specific entry covers this category
                    !specificCategories.any { it.contains(entry.name.lowercase()) ||
                                              entry.name.lowercase().contains(it) }
                } else true
            }
            .sortedByDescending { severityScore(it.severity) }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun fromDbEntry(entry: AssetLoader.AdditiveEntry, matchedAs: String) =
        DetectedAdditive(
            code         = entry.code,
            insCode      = entry.insCode,
            name         = entry.name,
            category     = entry.category,
            severity     = entry.severity,
            healthImpact = entry.healthImpact,
            adiLimit     = entry.adiLimit,
            commonFoods  = entry.commonFoods,
            matchMethod  = "database",
            matchedAs    = matchedAs
        )

    private fun severityScore(severity: String) = when (severity.lowercase()) {
        "high"     -> 3
        "moderate" -> 2
        "minor"    -> 1
        else       -> 0
    }

    /** Count of additives with severity ≥ moderate */
    fun harmfulCount(additives: List<DetectedAdditive>): Int =
        additives.count { severityScore(it.severity) >= 2 }

    // ── Data class ───────────────────────────────────────────────────────────

    data class DetectedAdditive(
        val code:         String  = "",
        val insCode:      String  = "",
        val name:         String,
        val category:     String  = "",
        val severity:     String  = "none",  // none / minor / moderate / high
        val healthImpact: String  = "",
        val adiLimit:     String  = "",
        val commonFoods:  String  = "",
        val matchMethod:  String  = "unknown",
        val matchedAs:    String  = ""
    ) {
        val isHarmful: Boolean get() = severity == "moderate" || severity == "high"
        val severityColor: Int get() = when (severity.lowercase()) {
            "high"     -> android.graphics.Color.parseColor("#FF4444")
            "moderate" -> android.graphics.Color.parseColor("#FF8800")
            "minor"    -> android.graphics.Color.parseColor("#FFCC00")
            else       -> android.graphics.Color.parseColor("#44BB44")
        }
    }
}