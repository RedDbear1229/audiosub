package com.audiosub.model

/**
 * Registry of all downloadable model bundles.
 *
 * Each [ModelBundle] declares a [DownloadSource] — either a single tar.bz2 archive
 * (sherpa-onnx releases) or a list of individual files (HuggingFace repos).
 */
object ModelRegistry {

    // -------------------------------------------------------------------------
    // Download source strategies
    // -------------------------------------------------------------------------

    sealed class DownloadSource {

        /**
         * A single tar.bz2 archive that is extracted to multiple files.
         * Used for sherpa-onnx model releases.
         */
        data class Archive(
            val url: String,
            val sha256: String = "",
            val sizeBytes: Long
        ) : DownloadSource()

        /**
         * A list of individual files downloaded separately.
         * Used for HuggingFace model repos where files are served individually.
         */
        data class IndividualFiles(
            val files: List<FileEntry>
        ) : DownloadSource() {
            val totalSizeBytes: Long get() = files.sumOf { it.sizeBytes }
        }

        data class FileEntry(
            val name: String,       // destination filename inside the bundle directory
            val url: String,
            val sha256: String = "",
            val sizeBytes: Long
        )
    }

    // -------------------------------------------------------------------------
    // Bundle definition
    // -------------------------------------------------------------------------

    data class ModelBundle(
        val id: String,
        val displayName: String,
        val description: String,
        val source: DownloadSource,
        val requiredFiles: List<String>,
        val category: String = ""
    ) {
        val totalSizeBytes: Long get() = when (source) {
            is DownloadSource.Archive        -> source.sizeBytes
            is DownloadSource.IndividualFiles -> source.totalSizeBytes
        }
    }

    // -------------------------------------------------------------------------
    // Whisper ASR — sherpa-onnx tar.bz2 archives
    // Release page: https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models
    // -------------------------------------------------------------------------

    val WHISPER_TINY = ModelBundle(
        id          = "whisper-tiny",
        displayName = "Whisper Tiny (다국어)",
        description = "빠른 전사, 낮은 정확도 · ~116 MB",
        source = DownloadSource.Archive(
            url       = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-tiny.tar.bz2",
            sizeBytes = 116_204_861L
        ),
        requiredFiles = listOf("tiny-encoder.int8.onnx", "tiny-decoder.int8.onnx", "tiny-tokens.txt")
    )

    val WHISPER_MEDIUM = ModelBundle(
        id          = "whisper-medium",
        displayName = "Whisper Medium (다국어)",
        description = "높은 정확도 · ~1.9 GB · RAM 2 GB 이상 필요",
        source = DownloadSource.Archive(
            url       = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-medium.tar.bz2",
            sizeBytes = 1_931_372_882L
        ),
        requiredFiles = listOf("medium-encoder.int8.onnx", "medium-decoder.int8.onnx", "medium-tokens.txt"),
        category = "asr"
    )

    // -------------------------------------------------------------------------
    // NLLB-200 Translation — RTranslator split INT8 model (v2.0.0)
    // 4-session split: encoder / decoder / cache_initializer / embed_and_lm_head
    // 4x faster inference, 1.9x less RAM vs Xenova merged model.
    // Source: https://github.com/niedev/RTranslator/releases/tag/2.0.0
    // -------------------------------------------------------------------------

    private const val RTRANSLATOR_BASE =
        "https://github.com/niedev/RTranslator/releases/download/2.0.0"

    val NLLB_600M = ModelBundle(
        id          = "nllb-600m-v2",
        displayName = "NLLB-200 번역 (600M)",
        description = "200개 언어 → 한국어 번역 · ~950 MB · 고속 추론",
        source = DownloadSource.IndividualFiles(
            files = listOf(
                DownloadSource.FileEntry(
                    name      = "NLLB_encoder.onnx",
                    url       = "$RTRANSLATOR_BASE/NLLB_encoder.onnx",
                    sizeBytes = 266_487_014L
                ),
                DownloadSource.FileEntry(
                    name      = "NLLB_decoder.onnx",
                    url       = "$RTRANSLATOR_BASE/NLLB_decoder.onnx",
                    sizeBytes = 179_109_694L
                ),
                DownloadSource.FileEntry(
                    name      = "NLLB_cache_initializer.onnx",
                    url       = "$RTRANSLATOR_BASE/NLLB_cache_initializer.onnx",
                    sizeBytes = 25_368_443L
                ),
                DownloadSource.FileEntry(
                    name      = "NLLB_embed_and_lm_head.onnx",
                    url       = "$RTRANSLATOR_BASE/NLLB_embed_and_lm_head.onnx",
                    sizeBytes = 524_712_277L
                ),
                DownloadSource.FileEntry(
                    name      = "sentencepiece.bpe.model",
                    url       = "https://huggingface.co/Xenova/nllb-200-distilled-600M/resolve/main/sentencepiece.bpe.model",
                    sizeBytes = 4_852_054L
                )
            )
        ),
        requiredFiles = listOf(
            "NLLB_encoder.onnx",
            "NLLB_decoder.onnx",
            "NLLB_cache_initializer.onnx",
            "NLLB_embed_and_lm_head.onnx",
            "sentencepiece.bpe.model"
        ),
        category = "translation"
    )

