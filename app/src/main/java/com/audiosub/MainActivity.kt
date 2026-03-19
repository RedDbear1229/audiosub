package com.audiosub

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log
import android.view.View
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
import com.audiosub.service.AudioCaptureService
import com.audiosub.util.PermissionHelper

private const val TAG = "MainActivity"

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
        val serviceIntent = Intent(this, AudioCaptureService::class.java).apply {
            if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
                putExtra(AudioCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(AudioCaptureService.EXTRA_PROJECTION_DATA, result.data)
                Log.i(TAG, "MediaProjection consent granted — passing to service")
            } else {
                Log.w(TAG, "MediaProjection denied (resultCode=${result.resultCode}) — mic fallback")
            }
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
        requestMediaProjection()
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
                requestMediaProjection()
            } else {
                Toast.makeText(this, "오디오 권한이 필요합니다.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun requestMediaProjection() {
        mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
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
