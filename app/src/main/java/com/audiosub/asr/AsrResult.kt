package com.audiosub.asr

/**
 * Result from ASR inference.
 *
 * @param text Transcribed text (may be empty for silence).
 * @param language BCP-47 language code detected by the model (e.g. "en", "ja", "zh").
 *                 Null when language detection is not supported by the engine.
 * @param confidence Confidence score in [0.0, 1.0], or -1 if unavailable.
 */
data class AsrResult(
    val text: String,
    val language: String? = null,
    val confidence: Float = -1f
) {
    val isEmpty: Boolean get() = text.isBlank()
}
