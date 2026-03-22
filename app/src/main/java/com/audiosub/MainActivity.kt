package com.audiosub

import android.app.ActivityManager
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.audiosub.databinding.ActivityMainBinding
import com.audiosub.model.ModelDownloadManager
import com.audiosub.model.ModelDownloadManager.DownloadWorker.Companion.KEY_BUNDLE_ID
import com.audiosub.model.ModelDownloadManager.DownloadWorker.Companion.KEY_PHASE
import com.audiosub.model.ModelDownloadManager.DownloadWorker.Companion.KEY_PROGRESS
import com.audiosub.model.ModelDownloadManager.DownloadWorker.Companion.PHASE_DONE
import com.audiosub.model.ModelDownloadManager.DownloadWorker.Companion.PHASE_EXTRACT
import com.audiosub.model.ModelManagerActivity
import com.audiosub.model.ModelRegistry
import com.audiosub.overlay.SubtitleOverlayManager
import com.audiosub.service.AudioCaptureService
import com.audiosub.util.PermissionHelper

private const val TAG = "MainActivity"
private const val PREFS_NAME = "audiosub_prefs"
private const val PREF_DEBUG_MODE = "debug_mode"
private const val PREF_AUDIO_SOURCE = "audio_source"
private const val PREF_SPEED_MODE = "speed_mode"
private const val PREF_STREAMING_LANG = "streaming_lang"

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var downloadManager: ModelDownloadManager

    private var serviceRunning = false

    private val projectionManager: MediaProjectionManager by lazy {
        getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // Android 14+: getMediaProjection() must be called AFTER the mediaProjection-type
        // foreground service has called startForeground(). So we pass the raw resultCode
        // and data Intent to the service and let it call getMediaProjection() itself.
        val prefs2 = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val isDebug = prefs2.getBoolean(PREF_DEBUG_MODE, false)
        val speedMode = prefs2.getString(PREF_SPEED_MODE, AudioCaptureService.SPEED_MODE_BALANCED)
            ?: AudioCaptureService.SPEED_MODE_BALANCED
        val streamingLang = prefs2.getString(PREF_STREAMING_LANG, "en") ?: "en"
        val serviceIntent = Intent(this, AudioCaptureService::class.java).apply {
            if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
                putExtra(AudioCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(AudioCaptureService.EXTRA_PROJECTION_DATA, result.data)
                Log.i(TAG, "MediaProjection consent granted — passing to service")
            } else {
                Log.w(TAG, "MediaProjection denied (resultCode=${result.resultCode}) — mic fallback")
            }
            putExtra(AudioCaptureService.EXTRA_AUDIO_SOURCE, AudioCaptureService.AUDIO_SOURCE_SYSTEM)
            putExtra(AudioCaptureService.EXTRA_DEBUG_MODE, isDebug)
            putExtra(AudioCaptureService.EXTRA_SPEED_MODE, speedMode)
            putExtra(AudioCaptureService.EXTRA_STREAMING_LANG, streamingLang)
        }
        startForegroundService(serviceIntent)
        serviceRunning = true
        binding.btnToggleService.text = getString(R.string.stop_service)
        showStatus(getString(R.string.listening))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        downloadManager = ModelDownloadManager(this)

        // Version label
        val versionName = packageManager.getPackageInfo(packageName, 0).versionName
        binding.tvVersion.text = "v$versionName"

        // Restore prefs state
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val savedDebug = prefs.getBoolean(PREF_DEBUG_MODE, false)
        val savedSource = prefs.getString(PREF_AUDIO_SOURCE, AudioCaptureService.AUDIO_SOURCE_SYSTEM)
            ?: AudioCaptureService.AUDIO_SOURCE_SYSTEM
        val savedSpeed = prefs.getString(PREF_SPEED_MODE, AudioCaptureService.SPEED_MODE_BALANCED)
            ?: AudioCaptureService.SPEED_MODE_BALANCED
        val savedStreamingLang = prefs.getString(PREF_STREAMING_LANG, "en") ?: "en"

        binding.switchDebugMode.isChecked = savedDebug
        if (savedSource == AudioCaptureService.AUDIO_SOURCE_MIC) {
            binding.radioMic.isChecked = true
        } else {
            binding.radioSystem.isChecked = true
        }
        // Speed mode spinner
        val speedModes = listOf(
            getString(R.string.speed_mode_balanced)  to AudioCaptureService.SPEED_MODE_BALANCED,
            getString(R.string.speed_mode_fast)      to AudioCaptureService.SPEED_MODE_FAST,
            getString(R.string.speed_mode_realtime)  to AudioCaptureService.SPEED_MODE_REALTIME
        )
        val speedAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, speedModes.map { it.first })
        speedAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerSpeedMode.adapter = speedAdapter
        val savedSpeedIdx = speedModes.indexOfFirst { it.second == savedSpeed }.takeIf { it >= 0 } ?: 0
        binding.spinnerSpeedMode.setSelection(savedSpeedIdx)

        // Streaming language spinner
        val streamingLangs = listOf("영어" to "en", "한국어" to "ko", "중국어" to "zh", "일본어" to "ja")
        val langAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, streamingLangs.map { it.first })
        langAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerStreamingLang.adapter = langAdapter
        val savedLangIdx = streamingLangs.indexOfFirst { it.second == savedStreamingLang }.takeIf { it >= 0 } ?: 0
        binding.spinnerStreamingLang.setSelection(savedLangIdx)
        binding.spinnerStreamingLang.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, pos: Int, id: Long) {
                prefs.edit().putString(PREF_STREAMING_LANG, streamingLangs[pos].second).apply()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        // Show/hide streaming language selector based on speed mode
        binding.layoutStreamingLang.visibility =
            if (savedSpeed == AudioCaptureService.SPEED_MODE_REALTIME) View.VISIBLE else View.GONE

        // Save debug mode on change
        binding.switchDebugMode.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(PREF_DEBUG_MODE, isChecked).apply()
        }

        // Save audio source on change
        binding.radioGroupAudioSource.setOnCheckedChangeListener { _, checkedId ->
            val source = if (checkedId == R.id.radioMic) {
                AudioCaptureService.AUDIO_SOURCE_MIC
            } else {
                AudioCaptureService.AUDIO_SOURCE_SYSTEM
            }
            prefs.edit().putString(PREF_AUDIO_SOURCE, source).apply()
        }

        // Save speed mode on change
        binding.spinnerSpeedMode.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, pos: Int, id: Long) {
                val mode = speedModes[pos].second
                prefs.edit().putString(PREF_SPEED_MODE, mode).apply()
                binding.layoutStreamingLang.visibility =
                    if (mode == AudioCaptureService.SPEED_MODE_REALTIME) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        binding.btnToggleService.setOnClickListener {
            if (serviceRunning) stopAudioService()
            else checkPermissionsAndStart()
        }

        binding.btnOverlayPermission.setOnClickListener {
            PermissionHelper.openOverlayPermissionSettings(this)
        }
        binding.btnModelManager.setOnClickListener {
            startActivity(Intent(this, ModelManagerActivity::class.java))
        }

        binding.btnTestSubtitle.setOnClickListener {
            testSubtitleOverlay()
        }

        try {
            checkModelStatus()
        } catch (e: Exception) {
            Log.e(TAG, "checkModelStatus 실패", e)
            showStatus("초기화 오류: ${e.message}")
        }
    }

    override fun onResume() {
        super.onResume()
        updateOverlayPermissionButton()
        syncServiceState()
    }

    /** Sync UI with actual service running state (service may have stopped while we were away). */
    private fun syncServiceState() {
        val running = isServiceActuallyRunning()
        if (serviceRunning != running) {
            Log.i(TAG, "Service state sync: UI=$serviceRunning actual=$running")
            serviceRunning = running
            binding.btnToggleService.text = getString(
                if (running) R.string.stop_service else R.string.start_service
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun isServiceActuallyRunning(): Boolean {
        val manager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        return manager.getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == AudioCaptureService::class.java.name }
    }

    // -------------------------------------------------------------------------
    // Permission flow
    // -------------------------------------------------------------------------

    private fun checkPermissionsAndStart() {
        if (!PermissionHelper.canDrawOverlays(this)) {
            showStatus(getString(R.string.overlay_permission_required))
            binding.btnOverlayPermission.visibility = View.VISIBLE
            return
        }
        if (!PermissionHelper.hasRecordAudioPermission(this)) {
            PermissionHelper.requestRecordAudioPermission(this)
            return
        }

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val source = prefs.getString(PREF_AUDIO_SOURCE, AudioCaptureService.AUDIO_SOURCE_SYSTEM)
            ?: AudioCaptureService.AUDIO_SOURCE_SYSTEM

        if (source == AudioCaptureService.AUDIO_SOURCE_MIC) {
            startServiceWithMic()
        } else {
            requestMediaProjection()
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        @Suppress("DEPRECATION")
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PermissionHelper.REQUEST_RECORD_AUDIO) {
            if (grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                checkPermissionsAndStart()
            } else {
                Toast.makeText(this, "오디오 권한이 필요합니다.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun requestMediaProjection() {
        mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun startServiceWithMic() {
        val prefs3 = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val isDebug = prefs3.getBoolean(PREF_DEBUG_MODE, false)
        val speedMode = prefs3.getString(PREF_SPEED_MODE, AudioCaptureService.SPEED_MODE_BALANCED)
            ?: AudioCaptureService.SPEED_MODE_BALANCED
        val streamingLang = prefs3.getString(PREF_STREAMING_LANG, "en") ?: "en"
        val serviceIntent = Intent(this, AudioCaptureService::class.java).apply {
            putExtra(AudioCaptureService.EXTRA_AUDIO_SOURCE, AudioCaptureService.AUDIO_SOURCE_MIC)
            putExtra(AudioCaptureService.EXTRA_DEBUG_MODE, isDebug)
            putExtra(AudioCaptureService.EXTRA_SPEED_MODE, speedMode)
            putExtra(AudioCaptureService.EXTRA_STREAMING_LANG, streamingLang)
        }
        startForegroundService(serviceIntent)
        serviceRunning = true
        binding.btnToggleService.text = getString(R.string.stop_service)
        showStatus(getString(R.string.listening))
    }

    // -------------------------------------------------------------------------
    // Service control
    // -------------------------------------------------------------------------

    private fun startAudioService() {
        // This path is no longer used — service is started directly from mediaProjectionLauncher
    }

    private fun stopAudioService() {
        stopService(Intent(this, AudioCaptureService::class.java))
        serviceRunning = false
        binding.btnToggleService.text = getString(R.string.start_service)
        showStatus("서비스 중지됨")
    }

    // -------------------------------------------------------------------------
    // Model download status
    // -------------------------------------------------------------------------

    private fun checkModelStatus() {
        // Check each model individually and report status
        val asrReady = downloadManager.isBundleReady(ModelRegistry.ACTIVE_ASR)
        Log.i(TAG, "모델 상태: ASR(${ModelRegistry.ACTIVE_ASR.id})=${if (asrReady) "준비됨" else "없음"}")

        if (asrReady) {
            showStatus("ASR 모델 준비됨 — 시작 가능")
            return
        }

        // ASR model not ready — show status but do NOT auto-download
        showStatus("ASR 모델 없음 — '모델 관리'에서 다운로드하세요")
        observeDownloadProgress()
    }

    private fun observeDownloadProgress() {
        binding.progressBar.visibility = View.VISIBLE
        binding.tvDownloadProgress.visibility = View.VISIBLE
        binding.tvDownloadProgress.text = "대기 중..."

        WorkManager.getInstance(this)
            .getWorkInfosByTagLiveData(ModelDownloadManager.TAG_DOWNLOAD)
            .observe(this) { infos ->
                if (infos.isEmpty()) {
                    binding.progressBar.visibility = View.GONE
                    binding.tvDownloadProgress.visibility = View.GONE
                    return@observe
                }

                val allDone = infos.all {
                    it.state == WorkInfo.State.SUCCEEDED || it.state == WorkInfo.State.CANCELLED
                }
                val hasFailed = infos.any { it.state == WorkInfo.State.FAILED }

                when {
                    allDone && !hasFailed -> {
                        binding.progressBar.visibility = View.GONE
                        binding.tvDownloadProgress.visibility = View.GONE
                        val asrReady = downloadManager.isBundleReady(ModelRegistry.ACTIVE_ASR)
                        showStatus(if (asrReady) "ASR 모델 준비됨 — 시작 가능" else "모델 상태를 확인하세요")
                    }
                    hasFailed -> {
                        showStatus("다운로드 실패 — 네트워크를 확인하세요")
                        binding.progressBar.visibility = View.GONE
                    }
                    else -> {
                        val running = infos.firstOrNull { it.state == WorkInfo.State.RUNNING }
                        if (running != null) {
                            val pct      = running.progress.getInt(KEY_PROGRESS, 0)
                            val phase    = running.progress.getString(KEY_PHASE) ?: ""
                            val bundleId = running.progress.getString(KEY_BUNDLE_ID) ?: ""
                            val detail   = running.progress.getString(
                                ModelDownloadManager.DownloadWorker.KEY_DETAIL
                            ) ?: ""
                            val label = when (phase) {
                                PHASE_EXTRACT -> "[$bundleId] 압축 해제 중..."
                                PHASE_DONE    -> "[$bundleId] 완료"
                                else -> {
                                    val detailStr = if (detail.isNotBlank()) " · $detail" else ""
                                    "[$bundleId] 다운로드 중... ${pct}%${detailStr}"
                                }
                            }
                            showStatus("모델 다운로드 진행 중")
                            binding.tvDownloadProgress.text = label
                            binding.progressBar.progress = pct
                        }
                    }
                }
            }
    }

    // -------------------------------------------------------------------------
    // Subtitle overlay test
    // -------------------------------------------------------------------------

    /**
     * Directly tests the overlay without ASR or translation.
     * Shows a Korean test subtitle to verify the WindowManager overlay works.
     */
    private fun testSubtitleOverlay() {
        if (!PermissionHelper.canDrawOverlays(this)) {
            Toast.makeText(this, "오버레이 권한이 없습니다. '오버레이 권한' 버튼을 먼저 눌러주세요.", Toast.LENGTH_SHORT).show()
            return
        }
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val testOverlay = SubtitleOverlayManager(this)
        testOverlay.attach()
        testOverlay.setState(com.audiosub.overlay.PipelineState.LISTENING)

        // 1) 0s: 원문 상단 표시 (스트리밍 부분 인식 시뮬레이션)
        testOverlay.showOriginalText("This is a subtitle")
        // 2) 1s: 원문 확장 (부분 결과 업데이트)
        handler.postDelayed({
            testOverlay.showOriginalText("This is a subtitle overlay test")
            testOverlay.setState(com.audiosub.overlay.PipelineState.TRANSLATING)
        }, 1_000L)
        // 3) 2.5s: 번역 완료 → 한국어 자막 표시 (원문 자동 숨김)
        handler.postDelayed({
            testOverlay.showSubtitle("자막 오버레이 테스트입니다")
            testOverlay.setState(com.audiosub.overlay.PipelineState.LISTENING)
        }, 2_500L)
        // 4) 5s: 다음 문장 원문
        handler.postDelayed({
            testOverlay.showOriginalText("The overlay is working correctly")
            testOverlay.setState(com.audiosub.overlay.PipelineState.TRANSLATING)
        }, 5_000L)
        // 5) 6.5s: 번역 완료
        handler.postDelayed({
            testOverlay.showSubtitle("오버레이가 정상 작동합니다 ✓")
            testOverlay.setState(com.audiosub.overlay.PipelineState.LISTENING)
        }, 6_500L)
        // 6) 12s: 자동 종료
        handler.postDelayed({
            testOverlay.detach()
        }, 12_000L)
        showStatus("자막 테스트 중 — 12초 후 자동 종료")
    }

    // -------------------------------------------------------------------------
    // UI helpers
    // -------------------------------------------------------------------------

    private fun updateOverlayPermissionButton() {
        binding.btnOverlayPermission.visibility =
            if (PermissionHelper.canDrawOverlays(this)) View.GONE else View.VISIBLE
    }

    private fun showStatus(msg: String) {
        binding.tvStatus.text = msg
    }
}
