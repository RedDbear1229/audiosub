package com.audiosub.translation

/**
 * Common interface for translation engines.
 *
 * Implementations must be called on a single-threaded dispatcher
 * (see [com.audiosub.util.CoroutineDispatcherProvider.translation]).
 */
interface TranslationEngine {
    /**
     * Translate [text] from [sourceLang] (BCP-47 code, e.g. "en", "ja") to Korean.
     * Returns translated Korean text, or an empty string on failure.
     */
    suspend fun translate(text: String, sourceLang: String): String

    /** Release native resources. */
    fun release()

    /** True if the engine is ready. */
    val isReady: Boolean
}
