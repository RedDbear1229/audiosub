package com.audiosub.translation

/**
 * Maps BCP-47 / Whisper language codes to FLORES-200 language codes required by NLLB-200.
 *
 * Reference: https://github.com/facebookresearch/flores/tree/main/flores200#languages-in-flores-200
 */
object TokenizerWrapper {

    /**
     * Convert a BCP-47 language tag (as returned by Whisper) to its FLORES-200 counterpart.
     * Falls back to English if the code is unknown.
     */
    fun toFlores200(bcp47: String): String =
        BCP47_TO_FLORES200[bcp47.lowercase()] ?: "eng_Latn"

    private val BCP47_TO_FLORES200: Map<String, String> = mapOf(
        "af" to "afr_Latn",
        "am" to "amh_Ethi",
        "ar" to "arb_Arab",
        "az" to "azj_Latn",
        "be" to "bel_Cyrl",
        "bg" to "bul_Cyrl",
        "bn" to "ben_Beng",
        "ca" to "cat_Latn",
        "cs" to "ces_Latn",
        "cy" to "cym_Latn",
        "da" to "dan_Latn",
        "de" to "deu_Latn",
        "el" to "ell_Grek",
        "en" to "eng_Latn",
        "es" to "spa_Latn",
        "et" to "est_Latn",
        "eu" to "eus_Latn",
        "fa" to "pes_Arab",
        "fi" to "fin_Latn",
        "fr" to "fra_Latn",
        "ga" to "gle_Latn",
        "gl" to "glg_Latn",
        "gu" to "guj_Gujr",
        "he" to "heb_Hebr",
        "hi" to "hin_Deva",
        "hr" to "hrv_Latn",
        "hu" to "hun_Latn",
        "hy" to "hye_Armn",
        "id" to "ind_Latn",
        "is" to "isl_Latn",
        "it" to "ita_Latn",
        "ja" to "jpn_Jpan",
        "ka" to "kat_Geor",
        "kk" to "kaz_Cyrl",
        "km" to "khm_Khmr",
        "kn" to "kan_Knda",
        "ko" to "kor_Hang",
        "lt" to "lit_Latn",
        "lv" to "lvs_Latn",
        "mk" to "mkd_Cyrl",
        "ml" to "mal_Mlym",
        "mn" to "khk_Cyrl",
        "mr" to "mar_Deva",
        "ms" to "zsm_Latn",
        "mt" to "mlt_Latn",
        "my" to "mya_Mymr",
        "ne" to "npi_Deva",
        "nl" to "nld_Latn",
        "no" to "nob_Latn",
        "pl" to "pol_Latn",
        "pt" to "por_Latn",
        "ro" to "ron_Latn",
        "ru" to "rus_Cyrl",
        "si" to "sin_Sinh",
        "sk" to "slk_Latn",
        "sl" to "slv_Latn",
        "sq" to "als_Latn",
        "sr" to "srp_Cyrl",
        "sv" to "swe_Latn",
        "sw" to "swh_Latn",
        "ta" to "tam_Taml",
        "te" to "tel_Telu",
        "th" to "tha_Thai",
        "tl" to "tgl_Latn",
        "tr" to "tur_Latn",
        "uk" to "ukr_Cyrl",
        "ur" to "urd_Arab",
        "uz" to "uzn_Latn",
        "vi" to "vie_Latn",
        "zh" to "zho_Hans",
        "zh-tw" to "zho_Hant"
    )

    /** FLORES-200 code for Korean (target language). */
    const val KOREAN = "kor_Hang"
}