    // Legacy Xenova merged model — kept for fallback/migration only, not shown in UI
    val NLLB_600M_LEGACY = ModelBundle(
        id          = "nllb-600m",
        displayName = "NLLB-200 번역 (600M, 구버전)",
        description = "Xenova merged 모델 · ~900 MB",
        source = DownloadSource.IndividualFiles(
            files = listOf(
                DownloadSource.FileEntry(
                    name      = "encoder_model.onnx",
                    url       = "https://huggingface.co/Xenova/nllb-200-distilled-600M/resolve/main/onnx/encoder_model_quantized.onnx",
                    sizeBytes = 419_120_483L
                ),
                DownloadSource.FileEntry(
                    name      = "decoder_model_merged.onnx",
                    url       = "https://huggingface.co/Xenova/nllb-200-distilled-600M/resolve/main/onnx/decoder_model_merged_quantized.onnx",
                    sizeBytes = 475_505_771L
                ),
                DownloadSource.FileEntry(
                    name      = "sentencepiece.bpe.model",
                    url       = "https://huggingface.co/Xenova/nllb-200-distilled-600M/resolve/main/sentencepiece.bpe.model",
                    sizeBytes = 4_852_054L
                )
            )
        ),
        requiredFiles = listOf(
            "encoder_model.onnx",
            "decoder_model_merged.onnx",
            "sentencepiece.bpe.model"
        )
    )

    // -------------------------------------------------------------------------
    // Streaming ASR — sherpa-onnx OnlineRecognizer (Zipformer Transducer)
    // Language-specific models for real-time streaming mode (~0.3-0.5s latency)
    // -------------------------------------------------------------------------

    private const val HF_STREAMING_EN =
        "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-02-21/resolve/main"
    private const val HF_STREAMING_KO =
        "https://huggingface.co/k2-fsa/sherpa-onnx-streaming-zipformer-korean-2024-06-16/resolve/main"
    private const val HF_STREAMING_ZH =
        "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20/resolve/main"
    private const val HF_STREAMING_JA =
        "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-ar_en_id_ja_ru_th_vi_zh-2025-02-10/resolve/main"

    val STREAMING_EN = ModelBundle(
        id          = "streaming-en",
        displayName = "스트리밍 ASR (영어)",
        description = "실시간 영어 인식 · ~128 MB",
        source = DownloadSource.IndividualFiles(
            files = listOf(
                DownloadSource.FileEntry("encoder.int8.onnx", "$HF_STREAMING_EN/encoder-epoch-99-avg-1.int8.onnx", sizeBytes = 126_967_571L),
                DownloadSource.FileEntry("decoder.int8.onnx", "$HF_STREAMING_EN/decoder-epoch-99-avg-1.int8.onnx", sizeBytes = 540_643L),
                DownloadSource.FileEntry("joiner.int8.onnx",  "$HF_STREAMING_EN/joiner-epoch-99-avg-1.int8.onnx",  sizeBytes = 259_380L),
                DownloadSource.FileEntry("tokens.txt",        "$HF_STREAMING_EN/tokens.txt",                       sizeBytes = 5_048L)
            )
        ),
        requiredFiles = listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt"),
        category = "streaming"
    )

    val STREAMING_KO = ModelBundle(
        id          = "streaming-ko",
        displayName = "스트리밍 ASR (한국어)",
        description = "실시간 한국어 인식 · ~133 MB",
        source = DownloadSource.IndividualFiles(
            files = listOf(
                DownloadSource.FileEntry("encoder.int8.onnx", "$HF_STREAMING_KO/encoder-epoch-99-avg-1.int8.onnx", sizeBytes = 126_968_852L),
                DownloadSource.FileEntry("decoder.int8.onnx", "$HF_STREAMING_KO/decoder-epoch-99-avg-1.int8.onnx", sizeBytes = 2_844_692L),
                DownloadSource.FileEntry("joiner.int8.onnx",  "$HF_STREAMING_KO/joiner-epoch-99-avg-1.int8.onnx",  sizeBytes = 2_581_421L),
                DownloadSource.FileEntry("tokens.txt",        "$HF_STREAMING_KO/tokens.txt",                       sizeBytes = 60_246L),
                DownloadSource.FileEntry("bpe.model",         "$HF_STREAMING_KO/bpe.model",                        sizeBytes = 314_212L)
            )
        ),
        requiredFiles = listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt", "bpe.model"),
        category = "streaming"
    )

