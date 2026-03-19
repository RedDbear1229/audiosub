package com.audiosub.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.audiosub.MainActivity
import com.audiosub.R
import com.audiosub.asr.AsrEngine
import com.audiosub.asr.SherpaAsrEngine
import com.audiosub.audio.AudioCaptureManager
import com.audiosub.audio.AudioChunker
import com.audiosub.model.ModelDownloadManager
import com.audiosub.model.ModelRegistry
import com.audiosub.overlay.PipelineState
import com.audiosub.overlay.SubtitleOverlayManager
import com.audiosub.translation.NllbTranslationEngine
import com.audiosub.translation.TranslationEngine
import com.audiosub.util.CoroutineDispatcherProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private const val TAG = "AudioCaptureService"
private const val NOTIFICATION_CHANNEL_ID = "audiosub_service"
private const val NOTIFICATION_ID = 1

// Energy threshold for silence detection (float PCM, normalized to [-1, 1]).
// RMS below this value is treated as silence and not sent to Whisper.
// ~0.008 ≈ -42 dBFS — adjust up if real speech is being skipped.
private const val VAD_RMS_THRESHOLD = 0.008f

/**
 * Foreground service that orchestrates the full pipeline.
 *
 * Overlay state machine:
 *   INITIALIZING ──► DOWNLOADING (while models still downloading)
 *                └──► LISTENING  (engines ready, waiting for audio)
 *                     ├──► TRANSCRIBING (Whisper running)
 *                     │    ├──► TRANSLATING (NLLB running)
 *                     │    │    └──► LISTENING + subtitle shown
 *                     │    └──► LISTENING + subtitle shown (lang=ko, skip translation)
 *                     └── (silence) LISTENING
 */
class AudioCaptureService : LifecycleService() {

