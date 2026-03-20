package com.audiosub.translation

import android.util.Log
import java.io.File

private const val TAG = "NllbBpeTokenizer"

/**
 * SentencePiece BPE tokenizer for NLLB-200 models.
 *
 * Reads the sentencepiece.bpe.model protobuf file and implements:
 *  - encode(text, srcLang, tgtLang)  → IntArray of token IDs
 *  - decode(ids)                     → String
 *
 * NLLB encoding format:
 *   src_lang_token + bpe_tokens + EOS
 *
 * NLLB decoding forces the target language token as the first generated token
 * (handled externally in NllbTranslationEngine).
 */
class NllbBpeTokenizer(modelFile: File) {

    companion object {
        const val EOS_ID = 2
        const val UNK_ID = 3
        // NLLB special: CONTROL tokens at top of vocabulary (ids 0–3)
        // Language tokens appear after the main BPE vocabulary (id >= ~256000)
    }

    private val pieces: List<NllbSentencePieceModel.Piece>
    private val tokenToId: Map<String, Int>

    init {
        Log.i(TAG, "Loading SentencePiece model from ${modelFile.name}…")
        pieces = NllbSentencePieceModel.load(modelFile)
        tokenToId = HashMap<String, Int>(pieces.size * 2).also { map ->
            pieces.forEachIndexed { id, p -> map[p.piece] = id }
        }
        Log.i(TAG, "Vocabulary size: ${pieces.size}")
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Encode [text] for NLLB.
     * Result: [srcLangId, ...bpeTokens..., EOS_ID]
     * The target language token is NOT included here — it is forced as the
     * first decoder output in NllbTranslationEngine.
     */
    fun encode(text: String, srcLang: String): IntArray {
        val bpeTokens = encodeText(text)
        val result = IntArray(1 + bpeTokens.size + 1)
        result[0] = getLanguageId(srcLang)
        bpeTokens.copyInto(result, destinationOffset = 1)
        result[result.size - 1] = EOS_ID
        return result
    }

    /** Map a FLORES-200 language code (e.g. "eng_Latn") to its vocabulary ID. */
    fun getLanguageId(flores200: String): Int {
        val id = tokenToId[flores200]
        if (id == null) {
            Log.w(TAG, "Unknown language code: $flores200, falling back to eng_Latn")
            return tokenToId["eng_Latn"] ?: error("eng_Latn not in vocabulary")
        }
        return id
    }

    /** Convert token IDs back to a human-readable string. */
    fun decode(ids: IntArray): String {
        return ids
            .filter { it in pieces.indices }
            .joinToString("") { pieces[it].piece }
            .replace("▁", " ")
            .trim()
    }

    // -------------------------------------------------------------------------
    // BPE encoding internals
    // -------------------------------------------------------------------------

    private fun encodeText(text: String): IntArray {
        if (text.isBlank()) return IntArray(0)

        val words = text.trim().split(Regex("\\s+"))
        val allTokens = mutableListOf<Int>()

        words.forEachIndexed { _, word ->
            // SentencePiece marks space as ▁ before each word except possibly first.
            // Standard NLLB practice: ▁ before every word (including first).
            val prefixed = "▁$word"
            allTokens.addAll(encodeWord(prefixed))
        }

        return allTokens.toIntArray()
    }

    /**
     * Encode a single (space-prefixed) word using greedy BPE merge.
     *
     * Algorithm:
     * 1. Start with individual characters (or ▁ prefix + chars).
     * 2. Find the adjacent pair whose merged form has the highest score.
     * 3. Merge and repeat until no more merges possible.
     * 4. Map tokens → IDs (UNK if not in vocabulary).
     */
    private fun encodeWord(word: String): List<Int> {
        // Decompose into individual codepoints (handles multi-byte Unicode)
        val symbols = word.map { it.toString() }.toMutableList()

        while (symbols.size > 1) {
            var bestScore = Float.NEGATIVE_INFINITY
            var bestIdx   = -1

            for (i in 0 until symbols.size - 1) {
                val merged = symbols[i] + symbols[i + 1]
                val id = tokenToId[merged] ?: continue
                val score = pieces[id].score
                if (score > bestScore) {
                    bestScore = score
                    bestIdx   = i
                }
            }

            if (bestIdx == -1) break   // no more valid merges

            symbols[bestIdx] = symbols[bestIdx] + symbols[bestIdx + 1]
            symbols.removeAt(bestIdx + 1)
        }

        return symbols.map { token ->
            tokenToId[token] ?: run {
                // Handle OOV: try byte-fallback tokens <0xXX>
                val bytes = token.toByteArray(Charsets.UTF_8)
                if (bytes.size == 1) {
                    val hex = String.format("<0x%02X>", bytes[0].toInt() and 0xFF)
                    tokenToId[hex] ?: UNK_ID
                } else {
                    UNK_ID
                }
            }
        }
    }
}
