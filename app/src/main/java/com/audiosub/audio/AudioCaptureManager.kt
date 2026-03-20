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

private const val SAMPLE_RATE_ASR = 16_000
private const val AUDIO_FORMAT    = AudioFormat.ENCODING_PCM_16BIT

/**
 * Ordered list of (sampleRate, channelMask, label) configs to try for system audio.
 * Most Android devices use a 48kHz stereo mixer; 44.1kHz stereo is a common fallback.
 */
private val SYSTEM_AUDIO_CONFIGS = listOf(
    Triple(48_000, AudioFormat.CHANNEL_IN_STEREO, "48k-stereo"),
    Triple(48_000, AudioFormat.CHANNEL_IN_MONO,   "48k-mono"),
    Triple(44_100, AudioFormat.CHANNEL_IN_STEREO, "44.1k-stereo"),
    Triple(44_100, AudioFormat.CHANNEL_IN_MONO,   "44.1k-mono"),
)

/**
 * Manages audio capture via [AudioRecord].
 *
 * When [mediaProjection] is provided (Android 10+), attempts system audio playback capture
 * using multiple sample-rate/channel configs. Falls back to microphone when all system
 * audio configs fail.
 *
 * @param chunker             Destination sliding-window chunker.
 * @param mediaProjection     Optional MediaProjection for system audio capture.
 * @param onLevelUpdate       Called with dBFS RMS after each read burst.
 * @param onCaptureSourceReady Called once with the actual capture source ("system"/"mic")
 *                             and config label when recording starts.
 * @param forceMic            If true, skip system audio and use microphone directly.
 */
