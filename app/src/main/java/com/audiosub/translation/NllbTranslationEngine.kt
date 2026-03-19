package com.audiosub.translation

import android.util.Log
import com.audiosub.util.CoroutineDispatcherProvider
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "NllbTranslationEngine"

/**
 * Translation engine backed by NLLB-200-distilled-600M ONNX model.
 *
 * ## Phase 4 implementation plan
 *
 * The full implementation requires:
 *  1. SentencePiece tokenizer — either via OrtExtensions (sentencepiece custom op)
 *     or a Java SentencePiece library (com.github.google:sentencepiece).
 *  2. ONNX Runtime session for encoder_model.onnx + decoder_model_merged.onnx.
 *  3. Greedy / beam decoder loop using OnnxTensor created from LongBuffer.
 *
 * Until Phase 4 lands, this class compiles cleanly but [translate] returns
 * the original text unchanged (pass-through fallback).
 *
 * Model files expected in [modelDir]:
 *  - encoder_model.onnx
 *  - decoder_model_merged.onnx
 *  - sentencepiece.bpe.model
 *  - tokenizer_config.json
 */
class NllbTranslationEngine(
    private val modelDir: File
) : TranslationEngine {

    override val isReady: Boolean = false // Phase 4: set true when sessions are loaded

    init {
        Log.i(TAG, "NllbTranslationEngine created (Phase 4 stub) — model dir: $modelDir")
        Log.w(TAG, "Translation is a no-op until Phase 4 implementation is complete")
    }

    /**
     * Phase 4 stub: returns [text] unchanged.
     *
     * Full implementation will:
     *  1. Tokenize [text] with SentencePiece using [sourceLang] as source flores-200 code.
     *  2. Run ONNX encoder session → hidden states.
     *  3. Run ONNX decoder session in a greedy loop (beam=1) with [TokenizerWrapper.KOREAN]
     *     as the forced BOS token.
     *  4. Detokenize output token IDs with SentencePiece.
     */
    override suspend fun translate(text: String, sourceLang: String): String =
        withContext(CoroutineDispatcherProvider.translation) {
            Log.d(TAG, "translate() stub — returning original text (lang=$sourceLang)")
            text // pass-through until Phase 4
        }

    override fun release() {
        Log.i(TAG, "NllbTranslationEngine released (Phase 4 stub)")
    }
}
