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
        val requiredFiles: List<String>
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
        requiredFiles = listOf("medium-encoder.int8.onnx", "medium-decoder.int8.onnx", "medium-tokens.txt")
    )

    // -------------------------------------------------------------------------
    // NLLB-200 Translation — individual files from HuggingFace (Xenova ONNX)
    // Repo: https://huggingface.co/Xenova/nllb-200-distilled-600M
    // NOTE: Verify URLs before first use. HuggingFace CDN requires browser
    //       User-Agent on some endpoints — set in DownloadWorker.
    // -------------------------------------------------------------------------

    val NLLB_600M = ModelBundle(
        id          = "nllb-600m",
        displayName = "NLLB-200 번역 (600M)",
        description = "200개 언어 → 한국어 번역 · ~900 MB",
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
                ),
                DownloadSource.FileEntry(
                    name      = "tokenizer_config.json",
                    url       = "https://huggingface.co/Xenova/nllb-200-distilled-600M/resolve/main/tokenizer_config.json",
                    sizeBytes = 544L
                )
            )
        ),
        requiredFiles = listOf(
            "encoder_model.onnx",
            "decoder_model_merged.onnx",
            "sentencepiece.bpe.model",
            "tokenizer_config.json"
        )
    )

    // -------------------------------------------------------------------------
    // Active configuration
    // -------------------------------------------------------------------------

    /** ASR model in use. Switch to [WHISPER_MEDIUM] after confirming device RAM. */
    val ACTIVE_ASR: ModelBundle = WHISPER_TINY

    /** Models required for ASR-only operation (Phase 3). */
    val ALL_ASR: List<ModelBundle> = listOf(ACTIVE_ASR)

    /** Models required for full ASR + translation (Phase 4+). */
    val ALL: List<ModelBundle> = listOf(ACTIVE_ASR, NLLB_600M)

    /** All known bundles (for management UI). */
    val CATALOG: List<ModelBundle> = listOf(WHISPER_TINY, WHISPER_MEDIUM, NLLB_600M)
}
