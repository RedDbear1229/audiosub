package com.audiosub.translation

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import com.audiosub.util.CoroutineDispatcherProvider
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.nio.LongBuffer

private const val TAG = "NllbTranslationEngine"

// NLLB-200-distilled-600M architecture constants
private const val NUM_DECODER_LAYERS = 12
private const val NUM_HEADS          = 16
private const val HEAD_DIM           = 64    // hidden_dim / num_heads = 1024 / 16
private const val HIDDEN_DIM         = 1024
private const val MAX_DECODE_STEPS   = 50

/**
 * Translation engine backed by RTranslator's split NLLB-200-distilled-600M INT8 model.
 *
 * 4 ONNX sessions:
 *  - NLLB_encoder.onnx           : input_ids + attention_mask + embed_matrix → last_hidden_state
 *  - NLLB_cache_initializer.onnx : encoder_hidden_states → present.{i}.encoder.key/value (×12)
 *  - NLLB_decoder.onnx           : token + KV cache → pre_logits + updated decoder KV
 *  - NLLB_embed_and_lm_head.onnx : dual-mode — embedding lookup OR logits projection
 *
 * Token ID convention (RTranslator split model):
 *  - Special tokens 0-3 (BOS/PAD/EOS/UNK): no offset
 *  - BPE tokens >= 4: +1 offset applied during encode, reversed during decode
 *  - Language tokens: 256001 + FLORES-200 index (unchanged)
 */
class NllbTranslationEngine(private val modelDir: File) : TranslationEngine {

    private var env: OrtEnvironment? = null
    private var encoderSession: OrtSession? = null
    private var cacheInitSession: OrtSession? = null
    private var decoderSession: OrtSession? = null
    private var embedLmHeadSession: OrtSession? = null
    private var tokenizer: NllbBpeTokenizer? = null

    override var isReady: Boolean = false
        private set

    var initError: String = ""
        private set

    init {
        try {
            loadModel()
        } catch (e: Throwable) {
            initError = "${e.javaClass.simpleName}: ${e.message?.take(120)}"
            Log.e(TAG, "Failed to load NLLB model: $initError", e)
            isReady = false
        }
    }

    // -------------------------------------------------------------------------
    // Model loading
    // -------------------------------------------------------------------------

    private fun loadModel() {
        val encoderFile    = File(modelDir, "NLLB_encoder.onnx")
        val decoderFile    = File(modelDir, "NLLB_decoder.onnx")
        val cacheInitFile  = File(modelDir, "NLLB_cache_initializer.onnx")
        val embedLmFile    = File(modelDir, "NLLB_embed_and_lm_head.onnx")
        val spFile         = File(modelDir, "sentencepiece.bpe.model")

        val missing = listOf(encoderFile, decoderFile, cacheInitFile, embedLmFile, spFile)
            .filter { !it.exists() }.map { it.name }
        if (missing.isNotEmpty()) {
            initError = "파일 없음: ${missing.joinToString()}"
            Log.w(TAG, initError); return
        }

        Log.i(TAG, "Files OK — encoder=${encoderFile.length()/1_048_576}MB " +
                "decoder=${decoderFile.length()/1_048_576}MB " +
                "cacheInit=${cacheInitFile.length()/1_048_576}MB " +
                "embedLm=${embedLmFile.length()/1_048_576}MB")

        val onnxEnv = OrtEnvironment.getEnvironment()
        env = onnxEnv
        Log.i(TAG, "OrtEnvironment OK")

        val options = OrtSession.SessionOptions().apply {
            setMemoryPatternOptimization(false)
            setCPUArenaAllocator(false)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT)
            setIntraOpNumThreads(2)  // 디코더 스텝 내 행렬 연산 병렬화
        }

        Log.i(TAG, "Loading encoder…")
        encoderSession = onnxEnv.createSession(encoderFile.absolutePath, options)
        Log.i(TAG, "Encoder OK — inputs:${encoderSession!!.inputNames} outputs:${encoderSession!!.outputNames}")

        Log.i(TAG, "Loading cache initializer…")
        cacheInitSession = onnxEnv.createSession(cacheInitFile.absolutePath, options)
        Log.i(TAG, "CacheInit OK — inputs:${cacheInitSession!!.inputNames} outputs:${cacheInitSession!!.outputNames}")

        Log.i(TAG, "Loading decoder…")
        decoderSession = onnxEnv.createSession(decoderFile.absolutePath, options)
        Log.i(TAG, "Decoder OK — inputs:${decoderSession!!.inputNames} outputs:${decoderSession!!.outputNames}")

