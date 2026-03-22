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
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.audiosub.MainActivity
import com.audiosub.R
import com.audiosub.asr.AsrEngine
import com.audiosub.asr.SherpaAsrEngine
import com.audiosub.asr.StreamingAsrEngine
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

        const val EXTRA_SPEED_MODE       = "extra_speed_mode"
        const val SPEED_MODE_BALANCED    = "balanced"   // B: 3초/1초, 4스레드
        const val SPEED_MODE_FAST        = "fast"       // C: 2초/0.75초, 4스레드
        const val SPEED_MODE_REALTIME    = "realtime"   // E: 스트리밍 ASR (~0.3-0.5초)

        const val EXTRA_STREAMING_LANG   = "extra_streaming_lang"  // "en", "ko", "zh", "ja"
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(CoroutineDispatcherProvider.io + serviceJob)

    private lateinit var overlay: SubtitleOverlayManager
    private lateinit var downloadManager: ModelDownloadManager

    private var chunker: AudioChunker? = null
    private var captureManager: AudioCaptureManager? = null
    private var asrEngine: AsrEngine? = null
    private var translationEngine: TranslationEngine? = null

    private var streamingEngine: StreamingAsrEngine? = null

    private var isDebugMode: Boolean = false
    private var audioSource: String = AUDIO_SOURCE_SYSTEM
    private var speedMode: String = SPEED_MODE_BALANCED
    private var streamingLang: String = "en"
    private var translationUnavailableReason: String = "모델 없음"
    private var wakeLock: PowerManager.WakeLock? = null

    // 연속 묵음 카운터 — 일정 횟수 이상이면 오디오 차단 경고 표시
    private var silentChunkCount = 0
    private val SILENCE_WARN_CHUNKS = 15  // 약 30초 (청크당 2s)

    // 번역 캐시 — 슬라이딩 윈도우 특성상 동일 텍스트 반복 번역 방지 (LRU, 최대 20개)
    private val translationCache = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean = size > 20
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service created")
        createNotificationChannel()
        overlay = SubtitleOverlayManager(this)
        downloadManager = ModelDownloadManager(this)

        // Prevent CPU from sleeping during audio capture & inference
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AudioSub::AudioCapture")
            .apply { acquire() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        // System restart with null intent — MediaProjection data is lost, cannot recover.
        if (intent == null) {
            Log.w(TAG, "onStartCommand: null intent (시스템 재시작?), 서비스 종료")
            stopSelf()
            return START_NOT_STICKY
        }

        // Parse extras BEFORE startForeground() so we know which service type to use
        isDebugMode   = intent?.getBooleanExtra(EXTRA_DEBUG_MODE, false) ?: false
        audioSource   = intent?.getStringExtra(EXTRA_AUDIO_SOURCE)   ?: AUDIO_SOURCE_SYSTEM
        speedMode     = intent?.getStringExtra(EXTRA_SPEED_MODE)     ?: SPEED_MODE_BALANCED
        streamingLang = intent?.getStringExtra(EXTRA_STREAMING_LANG) ?: "en"

        // startForeground() MUST be called before getMediaProjection() on Android 14+.
        // Use only the required service type to avoid unnecessary status bar indicators
        // (e.g. mic icon when using system audio only).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val fgType = if (audioSource == AUDIO_SOURCE_MIC)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            else
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            startForeground(NOTIFICATION_ID, buildNotification(), fgType)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }

        overlay.attach()
        overlay.setDebugMode(isDebugMode)
        overlay.setState(PipelineState.INITIALIZING)

        observeDownloadProgress()

        // Now safe to call getMediaProjection() — foreground service is running
        val projection = acquireMediaProjection(intent)

        // Init engines on the ASR thread, then start capture pipeline.
        // Wait for any active WorkManager download/extraction to finish first,
        // so isBundleReady() returns true when initEngines() runs.
        serviceScope.launch(CoroutineDispatcherProvider.asr) {
            try {
                waitForActiveDownloads()
                initEngines()
                startPipeline(projection, forceMic = (audioSource == AUDIO_SOURCE_MIC))
            } catch (e: Exception) {
                Log.e(TAG, "Pipeline init failed", e)
                writeCrashLog("Pipeline", e)
                overlay.setState(PipelineState.ERROR)
            }
        }

        return START_NOT_STICKY
    }

    /**
     * Obtain MediaProjection from the Intent extras that MainActivity forwarded.
     * Called AFTER startForeground() so Android 14+ constraint is satisfied.
     */
    private fun acquireMediaProjection(intent: Intent?): MediaProjection? {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            intent?.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
        else
            intent?.getParcelableExtra(EXTRA_PROJECTION_DATA)

        Log.i(TAG, "acquireMediaProjection: resultCode=$resultCode data=${data != null} intent=${intent != null}")

        if (resultCode != android.app.Activity.RESULT_OK || data == null) {
            Log.w(TAG, "No projection data in Intent (code=$resultCode, data=$data)")
            if (isDebugMode) overlay.updateCaptureSource("error", "MP 데이터 없음 (code=$resultCode)")
            return MediaProjectionHolder.get()
        }

        return try {
            val pm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val mp = pm.getMediaProjection(resultCode, data)
            Log.i(TAG, "getMediaProjection 성공: mp=$mp")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                mp.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        Log.i(TAG, "MediaProjection stopped by system")
                        MediaProjectionHolder.release()
                        captureManager?.stop()
                        overlay.showSubtitle(
                            "⚠ 화면 캡처 권한이 해제되었습니다.\n앱에서 다시 시작해주세요.",
                            displayMs = 0L
                        )
                        overlay.setState(PipelineState.ERROR)
                    }
                }, Handler(Looper.getMainLooper()))
            }
            MediaProjectionHolder.set(mp)
            mp
        } catch (e: Exception) {
            Log.e(TAG, "getMediaProjection failed: ${e.javaClass.simpleName}: ${e.message}", e)
            writeCrashLog("acquireMediaProjection", e)
            if (isDebugMode) overlay.updateCaptureSource("error", "MP 실패: ${e.message?.take(40)}")
            null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "Service destroying")
        captureManager?.stop()
        chunker?.reset()
        asrEngine?.release()
        streamingEngine?.release()
        translationEngine?.release()
        overlay.detach()
        MediaProjectionHolder.release()
        serviceScope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
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

    /**
     * Suspend until no WorkManager download/extraction tasks are active.
     * Shows a waiting overlay while blocked. Max wait: 300 seconds.
     * This prevents initEngines() from seeing isBundleReady()=false when
     * the user starts the service while a large archive (e.g. Whisper Medium 1.9 GB)
     * is still being extracted.
     */
    private suspend fun waitForActiveDownloads() {
        repeat(150) {   // 150 × 2s = 300s max
            val infos = withContext(Dispatchers.IO) {
                WorkManager.getInstance(applicationContext)
                    .getWorkInfosByTag(ModelDownloadManager.TAG_DOWNLOAD)
                    .get()
            }
            val isActive = infos.any {
                it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED
            }
            if (!isActive) return
            overlay.setState(PipelineState.DOWNLOADING("모델 압축 해제 중... 잠시 기다려주세요"))
            delay(2_000)
        }
        Log.w(TAG, "waitForActiveDownloads: 300s 타임아웃 — 강제 진행")
    }

    private fun initEngines() {
        val diagLines = mutableListOf<String>()

        // --- ASR ---
        if (speedMode == SPEED_MODE_REALTIME) {
            // Streaming ASR mode — use OnlineRecognizer with language-specific model
            initStreamingAsr(diagLines)
        } else {
            // Batch ASR mode — use Whisper OfflineRecognizer
            initBatchAsr(diagLines)
        }

        // --- Translation (NLLB) ---
        // Auto-detect: prefer new split model (v2), fall back to legacy merged model
        val nllbV2Ready     = downloadManager.isBundleReady(ModelRegistry.NLLB_600M)
        val nllbLegacyReady = downloadManager.isBundleReady(ModelRegistry.NLLB_600M_LEGACY)
        val nllbDir = when {
            nllbV2Ready     -> downloadManager.bundleDir(ModelRegistry.NLLB_600M)
            nllbLegacyReady -> downloadManager.bundleDir(ModelRegistry.NLLB_600M_LEGACY)
            else            -> downloadManager.bundleDir(ModelRegistry.NLLB_600M)
        }
        val nllbReady = nllbV2Ready || nllbLegacyReady

        if (nllbReady) {
            Log.i(TAG, "NLLB 모델 감지: ${if (nllbV2Ready) "split v2" else "legacy merged"} dir=$nllbDir")
            try {
                translationEngine = NllbTranslationEngine(nllbDir)
                val nllb = translationEngine as? NllbTranslationEngine
                if (nllb?.isReady == true) {
                    Log.i(TAG, "Translation engine ready (NLLB ${if (nllbV2Ready) "split" else "legacy"})")
                    diagLines.add("번역: 로딩 성공")
                } else {
                    val err = nllb?.initError ?: "unknown"
                    Log.w(TAG, "NLLB 엔진 초기화 실패: $err")
                    translationUnavailableReason = err
                    translationEngine = null
                    diagLines.add("번역 실패: $err")
                }
            } catch (e: Throwable) {
                Log.e(TAG, "NLLB 엔진 생성 실패: ${e.javaClass.simpleName}: ${e.message}")
                writeCrashLog("NllbTranslationEngine", Exception(e))
                translationUnavailableReason = "${e.javaClass.simpleName}: ${e.message?.take(60)}"
                translationEngine = null
                diagLines.add("번역 오류: ${e.javaClass.simpleName}")
            }
        } else {
            Log.d(TAG, "NLLB 모델 없음 — v2Ready=$nllbV2Ready legacyReady=$nllbLegacyReady")
            translationUnavailableReason = "모델 미다운로드 (모델 관리에서 다운로드)"
            diagLines.add("번역 모델 없음")
        }

        val anyAsrReady = asrEngine?.isReady == true || streamingEngine?.isReady == true
        overlay.showEngineStatus(
            asrReady = anyAsrReady,
            translationReady = translationEngine?.isReady == true,
            audioSource = audioSource
        )
        // Show diagnostics on overlay (always, not just debug mode)
        if (!anyAsrReady || translationEngine?.isReady != true) {
            overlay.showSubtitle(diagLines.joinToString("\n"), displayMs = 10_000L)
        }
    }

    private fun initStreamingAsr(diagLines: MutableList<String>) {
        val bundle = when (streamingLang) {
            "ko" -> ModelRegistry.STREAMING_KO
            "zh" -> ModelRegistry.STREAMING_ZH
            "ja" -> ModelRegistry.STREAMING_JA
            else -> ModelRegistry.STREAMING_EN
        }
        val bundleReady = downloadManager.isBundleReady(bundle)
        if (bundleReady) {
            try {
                val dir = downloadManager.bundleDir(bundle)
                // PengChengStarling multilingual model uses zipformer2 architecture
                val modelType = if (streamingLang == "ja") "zipformer2" else ""
                streamingEngine = StreamingAsrEngine(
                    modelDir = dir,
                    app = application,
                    numThreads = 4,
                    modelType = modelType,
                    onPartialResult = { text ->
                        if (streamingLang == "ko") {
                            overlay.showSubtitle(text)
                        } else {
                            // 원문을 상단 소형 텍스트로 표시 (이전 한국어 번역은 유지)
                            overlay.showOriginalText(text)
                        }
                    },
                    onFinalResult = { text ->
                        if (streamingLang == "ko") {
                            overlay.showSubtitle(text)
                        } else {
                            // 원문을 상단에 표시하고 번역 시작
                            overlay.showOriginalText(text)
                            launchTranslation(text, streamingLang)
                        }
                    }
                )
                if (streamingEngine?.isReady == true) {
                    Log.i(TAG, "Streaming ASR ready (${bundle.id}, lang=$streamingLang)")
                    diagLines.add("스트리밍 ASR: 로딩 성공 ($streamingLang)")
                } else {
                    Log.e(TAG, "Streaming ASR 초기화 실패")
                    streamingEngine = null
                    diagLines.add("스트리밍 ASR 초기화 실패")
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Streaming ASR 예외: ${e.javaClass.simpleName}: ${e.message}")
                writeCrashLog("StreamingAsrEngine", Exception(e))
                streamingEngine = null
                diagLines.add("스트리밍 ASR 오류: ${e.javaClass.simpleName}")
            }
        } else {
            Log.w(TAG, "Streaming model not ready: ${bundle.id}")
            diagLines.add("스트리밍 모델 없음: ${bundle.id}\n모델 관리에서 다운로드하세요")
        }
    }

    private fun initBatchAsr(diagLines: MutableList<String>) {
        val whisperDir = downloadManager.whisperModelDir()
        val asrReady = downloadManager.isBundleReady(ModelRegistry.ACTIVE_ASR)
        if (asrReady) {
            try {
                val asrThreads = 4  // 균형/빠름 모두 S24급 기준 4스레드
                asrEngine = SherpaAsrEngine(whisperDir, application, numThreads = asrThreads)
                Log.i(TAG, "ASR engine ready (${ModelRegistry.ACTIVE_ASR.id})")
                diagLines.add("ASR: 로딩 성공")
            } catch (e: Throwable) {
                Log.e(TAG, "ASR 엔진 생성 실패: ${e.javaClass.simpleName}: ${e.message}")
                writeCrashLog("SherpaAsrEngine", Exception(e))
                val msg = if (e is OutOfMemoryError || e.message?.contains("memory", ignoreCase = true) == true)
                    "ASR 로딩 실패: 메모리 부족\nWhisper Medium은 여유 RAM 2 GB 이상 필요\n다른 앱을 모두 종료 후 재시작하세요"
                else
                    "ASR 오류: ${e.javaClass.simpleName}: ${e.message?.take(80)}"
                diagLines.add(msg)
            }
        } else {
            val missing = ModelRegistry.ACTIVE_ASR.requiredFiles.filter {
                !java.io.File(whisperDir, it).exists()
            }
            Log.w(TAG, "ASR model not ready — missing: $missing dir=$whisperDir")
            diagLines.add("ASR 모델 없음: ${missing.joinToString()}")
        }
    }

    private fun launchTranslation(text: String, lang: String) {
        val cacheKey = "$lang:$text"
        val cached = translationCache[cacheKey]
        if (cached != null) {
            overlay.showSubtitle(cached)
            return
        }
        serviceScope.launch(CoroutineDispatcherProvider.translation) {
            try {
                val translated = translateOrFallback(text, lang)
                if (translated.isNotBlank() && translated != text) {
                    translationCache[cacheKey] = translated
                    overlay.showSubtitle(translated)
                } else {
                    overlay.showSubtitle(text) // 번역 결과 없으면 원문 폴백
                }
            } catch (e: Exception) {
                Log.e(TAG, "번역 오류: ${e.message}")
                overlay.showSubtitle(text) // 오류 시 원문 폴백
            }
        }
    }

    // -------------------------------------------------------------------------
    // Audio pipeline
    // -------------------------------------------------------------------------

    private fun startPipeline(projection: android.media.projection.MediaProjection?, forceMic: Boolean = false) {
        if (speedMode == SPEED_MODE_REALTIME && streamingEngine != null) {
            startStreamingPipeline(projection, forceMic)
        } else {
            startBatchPipeline(projection, forceMic)
        }
    }

    private fun startStreamingPipeline(projection: android.media.projection.MediaProjection?, forceMic: Boolean) {
        Log.i(TAG, "속도 모드: realtime (스트리밍 ASR, lang=$streamingLang)")
        captureManager = AudioCaptureManager(
            chunker = null,
            onRawAudio = { pcm -> streamingEngine?.feedAudio(pcm) },
            mediaProjection = projection,
            onLevelUpdate = { dbfs -> overlay.updateLevel(dbfs) },
            onCaptureSourceReady = { source, config ->
                Log.i(TAG, "캡처 소스 확정: source=$source config=$config")
                overlay.updateCaptureSource(source, config)
            },
            onCaptureError = { error ->
                Log.e(TAG, "캡처 오류: $error")
                overlay.showSubtitle("⚠ 오디오 캡처 오류: $error", displayMs = 0L)
                overlay.setState(PipelineState.ERROR)
            },
            forceMic = forceMic,
            scope = serviceScope
        ).also { it.start() }

        overlay.setState(PipelineState.LISTENING)
        // No chunk processing loop needed — StreamingAsrEngine handles everything via callbacks
    }

    private fun startBatchPipeline(projection: android.media.projection.MediaProjection?, forceMic: Boolean) {
        val (chunkSec, stepSec) = when (speedMode) {
            SPEED_MODE_FAST -> 2.0f to 0.75f
            else            -> 3.0f to 1.0f   // BALANCED
        }
        Log.i(TAG, "속도 모드: $speedMode (청크 ${chunkSec}s / 스트라이드 ${stepSec}s)")
        val audioChunker = AudioChunker(chunkSeconds = chunkSec, stepSeconds = stepSec)
        chunker = audioChunker

        captureManager = AudioCaptureManager(
            chunker = audioChunker,
            mediaProjection = projection,
            onLevelUpdate = { dbfs -> overlay.updateLevel(dbfs) },
            onCaptureSourceReady = { source, config ->
                Log.i(TAG, "캡처 소스 확정: source=$source config=$config")
                overlay.updateCaptureSource(source, config)
            },
            onCaptureError = { error ->
                Log.e(TAG, "캡처 오류: $error")
                overlay.showSubtitle("⚠ 오디오 캡처 오류: $error", displayMs = 0L)
                overlay.setState(PipelineState.ERROR)
            },
            forceMic = forceMic,
            scope = serviceScope
        ).also { it.start() }

        overlay.setState(PipelineState.LISTENING)

        serviceScope.launch(CoroutineDispatcherProvider.asr) {
            for (chunk in audioChunker.output) {
                try {
                    processChunk(chunk)
                } catch (e: Exception) {
                    Log.e(TAG, "청크 처리 오류 (루프 유지): ${e.javaClass.simpleName}: ${e.message}")
                    writeCrashLog("processChunk", e)
                    overlay.setState(PipelineState.LISTENING)
                }
            }
            Log.w(TAG, "청크 채널 종료 — 파이프라인 중단")
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
        val allZero = chunk.all { it == 0f }

        // RMS 표시 — debug 모드일 때만
        if (isDebugMode) {
            overlay.showDebugInfo(
                rms = rms,
                translationReady = translationEngine?.isReady == true,
                extraInfo = if (allZero) "⚠ 오디오 데이터 0 (캡처 차단?)" else "samples=${chunk.size}"
            )
        }

        if (rms < VAD_RMS_THRESHOLD) {
            silentChunkCount++
            Log.v(TAG, "VAD: 묵음 (rms=${"%.4f".format(rms)}, 연속=${silentChunkCount}회) — skip")
            if (silentChunkCount == SILENCE_WARN_CHUNKS) {
                val msg = if (audioSource == AUDIO_SOURCE_SYSTEM)
                    "⚠ 소리 없음 — 앱이 오디오 캡처 차단 중일 수 있음\n(YouTube 등 DRM 앱은 차단됨)"
                else
                    "⚠ 마이크 입력 없음 — 마이크 권한 또는 소리 확인"
                overlay.showSilenceWarning(msg)
                Log.w(TAG, "지속적 묵음 경고: $msg")
            }
            overlay.setState(PipelineState.LISTENING)
            return
        }
        silentChunkCount = 0  // 소리 감지 시 카운터 리셋
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
        if (lang == "ko") {
            Log.i(TAG, "언어=ko → 번역 생략, 원문 표시")
            overlay.showSubtitle(result.text)
            overlay.setState(PipelineState.LISTENING)
        } else {
            val engine = translationEngine
            if (engine != null && engine.isReady) {
                val cacheKey = "$lang:${result.text}"
                val cached = translationCache[cacheKey]
                if (cached != null) {
                    Log.i(TAG, "번역 캐시 히트: \"$cached\"")
                    overlay.showSubtitle(cached)
                    overlay.setState(PipelineState.LISTENING)
                } else {
                    // 원문을 상단에 작게 표시, 이전 번역 유지, 새 번역 완료 시 교체
                    overlay.showOriginalText(result.text)
                    overlay.setState(PipelineState.TRANSLATING)
                    serviceScope.launch(CoroutineDispatcherProvider.translation) {
                        try {
                            val translated = translateOrFallback(result.text, lang)
                            Log.i(TAG, "비동기 번역 완료: \"$translated\"")
                            if (translated.isNotBlank() && translated != result.text) {
                                translationCache[cacheKey] = translated
                                overlay.showSubtitle(translated)
                            } else {
                                overlay.showSubtitle(result.text) // 번역 결과 없으면 원문 폴백
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "비동기 번역 오류: ${e.message}")
                            overlay.showSubtitle(result.text) // 오류 시 원문 폴백
                        } finally {
                            overlay.setState(PipelineState.LISTENING)
                        }
                    }
                }
            } else {
                overlay.showSubtitle("[번역 미준비: $translationUnavailableReason]\n${result.text}")
                Log.w(TAG, "번역 엔진 없음 (reason=$translationUnavailableReason) → 원문 표시 (lang=$lang)")
                overlay.setState(PipelineState.LISTENING)
            }
        }
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
        } catch (e: Throwable) {
            val err = "${e.javaClass.simpleName}: ${e.message?.take(80)}"
            Log.e(TAG, "Translation error: $err", e)
            "[번역 오류: $err]\n$text"
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
