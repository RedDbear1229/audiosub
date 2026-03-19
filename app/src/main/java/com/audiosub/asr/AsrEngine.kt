package com.audiosub.asr

/**
 * Common interface for ASR engines.
 *
 * Implementations must be called on a single-threaded dispatcher
 * (see [com.audiosub.util.CoroutineDispatcherProvider.asr]).
 */
interface AsrEngine {
    /**
     * Transcribe [pcmFloat] (16 kHz, mono, float32 in [-1, 1]).
     * Returns an [AsrResult] with the transcribed text and detected language code.
     */
    suspend fun transcribe(pcmFloat: FloatArray): AsrResult

    /** Release native resources. Must be called when the engine is no longer needed. */
    fun release()

    /** True if the engine is ready to receive audio. */
    val isReady: Boolean
}