        Log.i(TAG, "Loading embed+lm_head…")
        embedLmHeadSession = onnxEnv.createSession(embedLmFile.absolutePath, options)
        Log.i(TAG, "EmbedLmHead OK — inputs:${embedLmHeadSession!!.inputNames} outputs:${embedLmHeadSession!!.outputNames}")

        Log.i(TAG, "Loading tokenizer (${spFile.length()/1024}KB)…")
        // applyIdOffset=true: RTranslator split model uses +1 BPE token offset
        val tk = NllbBpeTokenizer(spFile, applyIdOffset = true)
        if (tk.vocabSize < 10_000) {
            initError = "SPM 어휘 크기 이상: ${tk.vocabSize} (정상 ~256000). 파일 재다운로드 필요"
            Log.e(TAG, initError); return
        }
        tokenizer = tk
        Log.i(TAG, "Tokenizer OK — vocab=${tk.vocabSize}, kor_Hang=${tk.getLanguageId("kor_Hang")}")

        options.close()
        isReady = true
        Log.i(TAG, "NLLB split engine ready")
    }

    // -------------------------------------------------------------------------
    // TranslationEngine interface
    // -------------------------------------------------------------------------

    override suspend fun translate(text: String, sourceLang: String): String =
        withContext(CoroutineDispatcherProvider.translation) {
            if (!isReady) throw IllegalStateException("NLLB engine not ready")
            val tk      = tokenizer!!
            val onnx    = env!!
            val encSess = encoderSession!!
            val ciSess  = cacheInitSession!!
            val decSess = decoderSession!!
            val emSess  = embedLmHeadSession!!

            val srcFlores = TokenizerWrapper.toFlores200(sourceLang)
            val tgtFlores = TokenizerWrapper.KOREAN
            val tgtLangId = tk.getLanguageId(tgtFlores)
            val startMs   = System.currentTimeMillis()

            // 1. Tokenize
            val inputIds  = tk.encode(text, srcFlores)
            val attMask   = IntArray(inputIds.size) { 1 }
            Log.i(TAG, "Tokenized: ${inputIds.size} tokens  src=$srcFlores tgt=$tgtFlores(id=$tgtLangId)")

            // 2. Embed encoder input
            val encEmbedResult = runEmbedLmHead(onnx, emSess, inputIds, null, useLmHead = false)
            val encEmbedMatrix = encEmbedResult.get("embed_matrix").get() as OnnxTensor

            // 3. Encoder
            Log.i(TAG, "Running encoder…")
            val t0 = System.currentTimeMillis()
            val encoderResult = runEncoder(onnx, encSess, inputIds, attMask, encEmbedMatrix)
            encEmbedMatrix.close(); encEmbedResult.close()
            val hiddenState = encoderResult.get("last_hidden_state").get() as OnnxTensor
            Log.i(TAG, "Encoder done in ${System.currentTimeMillis()-t0}ms")

            // 4. Cache initializer — pre-compute encoder cross-attention KV (fixed for all steps)
            Log.i(TAG, "Running cache initializer…")
            val t1 = System.currentTimeMillis()
            val cacheInitResult = runCacheInit(onnx, ciSess, hiddenState)
            Log.i(TAG, "CacheInit done in ${System.currentTimeMillis()-t1}ms")

            // 5. Greedy decode
            Log.i(TAG, "Running greedy decoder…")
            val t2 = System.currentTimeMillis()
            val outputIds = greedyDecode(
                onnx, decSess, emSess,
                hiddenState, attMask, cacheInitResult, tgtLangId
            )
            Log.i(TAG, "Decoder done in ${System.currentTimeMillis()-t2}ms  tokens=${outputIds.toList()}")

            hiddenState.close()
            cacheInitResult.close()
            encoderResult.close()

            // 6. Detokenize
            val result = tk.decode(outputIds)
            Log.i(TAG, "Translation done in ${System.currentTimeMillis()-startMs}ms: \"$result\"")
            if (result.isBlank()) "[번역결과없음 토큰=${outputIds.toList()}]" else result
        }

    override fun release() {
        encoderSession?.close();    encoderSession    = null
        cacheInitSession?.close();  cacheInitSession  = null
        decoderSession?.close();    decoderSession    = null
        embedLmHeadSession?.close(); embedLmHeadSession = null
        env?.close();               env               = null
        isReady = false
        Log.i(TAG, "NLLB engine released")
    }

    // -------------------------------------------------------------------------
    // Encoder
    // -------------------------------------------------------------------------

    private fun runEncoder(
        onnx: OrtEnvironment,
        session: OrtSession,
        inputIds: IntArray,
        attMask: IntArray,
        embedMatrix: OnnxTensor
    ): OrtSession.Result {
        val seqLen = inputIds.size.toLong()
        val idsTensor = OnnxTensor.createTensor(
            onnx, LongBuffer.wrap(inputIds.map { it.toLong() }.toLongArray()), longArrayOf(1, seqLen)
        )
        val maskTensor = OnnxTensor.createTensor(
            onnx, LongBuffer.wrap(attMask.map { it.toLong() }.toLongArray()), longArrayOf(1, seqLen)
        )
        return try {
            session.run(mapOf(
                "input_ids"      to idsTensor,
                "attention_mask" to maskTensor,
                "embed_matrix"   to embedMatrix
            )).also { idsTensor.close(); maskTensor.close() }
        } catch (e: Exception) {
            idsTensor.close(); maskTensor.close(); throw e
        }
    }

    // -------------------------------------------------------------------------
    // Cache initializer
    // -------------------------------------------------------------------------

    private fun runCacheInit(
        @Suppress("UNUSED_PARAMETER") onnx: OrtEnvironment,
        session: OrtSession,
        hiddenState: OnnxTensor
    ): OrtSession.Result = session.run(mapOf("encoder_hidden_states" to hiddenState))

    // -------------------------------------------------------------------------
    // embed_and_lm_head (dual mode)
    // -------------------------------------------------------------------------

    /**
     * Dual-mode session:
     *  useLmHead=false → embedding lookup: input_ids → embed_matrix
     *  useLmHead=true  → LM head:          pre_logits → logits
     */
    private fun runEmbedLmHead(
        onnx: OrtEnvironment,
        session: OrtSession,
        inputIds: IntArray,
        preLogits: OnnxTensor?,
        useLmHead: Boolean
    ): OrtSession.Result {
        val seqLen = inputIds.size.toLong()
        val idsTensor = OnnxTensor.createTensor(
            onnx, LongBuffer.wrap(inputIds.map { it.toLong() }.toLongArray()), longArrayOf(1, seqLen)
        )
        val logitsTensor = preLogits ?: OnnxTensor.createTensor(
            onnx, FloatBuffer.wrap(FloatArray(HIDDEN_DIM)), longArrayOf(1, 1, HIDDEN_DIM.toLong())
        )
        val useLmHeadTensor = OnnxTensor.createTensor(
            onnx, ByteBuffer.wrap(byteArrayOf(if (useLmHead) 1 else 0)),
            longArrayOf(1), ai.onnxruntime.OnnxJavaType.BOOL
        )
        return try {
            session.run(mapOf(
                "input_ids"  to idsTensor,
                "pre_logits" to logitsTensor,
                "use_lm_head" to useLmHeadTensor
            )).also {
                idsTensor.close()
                if (preLogits == null) logitsTensor.close()
                useLmHeadTensor.close()
            }
        } catch (e: Exception) {
            idsTensor.close()
            if (preLogits == null) logitsTensor.close()
            useLmHeadTensor.close()
            throw e
        }
    }

    // -------------------------------------------------------------------------
    // Greedy decoder
    // -------------------------------------------------------------------------

    private fun greedyDecode(
        onnx: OrtEnvironment,
        decSess: OrtSession,
        emSess: OrtSession,
        @Suppress("UNUSED_PARAMETER") hiddenState: OnnxTensor,
        encAttMask: IntArray,
        cacheInitResult: OrtSession.Result,
        tgtLangId: Int
    ): IntArray {
        val eos = NllbBpeTokenizer.EOS_ID
        val output = mutableListOf<Int>()

        val seqLen = encAttMask.size.toLong()
        val encMaskTensor = OnnxTensor.createTensor(
            onnx, LongBuffer.wrap(encAttMask.map { it.toLong() }.toLongArray()), longArrayOf(1, seqLen)
        )

        // Extract encoder KV cache from cacheInitResult (fixed for all decode steps)
        val encoderKV = extractKV(onnx, cacheInitResult, "encoder")

        // Empty decoder self-attention KV cache (grows each step)
        var decoderKV = createEmptyKV(onnx, NUM_DECODER_LAYERS)

        var currentToken = NllbBpeTokenizer.EOS_ID  // EOS used as BOS

        try {
            for (step in 1..MAX_DECODE_STEPS) {
                val stepMs = System.currentTimeMillis()

                // a. Embed decoder input token
                val embedResult = runEmbedLmHead(onnx, emSess, intArrayOf(currentToken), null, useLmHead = false)
                val embedMatrix = embedResult.get("embed_matrix").get() as OnnxTensor

                // b. Decoder step
                val decResult = runDecoder(
                    onnx, decSess, currentToken, encMaskTensor, embedMatrix,
                    decoderKV, encoderKV
                )
                embedMatrix.close(); embedResult.close()

                val preLogits = decResult.get("pre_logits").get() as OnnxTensor

                // c. LM head → logits
                val lmResult = runEmbedLmHead(onnx, emSess, intArrayOf(0), preLogits, useLmHead = true)
                preLogits.close()
                val logitsTensor = lmResult.get("logits").get() as OnnxTensor

                // d. Pick next token
                val nextToken = if (step == 1) tgtLangId else argmax(logitsTensor)
                logitsTensor.close(); lmResult.close()

                // e. Update decoder KV cache
                val newDecoderKV = extractKV(onnx, decResult, "decoder")
                closeKV(decoderKV)
                decoderKV = newDecoderKV
                decResult.close()

                if (step <= 3 || nextToken == eos) {
                    Log.i(TAG, "Decoder step $step: token=$nextToken (${System.currentTimeMillis()-stepMs}ms)")
                }

                output.add(nextToken)
                if (nextToken == eos) break
                currentToken = nextToken
            }
        } finally {
            encMaskTensor.close()
            closeKV(encoderKV)
            closeKV(decoderKV)
        }

        return output.toIntArray()
    }

    private fun runDecoder(
        onnx: OrtEnvironment,
        session: OrtSession,
        currentToken: Int,
        encMask: OnnxTensor,
        embedMatrix: OnnxTensor,
        decoderKV: Array<OnnxTensor>,
        encoderKV: Array<OnnxTensor>
    ): OrtSession.Result {
        val idsTensor = OnnxTensor.createTensor(
            onnx, LongBuffer.wrap(longArrayOf(currentToken.toLong())), longArrayOf(1, 1)
        )
        val inputs = mutableMapOf<String, OnnxTensor>(
            "input_ids"              to idsTensor,
            "encoder_attention_mask" to encMask,
            "embed_matrix"           to embedMatrix
        )
        for (i in 0 until NUM_DECODER_LAYERS) {
            inputs["past_key_values.$i.decoder.key"]   = decoderKV[i * 2]
            inputs["past_key_values.$i.decoder.value"] = decoderKV[i * 2 + 1]
            inputs["past_key_values.$i.encoder.key"]   = encoderKV[i * 2]
            inputs["past_key_values.$i.encoder.value"] = encoderKV[i * 2 + 1]
        }
        return try {
            session.run(inputs).also { idsTensor.close() }
        } catch (e: Exception) {
            idsTensor.close(); throw e
        }
    }

    // -------------------------------------------------------------------------
    // KV cache helpers
    // -------------------------------------------------------------------------

    private fun createEmptyKV(onnx: OrtEnvironment, numLayers: Int): Array<OnnxTensor> {
        val shape = longArrayOf(1, NUM_HEADS.toLong(), 0, HEAD_DIM.toLong())
        return Array(numLayers * 2) {
            OnnxTensor.createTensor(onnx, FloatBuffer.wrap(FloatArray(0)), shape)
        }
    }

    /** Extract present.{i}.{type}.key/value tensors from a session result, copying data. */
    private fun extractKV(
        onnx: OrtEnvironment,
        result: OrtSession.Result,
        type: String   // "encoder" or "decoder"
    ): Array<OnnxTensor> = Array(NUM_DECODER_LAYERS * 2) { idx ->
        val layer    = idx / 2
        val keyOrVal = if (idx % 2 == 0) "key" else "value"
        val name     = "present.$layer.$type.$keyOrVal"
        val src      = result.get(name).get() as OnnxTensor
        val shape    = src.info.shape
        val buf      = src.floatBuffer
        val data     = FloatArray(buf.remaining()).also { buf.get(it) }
        OnnxTensor.createTensor(onnx, FloatBuffer.wrap(data), shape)
    }

    private fun closeKV(kv: Array<OnnxTensor>) =
        kv.forEach { try { it.close() } catch (_: Exception) {} }

    private fun argmax(logits: OnnxTensor): Int {
        val buf = logits.floatBuffer
        var maxIdx = 0; var maxVal = buf[0]
        for (i in 1 until buf.remaining()) { val v = buf[i]; if (v > maxVal) { maxVal = v; maxIdx = i } }
        return maxIdx
    }
}
