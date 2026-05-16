package com.macromind.foodscanner

/**
 * TextVectorizer
 * ===============
 * Converts an ingredient string into a fixed-length integer token array,
 * exactly matching how the Python training code tokenized text.
 *
 * Pipeline:
 *   raw text  →  lowercase + clean  →  split to tokens  →  map via vocab
 *             →  pad / truncate to SEQ_LEN  →  IntArray
 *
 * Unknown words are mapped to index 1 (OOV token, standard Keras convention).
 * Padding uses index 0.
 */
object TextVectorizer {

    private const val PAD_TOKEN = 0
    private const val OOV_TOKEN = 1

    /**
     * Tokenize ingredient text into a padded int sequence.
     *
     * @param text     raw ingredient string from OCR
     * @param vocab    token→index map loaded from vocab_GAP1.json
     * @param seqLen   fixed sequence length (from model_config.json)
     * @return         IntArray of length seqLen, zero-padded at end
     */
    fun vectorize(text: String, vocab: Map<String, Int>, seqLen: Int): IntArray {
        val cleaned = cleanText(text)
        val tokens  = tokenize(cleaned)

        val indices = IntArray(seqLen) { PAD_TOKEN }
        val take    = minOf(tokens.size, seqLen)

        for (i in 0 until take) {
            // Keras TextVectorization adds 2 to indices (0=pad, 1=OOV, 2+=real tokens)
            // If your vocab already encodes this way, direct lookup is correct.
            indices[i] = vocab[tokens[i]] ?: OOV_TOKEN
        }

        return indices
    }

    // ── Text cleaning ────────────────────────────────────────────────────────

    private fun cleanText(text: String): String {
        return text
            .lowercase()
            // Remove E-numbers and INS codes so they don't pollute word tokens
            // (additive detection is handled separately by IngredientParser)
            .replace(Regex("""e[-\s]?\d{3,4}[a-z]?"""), " eadditive ")
            .replace(Regex("""ins[-\s]?\d{3,4}"""),      " insadditive ")
            // Keep letters, digits, spaces — remove punctuation
            .replace(Regex("""[^a-z0-9\s]"""), " ")
            // Collapse multiple spaces
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun tokenize(text: String): List<String> =
        text.split(" ").filter { it.isNotBlank() }

    /**
     * Convert IntArray to FloatArray for TFLite input buffers.
     */
    fun toFloatArray(tokens: IntArray): FloatArray =
        FloatArray(tokens.size) { tokens[it].toFloat() }
}