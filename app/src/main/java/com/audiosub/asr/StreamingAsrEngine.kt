package com.audiosub.asr

import android.app.Application
import android.util.Log
import com.audiosub.AudioSubApp
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File

private const val TAG = "StreamingAsrEngine"

/**
 * Streaming ASR engine backed by sherpa-onnx [OnlineRecognizer] (Zipformer Transducer).
 *
 * Unlike [SherpaAsrEngine] (batch/offline), this engine processes audio incrementally:
 * small audio chunks (~20ms) are fed continuously via [feedAudio], producing partial
 * results in real-time and final results on endpoint detection (silence).
 *
 * @param modelDir   Directory containing encoder/decoder/joiner ONNX + tokens.txt
 * @param modelType  "zipformer" (default) or "zipformer2" (PengChengStarling multilingual)
 * @param onPartialResult Called with intermediate text as audio is being processed
 * @param onFinalResult   Called with finalized text when an endpoint (silence) is detected
 */
class StreamingAsrEngine(
    modelDir: File,
    private val app: Application? = null,
    numThreads: Int = 4,
    modelType: String = "",
    private val onPartialResult: ((String) -> Unit)? = null,
    private val onFinalResult: ((String) -> Unit)? = null
) {
    private var recognizer: OnlineRecognizer? = null
    private var stream: OnlineStream? = null

    val isReady: Boolean get() = recognizer != null

    init {
        try {
            val files = modelDir.listFiles() ?: emptyArray()

            // Find model files — support various naming patterns
            val encoderFile = files.firstOrNull { it.name.contains("encoder") && it.name.endsWith(".onnx") }
            val decoderFile = files.firstOrNull { it.name.contains("decoder") && it.name.endsWith(".onnx") }
            val joinerFile  = files.firstOrNull { it.name.contains("joiner") && it.name.endsWith(".onnx") }
            val tokensFile  = files.firstOrNull { it.name == "tokens.txt" }

            if (encoderFile == null || decoderFile == null || joinerFile == null || tokensFile == null) {
                Log.e(TAG, "모델 파일 누락: encoder=${encoderFile?.name}, decoder=${decoderFile?.name}, " +
                    "joiner=${joinerFile?.name}, tokens=${tokensFile?.name}")
                Log.e(TAG, "디렉토리 내 파일: ${files.map { it.name }}")
                throw IllegalStateException("Required streaming model files not found in $modelDir")
            }

            Log.i(TAG, "모델 파일: encoder=${encoderFile.name}, decoder=${decoderFile.name}, " +
                "joiner=${joinerFile.name}, tokens=${tokensFile.name}")

            val transducerCfg = OnlineTransducerModelConfig.Builder()
                .setEncoder(encoderFile.absolutePath)
                .setDecoder(decoderFile.absolutePath)
                .setJoiner(joinerFile.absolutePath)
                .build()

            val modelCfgBuilder = OnlineModelConfig.Builder()
                .setTransducer(transducerCfg)
                .setTokens(tokensFile.absolutePath)
                .setNumThreads(numThreads)
                .setDebug(false)
                .setProvider("cpu")

            if (modelType.isNotBlank()) {
                modelCfgBuilder.setModelType(modelType)
            }

            val modelCfg = modelCfgBuilder.build()

            val featCfg = FeatureConfig.Builder()
                .setSampleRate(16000)
                .setFeatureDim(80)
                .build()

            // Endpoint rules for detecting speech boundaries
            val rule1 = EndpointRule.builder()
                .setMustContainNonSilence(false)
                .setMinTrailingSilence(2.4f)    // 2.4s silence → endpoint (no speech at all)
                .setMinUtteranceLength(0f)
                .build()
            val rule2 = EndpointRule.builder()
                .setMustContainNonSilence(true)
                .setMinTrailingSilence(1.2f)    // 1.2s silence after speech → endpoint
                .setMinUtteranceLength(0f)
                .build()
            val rule3 = EndpointRule.builder()
                .setMustContainNonSilence(false)
                .setMinTrailingSilence(0f)
                .setMinUtteranceLength(20f)     // Force endpoint after 20s utterance
                .build()

            val endpointCfg = EndpointConfig.builder()
                .setRule1(rule1)
                .setRule2(rule2)
                .setRul3(rule3)     // Note: typo in sherpa-onnx API — "setRul3" not "setRule3"
                .build()

            val config = OnlineRecognizerConfig.Builder()
                .setFeatureConfig(featCfg)
                .setOnlineModelConfig(modelCfg)
                .setEndpointConfig(endpointCfg)
                .setEnableEndpoint(true)
                .setDecodingMethod("greedy_search")
                .build()

            recognizer = OnlineRecognizer(config)
            stream = recognizer!!.createStream()
            Log.i(TAG, "StreamingAsrEngine initialized from $modelDir (modelType=$modelType, threads=$numThreads)")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to initialize StreamingAsrEngine: ${e.javaClass.simpleName}: ${e.message}", e)
            recognizer = null
            stream = null
        }
    }

    /**
     * Feed audio samples and process immediately (non-blocking design).
     * Internally calls decode + endpoint check.
     * Partial/final results are delivered via callbacks.
     */
    fun feedAudio(pcmFloat: FloatArray, sampleRate: Int = 16000) {
        val rec = recognizer ?: return
        val s = stream ?: return

        try {
            AudioSubApp.markOperation(app, "STREAMING samples=${pcmFloat.size}")
            s.acceptWaveform(pcmFloat, sampleRate)

            while (rec.isReady(s)) {
                rec.decode(s)
            }

            if (rec.isEndpoint(s)) {
                val result = rec.getResult(s)
                val text = result.text.trim()
                if (text.isNotBlank()) {
                    Log.i(TAG, "Final: \"${text.take(60)}\"")
                    onFinalResult?.invoke(text)
                }
                rec.reset(s)
            } else {
                val result = rec.getResult(s)
                val text = result.text.trim()
                if (text.isNotBlank()) {
                    onPartialResult?.invoke(text)
                }
            }
            AudioSubApp.markOperation(app, null)
        } catch (e: Throwable) {
            Log.e(TAG, "feedAudio 오류: ${e.javaClass.simpleName}: ${e.message}")
            AudioSubApp.markOperation(app, null)
        }
    }

    fun release() {
        try { stream?.release() } catch (_: Throwable) {}
        try { recognizer?.release() } catch (_: Throwable) {}
        stream = null
        recognizer = null
        Log.i(TAG, "StreamingAsrEngine released")
    }
}
