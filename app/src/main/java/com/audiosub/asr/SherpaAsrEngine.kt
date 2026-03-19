package com.audiosub.asr

import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import kotlinx.coroutines.withContext
import com.audiosub.util.CoroutineDispatcherProvider
import java.io.File

private const val TAG = "SherpaAsrEngine"

/**
 * ASR engine backed by sherpa-onnx [OfflineRecognizer] (Whisper model).
 *
 * @param modelDir Directory containing the whisper encoder/decoder ONNX files and tokens.txt.
 */
class SherpaAsrEngine(modelDir: File) : AsrEngine {

    private var recognizer: OfflineRecognizer? = null

    override val isReady: Boolean get() = recognizer != null

    init {
        try {
            // Whisper archives use size-prefixed names: tiny-encoder.int8.onnx, medium-encoder.int8.onnx, etc.
            val files = modelDir.listFiles() ?: emptyArray()
            val encoderFile = files.firstOrNull { it.name.endsWith("-encoder.int8.onnx") }
                ?: files.firstOrNull { it.name.contains("encoder") && it.name.endsWith(".onnx") }
            val decoderFile = files.firstOrNull { it.name.endsWith("-decoder.int8.onnx") }
                ?: files.firstOrNull { it.name.contains("decoder") && it.name.endsWith(".onnx") }
            val tokensFile = files.firstOrNull { it.name.endsWith("-tokens.txt") }
                ?: files.firstOrNull { it.name == "tokens.txt" }

            if (encoderFile == null || decoderFile == null || tokensFile == null) {
                Log.e(TAG, "모델 파일 누락: encoder=${encoderFile?.name}, decoder=${decoderFile?.name}, tokens=${tokensFile?.name}")
                Log.e(TAG, "디렉토리 내 파일: ${files.map { it.name }}")
                throw IllegalStateException("Required model files not found in $modelDir")
            }

            val encoderPath = encoderFile.absolutePath
            val decoderPath = decoderFile.absolutePath
            val tokensPath  = tokensFile.absolutePath
            Log.i(TAG, "모델 파일: encoder=${encoderFile.name}, decoder=${decoderFile.name}, tokens=${tokensFile.name}")

            val whisperCfg = OfflineWhisperModelConfig.Builder()
                .setEncoder(encoderPath)
                .setDecoder(decoderPath)
                .setLanguage("")       // empty = auto-detect
                .setTask("transcribe")
                .setTailPaddings(-1)
                .build()

            val modelCfg = OfflineModelConfig.Builder()
                .setWhisper(whisperCfg)
                .setTokens(tokensPath)
                .setNumThreads(2)
                .setDebug(false)
                .setProvider("cpu")
                .build()

            val featCfg = FeatureConfig.Builder()
                .setSampleRate(16000)
                .setFeatureDim(80)
                .build()

            val config = OfflineRecognizerConfig.Builder()
                .setFeatureConfig(featCfg)
                .setOfflineModelConfig(modelCfg)
                .build()

            recognizer = OfflineRecognizer(config)
            Log.i(TAG, "SherpaAsrEngine initialized from $modelDir")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize SherpaAsrEngine", e)
        }
    }

    override suspend fun transcribe(pcmFloat: FloatArray): AsrResult =
        withContext(CoroutineDispatcherProvider.asr) {
            val rec = recognizer ?: return@withContext AsrResult("")
            try {
                val stream = rec.createStream()
                stream.acceptWaveform(pcmFloat, 16000)
                rec.decode(stream)
                val result = rec.getResult(stream)
                stream.release()
                AsrResult(
                    text = result.text.trim(),
                    language = result.lang.ifBlank { null }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Transcription error", e)
                AsrResult("")
            }
        }

    override fun release() {
        recognizer?.release()
        recognizer = null
        Log.i(TAG, "SherpaAsrEngine released")
    }
}
