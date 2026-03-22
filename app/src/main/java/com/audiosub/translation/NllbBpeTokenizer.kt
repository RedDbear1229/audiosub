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
/**
 * @param applyIdOffset If true, applies +1 to BPE token IDs (required for RTranslator split models).
 *   Special tokens (0-3) and language tokens are unaffected.
 */
class NllbBpeTokenizer(modelFile: File, private val applyIdOffset: Boolean = false) {

    companion object {
        const val EOS_ID = 2
        const val UNK_ID = 3

        /** fairseq adds 1 extra offset after SPM vocab (for <mask> token). */
        private const val FAIRSEQ_OFFSET = 1

        /**
         * FLORES-200 language codes in the exact order used by HuggingFace NLLB tokenizer.
         * Language token ID = spm_vocab_size + FAIRSEQ_OFFSET + index.
         */
        private val FAIRSEQ_LANGUAGE_CODES = listOf(
            "ace_Arab","ace_Latn","acm_Arab","acq_Arab","aeb_Arab","afr_Latn","ajp_Arab","aka_Latn",
            "amh_Ethi","apc_Arab","arb_Arab","ars_Arab","ary_Arab","arz_Arab","asm_Beng","ast_Latn",
            "awa_Deva","ayr_Latn","azb_Arab","azj_Latn","bak_Cyrl","bam_Latn","ban_Latn","bel_Cyrl",
            "bem_Latn","ben_Beng","bho_Deva","bjn_Arab","bjn_Latn","bod_Tibt","bos_Latn","bug_Latn",
            "bul_Cyrl","cat_Latn","ceb_Latn","ces_Latn","cjk_Latn","ckb_Arab","crh_Latn","cym_Latn",
            "dan_Latn","deu_Latn","dik_Latn","dyu_Latn","dzo_Tibt","ell_Grek","eng_Latn","epo_Latn",
            "est_Latn","eus_Latn","ewe_Latn","fao_Latn","pes_Arab","fij_Latn","fin_Latn","fon_Latn",
            "fra_Latn","fur_Latn","fuv_Latn","gla_Latn","gle_Latn","glg_Latn","grn_Latn","guj_Gujr",
            "hat_Latn","hau_Latn","heb_Hebr","hin_Deva","hne_Deva","hrv_Latn","hun_Latn","hye_Armn",
            "ibo_Latn","ilo_Latn","ind_Latn","isl_Latn","ita_Latn","jav_Latn","jpn_Jpan","kab_Latn",
            "kac_Latn","kam_Latn","kan_Knda","kas_Arab","kas_Deva","kat_Geor","knc_Arab","knc_Latn",
            "kaz_Cyrl","kbp_Latn","kea_Latn","khm_Khmr","kik_Latn","kin_Latn","kir_Cyrl","kmb_Latn",
            "kon_Latn","kor_Hang","kmr_Latn","lao_Laoo","lvs_Latn","lij_Latn","lim_Latn","lin_Latn",
            "lit_Latn","lmo_Latn","ltg_Latn","ltz_Latn","lua_Latn","lug_Latn","luo_Latn","lus_Latn",
            "mag_Deva","mai_Deva","mal_Mlym","mar_Deva","min_Latn","mkd_Cyrl","plt_Latn","mlt_Latn",
            "mni_Beng","khk_Cyrl","mos_Latn","mri_Latn","zsm_Latn","mya_Mymr","nld_Latn","nno_Latn",
            "nob_Latn","npi_Deva","nso_Latn","nus_Latn","nya_Latn","oci_Latn","gaz_Latn","ory_Orya",
            "pag_Latn","pan_Guru","pap_Latn","pol_Latn","por_Latn","prs_Arab","pbt_Arab","quy_Latn",
            "ron_Latn","run_Latn","rus_Cyrl","sag_Latn","san_Deva","sat_Beng","scn_Latn","shn_Mymr",
            "sin_Sinh","slk_Latn","slv_Latn","smo_Latn","sna_Latn","snd_Arab","som_Latn","sot_Latn",
            "spa_Latn","als_Latn","srd_Latn","srp_Cyrl","ssw_Latn","sun_Latn","swe_Latn","swh_Latn",
            "szl_Latn","tam_Taml","tat_Cyrl","tel_Telu","tgk_Cyrl","tgl_Latn","tha_Thai","tir_Ethi",
            "taq_Latn","taq_Tfng","tpi_Latn","tsn_Latn","tso_Latn","tuk_Latn","tum_Latn","tur_Latn",
            "twi_Latn","tzm_Tfng","uig_Arab","ukr_Cyrl","umb_Latn","urd_Arab","uzn_Latn","vec_Latn",
            "vie_Latn","war_Latn","wol_Latn","xho_Latn","ydd_Hebr","yor_Latn","yue_Hant","zho_Hans",
            "zho_Hant","zul_Latn"
        )
    }

    private val pieces: List<NllbSentencePieceModel.Piece>
    private val tokenToId: Map<String, Int>
    /** Total vocab size including language tokens (needed for decode). */
    private val langIdToCode: Map<Int, String>

    /** Number of SPM pieces loaded (should be ~256,000 for NLLB). */
    val vocabSize: Int get() = pieces.size

    init {
        Log.i(TAG, "Loading SentencePiece model from ${modelFile.name}…")
        pieces = NllbSentencePieceModel.load(modelFile)

        val map = HashMap<String, Int>(pieces.size + FAIRSEQ_LANGUAGE_CODES.size + 16)
        pieces.forEachIndexed { id, p -> map[p.piece] = id }

        // Add FLORES-200 language tokens after SPM vocab + fairseq offset
        val langMap = HashMap<Int, String>(FAIRSEQ_LANGUAGE_CODES.size)
        FAIRSEQ_LANGUAGE_CODES.forEachIndexed { idx, code ->
            val id = pieces.size + FAIRSEQ_OFFSET + idx
            map[code] = id
            langMap[id] = code
        }
        tokenToId = map
        langIdToCode = langMap

        Log.i(TAG, "Vocabulary: ${pieces.size} SPM + ${FAIRSEQ_LANGUAGE_CODES.size} lang tokens")
        Log.i(TAG, "eng_Latn=${map["eng_Latn"]}, kor_Hang=${map["kor_Hang"]}")
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
        // RTranslator split model requires +1 offset on BPE tokens (IDs >= 4).
        // Special tokens (0-3) and language tokens are unaffected.
        val adjusted = if (applyIdOffset) bpeTokens.map { if (it >= 4) it + 1 else it }.toIntArray()
                       else bpeTokens
        val result = IntArray(1 + adjusted.size + 1)
        result[0] = getLanguageId(srcLang)
        adjusted.copyInto(result, destinationOffset = 1)
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
            .map { if (applyIdOffset && it >= 5) it - 1 else it }  // undo +1 offset on BPE tokens
            .filter { it in pieces.indices && !isSpecialToken(it) }
            .joinToString("") { pieces[it].piece }
            .replace("▁", " ")
            .trim()
    }

    private fun isSpecialToken(id: Int): Boolean =
        id <= 3 || id in langIdToCode

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
