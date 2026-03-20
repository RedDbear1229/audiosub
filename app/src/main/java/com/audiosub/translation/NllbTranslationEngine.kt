package com.audiosub.translation

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import com.audiosub.util.CoroutineDispatcherProvider
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

private const val TAG = "NllbTranslationEngine"

// NLLB-200-distilled-600M architecture constants
private const val NUM_DECODER_LAYERS = 12
private const val NUM_HEADS          = 16
private const val HEAD_DIM           = 64   // 1024 / 16
private const val MAX_DECODE_STEPS   = 200

/**
 * Translation engine backed by NLLB-200-distilled-600M (Xenova/HuggingFace ONNX).
 *
 * Model files expected in [modelDir]:
 *  - encoder_model.onnx            (or encoder_model_quantized.onnx)
 *  - decoder_model_merged.onnx     (or decoder_model_merged_quantized.onnx)
 *  - sentencepiece.bpe.model
 *
 * Decoder input/output naming follows HuggingFace Optimum export convention:
 *  Inputs:  input_ids, encoder_hidden_states, encoder_attention_mask,
 *           past_key_values.{i}.key, past_key_values.{i}.value
 *  Outputs: logits, present.{i}.key, present.{i}.value
 *
 * Reference: RTranslator (niedev/RTranslator) NLLB_CACHE inference logic.
 */
class NllbTranslationEngine(private val modelDir: File) : TranslationEngine {

    private var env: OrtEnvironment? = null
    private var encoderSession: OrtSession? = null
    private var decoderSession: OrtSession? = null
    private var tokenizer: NllbBpeTokenizer? = null

    override var isReady: Boolean = false
        private set

    init {
        try {
            loadModel()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load NLLB model", e)
        }
    }

    // -------------------------------------------------------------------------
    // Model loading
    // -------------------------------------------------------------------------

    private fun loadModel() {
        val encoderFile = listOf("encoder_model_quantized.onnx", "encoder_model.onnx")
            .map { File(modelDir, it) }.firstOrNull { it.exists() }
            ?: run { Log.w(TAG, "Encoder model not found in $modelDir"); return }

        val decoderFile = listOf("decoder_model_merged_quantized.onnx", "decoder_model_merged.onnx")
            .map { File(modelDir, it) }.firstOrNull { it.exists() }
            ?: run { Log.w(TAG, "Decoder model not found in $modelDir"); return }

        val spFile = File(modelDir, "sentencepiece.bpe.model")
        if (!spFile.exists()) {
            Log.w(TAG, "sentencepiece.bpe.model not found in $modelDir")
            return
        }

        val onnxEnv = OrtEnvironment.getEnvironment()
        env = onnxEnv

        val options = OrtSession.SessionOptions().apply {
            setMemoryPatternOptimization(false)
            setCPUArenaAllocator(false)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT)
        }

        Log.i(TAG, "Loading encoder: ${encoderFile.name} (${encoderFile.length() / 1_048_576} MB)")
        encoderSession = onnxEnv.createSession(encoderFile.absolutePath, options)

        Log.i(TAG, "Loading decoder: ${decoderFile.name} (${decoderFile.length() / 1_048_576} MB)")
        decoderSession = onnxEnv.createSession(decoderFile.absolutePath, options)

        Log.i(TAG, "Loading SentencePiece tokenizer…")
        tokenizer = NllbBpeTokenizer(spFile)