    val STREAMING_ZH = ModelBundle(
        id          = "streaming-zh",
        displayName = "스트리밍 ASR (중국어)",
        description = "실시간 중국어 인식 (중영 이중언어) · ~198 MB",
        source = DownloadSource.IndividualFiles(
            files = listOf(
                DownloadSource.FileEntry("encoder.int8.onnx", "$HF_STREAMING_ZH/encoder-epoch-99-avg-1.int8.onnx", sizeBytes = 181_895_032L),
                DownloadSource.FileEntry("decoder.int8.onnx", "$HF_STREAMING_ZH/decoder-epoch-99-avg-1.int8.onnx", sizeBytes = 13_091_040L),
                DownloadSource.FileEntry("joiner.int8.onnx",  "$HF_STREAMING_ZH/joiner-epoch-99-avg-1.int8.onnx",  sizeBytes = 3_228_404L),
                DownloadSource.FileEntry("tokens.txt",        "$HF_STREAMING_ZH/tokens.txt",                       sizeBytes = 56_317L),
                DownloadSource.FileEntry("bpe.model",         "$HF_STREAMING_ZH/bpe.model",                        sizeBytes = 244_836L)
            )
        ),
        requiredFiles = listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt", "bpe.model"),
        category = "streaming"
    )

    /** Japanese — no dedicated streaming model exists; uses PengChengStarling multilingual
     *  (8 languages: ar/en/id/ja/ru/th/vi/zh). modelType = "zipformer2". */
    val STREAMING_JA = ModelBundle(
        id          = "streaming-ja",
        displayName = "스트리밍 ASR (일본어)",
        description = "실시간 일본어 인식 (다국어 모델) · ~339 MB",
        source = DownloadSource.IndividualFiles(
            files = listOf(
                DownloadSource.FileEntry("encoder.int8.onnx", "$HF_STREAMING_JA/encoder-epoch-75-avg-11-chunk-16-left-128.int8.onnx", sizeBytes = 296_583_597L),
                DownloadSource.FileEntry("decoder.onnx",      "$HF_STREAMING_JA/decoder-epoch-75-avg-11-chunk-16-left-128.onnx",      sizeBytes = 33_837_085L),
                DownloadSource.FileEntry("joiner.int8.onnx",  "$HF_STREAMING_JA/joiner-epoch-75-avg-11-chunk-16-left-128.int8.onnx",  sizeBytes = 8_257_421L),
                DownloadSource.FileEntry("tokens.txt",        "$HF_STREAMING_JA/tokens.txt",                                          sizeBytes = 195_244L),
                DownloadSource.FileEntry("bpe.model",         "$HF_STREAMING_JA/bpe.model",                                           sizeBytes = 476_049L)
            )
        ),
        requiredFiles = listOf("encoder.int8.onnx", "decoder.onnx", "joiner.int8.onnx", "tokens.txt", "bpe.model"),
        category = "streaming"
    )

    // -------------------------------------------------------------------------
    // Active configuration
    // -------------------------------------------------------------------------

    /** ASR model in use. */
    val ACTIVE_ASR: ModelBundle = WHISPER_MEDIUM

    /** Models required for ASR-only operation. */
    val ALL_ASR: List<ModelBundle> = listOf(ACTIVE_ASR)

    /** Models required for full ASR + translation. */
    val ALL: List<ModelBundle> = listOf(ACTIVE_ASR, NLLB_600M)

    val CATEGORY_LABELS = mapOf(
        "asr"         to "음성 인식 (ASR)",
        "translation" to "번역",
        "streaming"   to "스트리밍 ASR (실시간)"
    )

    /** All bundles shown in management UI (WHISPER_TINY and legacy NLLB excluded). */
    val CATALOG: List<ModelBundle> = listOf(
        WHISPER_MEDIUM, NLLB_600M,
        STREAMING_EN, STREAMING_KO, STREAMING_ZH, STREAMING_JA
    )
}