    companion object {
        const val EXTRA_RESULT_CODE      = "extra_result_code"
        const val EXTRA_PROJECTION_DATA  = "extra_projection_data"
        const val EXTRA_DEBUG_MODE       = "extra_debug_mode"
        const val EXTRA_AUDIO_SOURCE     = "extra_audio_source"
        const val AUDIO_SOURCE_MIC       = "mic"
        const val AUDIO_SOURCE_SYSTEM    = "system"
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(CoroutineDispatcherProvider.io + serviceJob)

    private lateinit var overlay: SubtitleOverlayManager
    private lateinit var downloadManager: ModelDownloadManager

    private var chunker: AudioChunker? = null
    private var captureManager: AudioCaptureManager? = null
    private var asrEngine: AsrEngine? = null
    private var translationEngine: TranslationEngine? = null

    private var isDebugMode: Boolean = false
    private var audioSource: String = AUDIO_SOURCE_SYSTEM

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service created")
        createNotificationChannel()
        overlay = SubtitleOverlayManager(this)
        downloadManager = ModelDownloadManager(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        // startForeground() MUST be called before getMediaProjection() on Android 14+.
        // Must specify FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION so Android recognises
        // this as a mediaProjection-type service — required for getMediaProjection() to succeed.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
        isDebugMode = intent?.getBooleanExtra(EXTRA_DEBUG_MODE, false) ?: false
        audioSource = intent?.getStringExtra(EXTRA_AUDIO_SOURCE) ?: AUDIO_SOURCE_SYSTEM

        overlay.attach()
        overlay.setDebugMode(isDebugMode)
        overlay.setState(PipelineState.INITIALIZING)

        observeDownloadProgress()

        // Now safe to call getMediaProjection() — foreground service is running
        val projection = acquireMediaProjection(intent)

        // Init engines on the ASR thread, then start capture pipeline
        serviceScope.launch(CoroutineDispatcherProvider.asr) {
            try {
                initEngines()
                startPipeline(projection, forceMic = (audioSource == AUDIO_SOURCE_MIC))
            } catch (e: Exception) {
                Log.e(TAG, "Pipeline init failed", e)
                writeCrashLog("Pipeline", e)
                overlay.setState(PipelineState.ERROR)
            }
        }

        return START_STICKY
    }

    /**
     * Obtain MediaProjection from the Intent extras that MainActivity forwarded.
     * Called AFTER startForeground() so Android 14+ constraint is satisfied.
     */
    private fun acquireMediaProjection(intent: Intent?): MediaProjection? {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        @Suppress("DEPRECATION")
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            intent?.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
        else
            intent?.getParcelableExtra(EXTRA_PROJECTION_DATA)

        if (resultCode == -1 || data == null) {
            Log.w(TAG, "No projection data in Intent — mic fallback")
            return MediaProjectionHolder.get() // fall back to previously stored (if any)
        }

        return try {
            val pm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val mp = pm.getMediaProjection(resultCode, data)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                mp.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        Log.i(TAG, "MediaProjection stopped by system")
                        MediaProjectionHolder.release()
                    }
                }, Handler(Looper.getMainLooper()))
            }
            MediaProjectionHolder.set(mp)
            Log.i(TAG, "MediaProjection acquired in service")
            mp
        } catch (e: Exception) {
            Log.e(TAG, "getMediaProjection failed in service: ${e.javaClass.simpleName}: ${e.message}", e)
            writeCrashLog("acquireMediaProjection", e)
            null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "Service destroying")
        captureManager?.stop()
        chunker?.reset()
        asrEngine?.release()
        translationEngine?.release()
        overlay.detach()
        MediaProjectionHolder.release()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    // -------------------------------------------------------------------------
    // Download progress observation → overlay DOWNLOADING state
    // -------------------------------------------------------------------------

    private fun observeDownloadProgress() {
        WorkManager.getInstance(this)
            .getWorkInfosByTagLiveData(ModelDownloadManager.TAG_DOWNLOAD)
            .observe(this) { infos ->
                val hasActiveDownload = infos.any {
                    it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED
                }
                if (hasActiveDownload) {
                    val runningInfo = infos.firstOrNull { it.state == WorkInfo.State.RUNNING }
                    val pct = runningInfo?.progress?.getInt(
                        ModelDownloadManager.DownloadWorker.KEY_PROGRESS, -1
                    ) ?: -1
                    val phase = runningInfo?.progress?.getString(
                        ModelDownloadManager.DownloadWorker.KEY_PHASE
                    ) ?: ModelDownloadManager.DownloadWorker.PHASE_DOWNLOAD

                    val label = when (phase) {
                        ModelDownloadManager.DownloadWorker.PHASE_EXTRACT -> "압축 해제 중..."
                        ModelDownloadManager.DownloadWorker.PHASE_DONE    -> "모델 준비 완료"
                        else -> if (pct >= 0) "다운로드 중... $pct%" else "다운로드 중..."
                    }
                    overlay.setState(PipelineState.DOWNLOADING(label))
                }
            }
    }

    // -------------------------------------------------------------------------
    // Engine initialization
    // -------------------------------------------------------------------------

    private fun initEngines() {
        val whisperDir = downloadManager.whisperModelDir()
        if (downloadManager.isBundleReady(ModelRegistry.ACTIVE_ASR)) {
            asrEngine = SherpaAsrEngine(whisperDir)
            Log.i(TAG, "ASR engine ready (${ModelRegistry.ACTIVE_ASR.id})")
        } else {
            Log.w(TAG, "ASR model not ready — waiting for download")
        }

        val nllbDir = downloadManager.nllbModelDir()
        if (downloadManager.isBundleReady(ModelRegistry.NLLB_600M)) {
            translationEngine = NllbTranslationEngine(nllbDir)
            Log.i(TAG, "Translation engine ready (NLLB)")
        } else {
            Log.d(TAG, "NLLB 모델 없음 — 번역 생략 (원문 표시)")
        }

        overlay.showEngineStatus(
            asrReady = asrEngine?.isReady == true,
            translationReady = translationEngine?.isReady == true,
            audioSource = audioSource
        )
    }

    // -------------------------------------------------------------------------
    // Audio pipeline
    // -------------------------------------------------------------------------

    private fun startPipeline(projection: android.media.projection.MediaProjection?, forceMic: Boolean = false) {
        val audioChunker = AudioChunker()
        chunker = audioChunker

        captureManager = AudioCaptureManager(
            chunker = audioChunker,
            mediaProjection = projection,
            onLevelUpdate = { dbfs -> overlay.updateLevel(dbfs) },
            forceMic = forceMic
        ).also { it.start() }

        overlay.setState(PipelineState.LISTENING)

        serviceScope.launch(CoroutineDispatcherProvider.asr) {
            for (chunk in audioChunker.output) {
                processChunk(chunk)
            }
        }
    }

    private suspend fun processChunk(chunk: FloatArray) {
        val asr = asrEngine
        if (asr == null || !asr.isReady) {
            Log.w(TAG, "processChunk: ASR 엔진 미준비 (null=${asr == null})")
            return
        }

        // Energy-based VAD: skip silence to prevent Whisper hallucinations.
        val rms = computeRms(chunk)

        // RMS 표시 — debug 모드일 때만
        if (isDebugMode) {
            overlay.showDebugInfo(rms = rms, translationReady = translationEngine?.isReady == true)
        }

        if (rms < VAD_RMS_THRESHOLD) {
            Log.v(TAG, "VAD: 묵음 (rms=${"%.4f".format(rms)}) — skip")
            overlay.setState(PipelineState.LISTENING)
            return
        }
        Log.i(TAG, "VAD: 음성 감지 (rms=${"%.4f".format(rms)}, samples=${chunk.size})")

        overlay.setState(PipelineState.TRANSCRIBING)
        val result = asr.transcribe(chunk)
        Log.i(TAG, "ASR 결과: \"${result.text}\" [lang=${result.language}] [empty=${result.isEmpty}]")

        // ASR 결과를 debug overlay에 표시 — debug 모드일 때만
        if (isDebugMode) {
            overlay.showDebugInfo(
                rms = rms,
                asrText = result.text,
                lang = result.language,
                translationReady = translationEngine?.isReady == true
            )
        }

        if (result.isEmpty) {
            Log.d(TAG, "ASR: 빈 결과 — skip")
            overlay.setState(PipelineState.LISTENING)
            return
        }
        if (isHallucination(result.text)) {
            Log.d(TAG, "ASR: 환각 필터됨 \"${result.text}\"")
            overlay.setState(PipelineState.LISTENING)
            return
        }

        val lang = result.language ?: "en"
        val subtitle: String
        if (lang == "ko") {
            subtitle = result.text
            Log.i(TAG, "언어=ko → 번역 생략, 원문 표시")
        } else {
            val engine = translationEngine
            if (engine != null && engine.isReady) {
                overlay.setState(PipelineState.TRANSLATING)
                subtitle = translateOrFallback(result.text, lang)
                Log.i(TAG, "번역 완료: \"$subtitle\"")
            } else {
                // 번역 엔진 미준비 → 원문을 언어 레이블과 함께 표시
                subtitle = "[${lang.uppercase()}] ${result.text}"
                Log.i(TAG, "번역 엔진 없음 → 원문 표시 (lang=$lang)")
            }
        }

        overlay.setState(PipelineState.LISTENING)
        if (subtitle.isNotBlank()) overlay.showSubtitle(subtitle)
    }

    private fun computeRms(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0f
        var sum = 0.0
        for (s in samples) sum += s * s
        return kotlin.math.sqrt((sum / samples.size).toFloat())
    }

    /**
     * Filters well-known Whisper hallucinations produced from silence or noise.
     * Returns true if the text should be discarded.
     */
    private fun isHallucination(text: String): Boolean {
        val t = text.trim().lowercase()
        if (t.length <= 2) return true
        val patterns = listOf(
            "thank you", "thanks for watching", "please subscribe",
            "suscle", "sub", "subtitles by", "transcribed by",
            "♪", "♫", "music", "[music]", "[applause]", "[laughter]",
            "www.", ".com", "http"
        )
        return patterns.any { t.contains(it) }
    }

    private suspend fun translateOrFallback(text: String, lang: String): String {
        val engine = translationEngine
        if (engine == null || !engine.isReady) return text
        return try {
            engine.translate(text, lang)
        } catch (e: Exception) {
            Log.e(TAG, "Translation error", e)
            text
        }
    }

    // -------------------------------------------------------------------------
    // Notification
    // -------------------------------------------------------------------------

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = getString(R.string.notification_channel_desc) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun writeCrashLog(tag: String, e: Exception) {
        try {
            val dir = getExternalFilesDir(null) ?: filesDir
            val file = java.io.File(dir, "crash_log.txt")
            val sw = java.io.StringWriter()
            e.printStackTrace(java.io.PrintWriter(sw))
            file.appendText("[$tag] ${java.util.Date()}\n$sw\n\n")
        } catch (_: Exception) {}
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }
}