class AudioCaptureManager(
    private val chunker: AudioChunker,
    private val mediaProjection: MediaProjection? = null,
    private val onLevelUpdate: ((dbfs: Float) -> Unit)? = null,
    private val onCaptureSourceReady: ((source: String, config: String) -> Unit)? = null,
    private val forceMic: Boolean = false
) {
    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    // Actual capture params — set in start() once a valid config is found
    private var actualSampleRate = 44_100
    private var isStereo = false
    private var readCount = 0  // 초기 진단용 카운터

    val isCapturing: Boolean get() = captureJob?.isActive == true

    @SuppressLint("MissingPermission")
    fun start() {
        if (isCapturing) return

        val record: AudioRecord?
        val sourceLabel: String
        val configLabel: String

        if (!forceMic && mediaProjection != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val result = buildSystemAudioRecord(mediaProjection)
            if (result != null) {
                record = result.first
                actualSampleRate = result.second
                isStereo = result.third
                sourceLabel = "system"
                configLabel = "${if (isStereo) "stereo" else "mono"} ${actualSampleRate / 1000}kHz"
                Log.i(TAG, "✓ 시스템 오디오 캡처 활성화 ($configLabel)")
            } else {
                Log.w(TAG, "✗ 모든 시스템 오디오 설정 실패 → 마이크로 폴백")
                val micResult = buildMicRecord()
                record = micResult?.first
                actualSampleRate = micResult?.second ?: 44_100
                isStereo = false
                sourceLabel = "mic(fallback)"
                configLabel = "mic"
            }
        } else {
            val reason = if (forceMic) "강제 마이크 모드" else "MediaProjection 없음 → 마이크 사용"
            Log.i(TAG, reason)
            val micResult = buildMicRecord()
            record = micResult?.first
            actualSampleRate = micResult?.second ?: 44_100
            isStereo = false
            sourceLabel = "mic"
            configLabel = "mic"
        }

        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord 초기화 실패 (state=${record?.state})")
            record?.release()
            onCaptureSourceReady?.invoke("error", "초기화 실패")
            return
        }

        audioRecord = record
        record.startRecording()
        Log.i(TAG, "AudioRecord 녹음 시작: source=$sourceLabel sampleRate=$actualSampleRate stereo=$isStereo")
        onCaptureSourceReady?.invoke(sourceLabel, configLabel)

        val bufSize = AudioRecord.getMinBufferSize(actualSampleRate,
            if (isStereo) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO,
            AUDIO_FORMAT).takeIf { it > 0 } ?: (actualSampleRate / 5 * (if (isStereo) 4 else 2))
        val readBuffer = ByteArray(bufSize * 4)

        captureJob = scope.launch {
            while (isActive) {
                val bytesRead = record.read(readBuffer, 0, readBuffer.size)
                if (bytesRead <= 0) {
                    Log.v(TAG, "read() returned $bytesRead — skipping")
                    continue
                }

                var float32 = PcmConverter.int16BytesToFloat32(readBuffer, bytesRead)
                if (isStereo) float32 = PcmConverter.stereoToMono(float32)

                // 처음 10회 read 동안 raw 데이터 진단 로그 출력
                readCount++
                if (readCount <= 10) {
                    val maxSample = float32.maxOrNull() ?: 0f
                    val minSample = float32.minOrNull() ?: 0f
                    val rms = sqrt(float32.sumOf { (it * it).toDouble() }.toFloat() / float32.size)
                    val allZero = float32.all { it == 0f }
                    Log.i(TAG, "RAW[${readCount}] samples=${float32.size} " +
                        "min=${"%.6f".format(minSample)} max=${"%.6f".format(maxSample)} " +
                        "rms=${"%.6f".format(rms)} allZero=$allZero stereo=$isStereo")
                    if (readCount == 10 && allZero) {
                        Log.e(TAG, "⚠ 처음 10회 read가 모두 0 — 오디오 입력 없음!")
                    }
                }

                val resampled = PcmConverter.downsample(float32, actualSampleRate, SAMPLE_RATE_ASR)

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

    /**
     * Try each (sampleRate, channelMask) config in [SYSTEM_AUDIO_CONFIGS] until one succeeds.
     * Returns Triple(audioRecord, sampleRate, isStereo) or null if all fail.
     *
     * IMPORTANT: AudioRecord.Builder().build() does NOT throw on failure — it can return
     * a record in STATE_UNINITIALIZED. Always validate state before returning.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    @SuppressLint("MissingPermission")
    private fun buildSystemAudioRecord(projection: MediaProjection): Triple<AudioRecord, Int, Boolean>? {
        val captureConfig = try {
            AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(android.media.AudioAttributes.USAGE_GAME)
                .addMatchingUsage(android.media.AudioAttributes.USAGE_UNKNOWN)
                .addMatchingUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .addMatchingUsage(android.media.AudioAttributes.USAGE_ALARM)
                .addMatchingUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                .addMatchingUsage(android.media.AudioAttributes.USAGE_ASSISTANT)
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "AudioPlaybackCaptureConfiguration 생성 실패: ${e.message}")
            return null
        }

        for ((rate, channel, label) in SYSTEM_AUDIO_CONFIGS) {
            try {
                val minBuf = AudioRecord.getMinBufferSize(rate, channel, AUDIO_FORMAT)
                    .takeIf { it > 0 } ?: (rate / 5 * if (channel == AudioFormat.CHANNEL_IN_STEREO) 4 else 2)

                val ar = AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(captureConfig)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AUDIO_FORMAT)
                            .setSampleRate(rate)
                            .setChannelMask(channel)
                            .build()
                    )
                    .setBufferSizeInBytes(minBuf * 4)
                    .build()

                if (ar.state == AudioRecord.STATE_INITIALIZED) {
                    Log.i(TAG, "시스템 오디오 설정 성공: $label (minBuf=$minBuf)")
                    return Triple(ar, rate, channel == AudioFormat.CHANNEL_IN_STEREO)
                } else {
                    Log.w(TAG, "시스템 오디오 $label: STATE_UNINITIALIZED — 다음 시도")
                    ar.release()
                }
            } catch (e: Exception) {
                Log.w(TAG, "시스템 오디오 $label 예외: ${e.message}")
            }
        }

        Log.e(TAG, "모든 시스템 오디오 설정 실패")
        return null
    }

    @SuppressLint("MissingPermission")
    private fun buildMicRecord(): Pair<AudioRecord, Int>? {
        val rate = 44_100
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AUDIO_FORMAT)
            .takeIf { it > 0 } ?: (rate / 5 * 2)
        return try {
            val ar = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                rate, AudioFormat.CHANNEL_IN_MONO, AUDIO_FORMAT, minBuf * 4
            )
            if (ar.state == AudioRecord.STATE_INITIALIZED) {
                Log.i(TAG, "마이크 AudioRecord 초기화 성공")
                Pair(ar, rate)
            } else {
                Log.e(TAG, "마이크 AudioRecord STATE_UNINITIALIZED")
                ar.release()
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "마이크 AudioRecord 예외: ${e.message}")
            null
        }
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