        options.close()
        isReady = true
        Log.i(TAG, "NLLB engine ready")
    }

    // -------------------------------------------------------------------------
    // TranslationEngine interface
    // -------------------------------------------------------------------------

    override suspend fun translate(text: String, sourceLang: String): String =
        withContext(CoroutineDispatcherProvider.translation) {
            if (!isReady) return@withContext text
            val tk   = tokenizer ?: return@withContext text
            val enc  = encoderSession ?: return@withContext text
            val dec  = decoderSession ?: return@withContext text
            val onnx = env ?: return@withContext text

            try {
                val srcFlores = TokenizerWrapper.toFlores200(sourceLang)
                val tgtFlores = TokenizerWrapper.KOREAN

                val startMs = System.currentTimeMillis()

                // 1. Tokenize
                val inputIds    = tk.encode(text, srcFlores)
                val attentionMk = IntArray(inputIds.size) { 1 }

                // 2. Encoder
                val encoderHidden = runEncoder(onnx, enc, inputIds, attentionMk)
                    ?: return@withContext text

                // 3. Greedy decoder
                val outputIds = greedyDecode(
                    onnx, dec, encoderHidden,
                    attentionMk,
                    tgtLangId = tk.getLanguageId(tgtFlores)
                )

                encoderHidden.close()

                // 4. Detokenize
                val result = tk.decode(outputIds)
                Log.i(TAG, "Translation done in ${System.currentTimeMillis() - startMs}ms: \"$result\"")
                result

            } catch (e: Exception) {
                Log.e(TAG, "Translation failed", e)
                text  // fallback: return original
            }
        }

    override fun release() {
        encoderSession?.close()
        decoderSession?.close()
        env?.close()
        encoderSession = null
        decoderSession = null
        env = null
        isReady = false
        Log.i(TAG, "NLLB engine released")
    }

    // -------------------------------------------------------------------------
    // Encoder inference
    // -------------------------------------------------------------------------

    private fun runEncoder(
        onnx: OrtEnvironment,
        session: OrtSession,
        inputIds: IntArray,
        attentionMask: IntArray
    ): OnnxTensor? {
        val seqLen = inputIds.size.toLong()

        val idsTensor = OnnxTensor.createTensor(
            onnx,
            LongBuffer.wrap(inputIds.map { it.toLong() }.toLongArray()),
            longArrayOf(1, seqLen)
        )
        val maskTensor = OnnxTensor.createTensor(
            onnx,
            LongBuffer.wrap(attentionMask.map { it.toLong() }.toLongArray()),
            longArrayOf(1, seqLen)
        )

        return try {
            val inputs = mapOf("input_ids" to idsTensor, "attention_mask" to maskTensor)
            val result = session.run(inputs)
            idsTensor.close()
            maskTensor.close()
            // Return last_hidden_state tensor (caller must close it)
            result.get("last_hidden_state").get() as OnnxTensor
        } catch (e: Exception) {
            idsTensor.close()
            maskTensor.close()
            Log.e(TAG, "Encoder failed", e)
            null
        }
    }

    // -------------------------------------------------------------------------
    // Greedy decoder with KV cache
    // -------------------------------------------------------------------------

    private fun greedyDecode(
        onnx: OrtEnvironment,
        session: OrtSession,
        encoderHidden: OnnxTensor,
        encoderAttentionMask: IntArray,
        tgtLangId: Int
    ): IntArray {
        val eos = NllbBpeTokenizer.EOS_ID
        val output = mutableListOf<Int>()

        val seqLen = encoderAttentionMask.size.toLong()
        val encMaskTensor = OnnxTensor.createTensor(
            onnx,
            LongBuffer.wrap(encoderAttentionMask.map { it.toLong() }.toLongArray()),
            longArrayOf(1, seqLen)
        )

        // NLLB decoder starts with EOS as BOS token
        var currentToken = NllbBpeTokenizer.EOS_ID.toLong()

        // Initialize empty KV cache [1, NUM_HEADS, 0, HEAD_DIM]
        var kvCache = createEmptyKVCache(onnx)

        try {
            for (step in 0 until MAX_DECODE_STEPS) {
                val inputIdsTensor = OnnxTensor.createTensor(
                    onnx,
                    LongBuffer.wrap(longArrayOf(currentToken)),
                    longArrayOf(1, 1)
                )

                val inputs = buildDecoderInputs(
                    inputIdsTensor, encoderHidden, encMaskTensor, kvCache
                )

                val result = session.run(inputs)
                inputIdsTensor.close()

                // Extract logits [1, 1, vocab_size] and find argmax
                val logits = result.get("logits").get() as OnnxTensor
                val nextToken = if (step == 0) {
                    // Force target language token as first output (NLLB convention)
                    tgtLangId
                } else {
                    argmax(logits)
                }
                logits.close()

                // Update KV cache from present.* tensors
                val newKvCache = extractKVCache(onnx, result)
                closeKVCache(kvCache)
                kvCache = newKvCache
                result.close()

                output.add(nextToken)
                if (nextToken == eos) break

                currentToken = nextToken.toLong()
            }
        } finally {
            encMaskTensor.close()
            closeKVCache(kvCache)
        }

        return output.toIntArray()
    }

    private fun buildDecoderInputs(
        inputIds: OnnxTensor,
        encoderHidden: OnnxTensor,
        encMask: OnnxTensor,
        kvCache: Array<OnnxTensor>
    ): Map<String, OnnxTensor> {
        val inputs = mutableMapOf<String, OnnxTensor>(
            "input_ids"               to inputIds,
            "encoder_hidden_states"   to encoderHidden,
            "encoder_attention_mask"  to encMask
        )
        for (i in 0 until NUM_DECODER_LAYERS) {
            inputs["past_key_values.$i.key"]   = kvCache[i * 2]
            inputs["past_key_values.$i.value"] = kvCache[i * 2 + 1]
        }
        return inputs
    }

    /** Empty KV cache for the first decoder step: [1, NUM_HEADS, 0, HEAD_DIM]. */
    private fun createEmptyKVCache(onnx: OrtEnvironment): Array<OnnxTensor> {
        val shape = longArrayOf(1, NUM_HEADS.toLong(), 0, HEAD_DIM.toLong())
        return Array(NUM_DECODER_LAYERS * 2) {
            OnnxTensor.createTensor(onnx, FloatBuffer.wrap(FloatArray(0)), shape)
        }
    }

    /** Extract present.*.key/value from decoder result into a new KV cache array. */
    private fun extractKVCache(onnx: OrtEnvironment, result: OrtSession.Result): Array<OnnxTensor> {
        return Array(NUM_DECODER_LAYERS * 2) { idx ->
            val layer    = idx / 2
            val keyOrVal = if (idx % 2 == 0) "key" else "value"
            val name     = "present.$layer.$keyOrVal"
            // Copy tensor data so we can close the result safely
            val src = result.get(name).get() as OnnxTensor
            val shape = src.info.shape
            val buf = src.floatBuffer
            val data = FloatArray(buf.remaining())
            buf.get(data)
            OnnxTensor.createTensor(onnx, FloatBuffer.wrap(data), shape)
        }
    }

    private fun closeKVCache(kv: Array<OnnxTensor>) {
        kv.forEach { try { it.close() } catch (_: Exception) {} }
    }

    private fun argmax(logits: OnnxTensor): Int {
        val buf = logits.floatBuffer
        var maxIdx = 0
        var maxVal = buf[0]
        val size = buf.remaining()
        for (i in 1 until size) {
            val v = buf[i]
            if (v > maxVal) { maxVal = v; maxIdx = i }
        }
        return maxIdx
    }
}
