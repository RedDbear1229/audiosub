package com.audiosub.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.log10
import kotlin.math.sqrt

private const val TAG = "AudioCaptureManager"

private const val SAMPLE_RATE_ASR     = 16_000
private const val SAMPLE_RATE_CAPTURE = 44_100
private const val CHANNEL_CONFIG      = AudioFormat.CHANNEL_IN_MONO
private const val AUDIO_FORMAT        = AudioFormat.ENCODING_PCM_16BIT

/**
 * Manages audio capture via [AudioRecord].
 *
 * When [mediaProjection] is provided (Android 10+), captures system audio playback.
 * Otherwise falls back to microphone.
 *
 * Captured PCM is converted to Float32 at 16 kHz and pushed into [chunker].
 *
 * @param chunker        Destination sliding-window chunker.
 * @param mediaProjection Optional [MediaProjection] for system audio capture.
 * @param onLevelUpdate  Called with dBFS RMS level after each read burst (main thread NOT guaranteed).
 */
class AudioCaptureManager(
    private val chunker: AudioChunker,
    private val mediaProjection: MediaProjection? = null,
    private val onLevelUpdate: ((dbfs: Float) -> Unit)? = null
) {
    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    val isCapturing: Boolean get() = captureJob?.isActive == true

    @SuppressLint("MissingPermission")
    fun start() {
        if (isCapturing) return

        val minBufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE_CAPTURE, CHANNEL_CONFIG, AUDIO_FORMAT)
            .takeIf { it > 0 } ?: (SAMPLE_RATE_CAPTURE / 5 * 2)

        val record = if (mediaProjection != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val sys = buildSystemAudioRecord(mediaProjection, minBufSize)
            if (sys != null) {
                Log.i(TAG, "✓ 시스템 오디오 캡처 활성화 (MediaProjection)")
            } else {
                Log.w(TAG, "✗ 시스템 오디오 실패 → 마이크로 폴백")
            }
            sys ?: buildMicRecord(minBufSize)
        } else {
            Log.i(TAG, "MediaProjection 없음 → 마이크 사용")
            buildMicRecord(minBufSize)
        }

        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord 초기화 실패")
            record?.release()
            return
        }

        audioRecord = record
        record.startRecording()
        Log.i(TAG, "AudioRecord 시작 (srcRate=$SAMPLE_RATE_CAPTURE → asrRate=$SAMPLE_RATE_ASR)")

        val readBuffer = ByteArray(minBufSize)

        captureJob = scope.launch {
            while (isActive) {
                val bytesRead = record.read(readBuffer, 0, readBuffer.size)
                if (bytesRead <= 0) continue

                val float32   = PcmConverter.int16BytesToFloat32(readBuffer, bytesRead)
                val resampled = PcmConverter.downsample(float32, SAMPLE_RATE_CAPTURE, SAMPLE_RATE_ASR)

                onLevelUpdate?.invoke(computeDbfs(float32))
                chunker.feed(resampled)
            }
        }
    }

    fun stop() {
        captureJob?.cancel()
        captureJob = null
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        Log.i(TAG, "AudioRecord stopped")
    }

    // -------------------------------------------------------------------------
    // Private builders
    // -------------------------------------------------------------------------

    @RequiresApi(Build.VERSION_CODES.Q)
    @SuppressLint("MissingPermission")
    private fun buildSystemAudioRecord(projection: MediaProjection, bufSize: Int): AudioRecord? =
        try {
            val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(android.media.AudioAttributes.USAGE_GAME)
                .addMatchingUsage(android.media.AudioAttributes.USAGE_UNKNOWN)
                .build()

            AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(captureConfig)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AUDIO_FORMAT)
                        .setSampleRate(SAMPLE_RATE_CAPTURE)
                        .setChannelMask(CHANNEL_CONFIG)
                        .build()
                )
                .setBufferSizeInBytes(bufSize * 4)
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "System AudioRecord failed, will try mic", e)
            null
        }

    @SuppressLint("MissingPermission")
    private fun buildMicRecord(bufSize: Int): AudioRecord? =
        try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE_CAPTURE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufSize * 4
            )
        } catch (e: Exception) {
            Log.e(TAG, "Mic AudioRecord failed", e)
            null
        }

    // -------------------------------------------------------------------------
    // Level metering
    // -------------------------------------------------------------------------

    private fun computeDbfs(samples: FloatArray): Float {
        if (samples.isEmpty()) return -96f
        val rms = sqrt(samples.sumOf { (it * it).toDouble() }.toFloat() / samples.size)
        return if (rms > 0f) 20f * log10(rms) else -96f
    }
}
