package com.audiosub.model

import android.content.res.ColorStateList
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.work.WorkInfo
import com.audiosub.R
import com.audiosub.databinding.ActivityModelManagerBinding

private const val TAG = "ModelManagerActivity"

/**
 * Dedicated screen for per-bundle model download management.
 *
 * Shows all bundles in [ModelRegistry.CATALOG] with:
 *  - Current status (Ready / Downloading / Not downloaded / Failed)
 *  - Download progress bar + percentage during active download
 *  - Download / Cancel / Retry / Delete action buttons
 *  - Available storage info
 */
class ModelManagerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityModelManagerBinding
    private lateinit var downloadManager: ModelDownloadManager
    private lateinit var adapter: BundleAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            binding = ActivityModelManagerBinding.inflate(layoutInflater)
            setContentView(binding.root)

            setSupportActionBar(binding.toolbar)
            supportActionBar?.setDisplayHomeAsUpEnabled(true)
            binding.toolbar.setNavigationOnClickListener { finish() }

            downloadManager = ModelDownloadManager(this)
            Log.i(TAG, "모델 디렉토리: ${downloadManager.modelsDir().absolutePath}")

            // Log each bundle status
            for (bundle in ModelRegistry.CATALOG) {
                val ready = downloadManager.isBundleReady(bundle)
                Log.i(TAG, "  [${bundle.id}] ${bundle.displayName}: ${if (ready) "✓ 준비됨" else "✗ 없음"}")
            }

            adapter = BundleAdapter(ModelRegistry.CATALOG, downloadManager) { action, bundle ->
                Log.i(TAG, "액션: $action, 번들: ${bundle.id}")
                handleAction(action, bundle)
            }
            binding.rvModels.layoutManager = LinearLayoutManager(this)
            binding.rvModels.adapter = adapter

            observeDownloadProgress()
            updateStorageInfo()
        } catch (e: Exception) {
            Log.e(TAG, "onCreate 실패", e)
            Toast.makeText(this, "모델 관리 화면 초기화 실패: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!::adapter.isInitialized) return
        adapter.refreshStatuses()
        updateStorageInfo()
    }

    // -------------------------------------------------------------------------
    // User actions
    // -------------------------------------------------------------------------

    private fun handleAction(action: BundleAction, bundle: ModelRegistry.ModelBundle) {
        when (action) {
            BundleAction.DOWNLOAD -> {
                Log.i(TAG, "[${bundle.id}] 다운로드 시작 요청 (${bundle.totalSizeBytes / 1_000_000} MB)")
                Toast.makeText(this, "'${bundle.displayName}' 다운로드 시작...", Toast.LENGTH_SHORT).show()
                val skipped = downloadManager.enqueueDownloads(listOf(bundle))
                if (skipped.isNotEmpty()) {
                    val required = bundle.totalSizeBytes / 1_000_000
                    val available = downloadManager.availableStorageBytes() / 1_000_000
                    Toast.makeText(
                        this,
                        "저장공간 부족: ${required} MB 필요, ${available} MB 사용 가능",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            BundleAction.CANCEL -> {
                AlertDialog.Builder(this)
                    .setTitle("다운로드 취소")
                    .setMessage("'${bundle.displayName}' 다운로드를 취소하고 부분 파일을 삭제할까요?")
                    .setPositiveButton("취소") { _, _ ->
                        downloadManager.deleteBundle(bundle)
                        adapter.refreshStatuses()
                    }
                    .setNegativeButton("계속", null)
                    .show()
            }
            BundleAction.RETRY -> {
                downloadManager.retryBundle(bundle)
            }
            BundleAction.DELETE -> {
                AlertDialog.Builder(this)
                    .setTitle("모델 삭제")
                    .setMessage("'${bundle.displayName}' 모델 파일을 삭제할까요?\n(${bundle.totalSizeBytes / 1_000_000} MB 회수)")
                    .setPositiveButton("삭제") { _, _ ->
                        downloadManager.deleteBundle(bundle)
                        adapter.refreshStatuses()
                        updateStorageInfo()
                    }
                    .setNegativeButton("취소", null)
                    .show()
            }
        }
    }

    // -------------------------------------------------------------------------
    // LiveData observation
    // -------------------------------------------------------------------------

    private fun observeDownloadProgress() {
        downloadManager.getWorkInfosLiveData().observe(this) { infos ->
            adapter.updateWorkInfos(infos)
            updateStorageInfo()
        }
    }

    private fun updateStorageInfo() {
        try {
            val availMB = downloadManager.availableStorageBytes() / 1_000_000
            binding.tvStorageInfo.text = "사용 가능한 저장공간: ${availMB} MB"
        } catch (e: Exception) {
            binding.tvStorageInfo.text = "저장공간 확인 불가"
        }
    }

    // =========================================================================
    // RecyclerView adapter
    // =========================================================================

    enum class BundleAction { DOWNLOAD, CANCEL, RETRY, DELETE }

    class BundleAdapter(
        private val bundles: List<ModelRegistry.ModelBundle>,
        private val manager: ModelDownloadManager,
        private val onAction: (BundleAction, ModelRegistry.ModelBundle) -> Unit
    ) : RecyclerView.Adapter<BundleAdapter.ViewHolder>() {

        private val workInfoMap = mutableMapOf<String, MutableList<WorkInfo>>()

        fun refreshStatuses() = notifyDataSetChanged()

        fun updateWorkInfos(infos: List<WorkInfo>) {
            workInfoMap.clear()
            for (info in infos) {
                val bundleTag = info.tags.firstOrNull {
                    it.startsWith(ModelDownloadManager.TAG_BUNDLE_PREFIX)
                } ?: continue
                val bundleId = bundleTag.removePrefix(ModelDownloadManager.TAG_BUNDLE_PREFIX)
                workInfoMap.getOrPut(bundleId) { mutableListOf() }.add(info)
            }
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_model_bundle, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val bundle   = bundles[position]
            val infos    = workInfoMap[bundle.id] ?: emptyList()
            val isReady  = manager.isBundleReady(bundle)
            val workInfo = infos.maxByOrNull { it.state.ordinal }

            holder.bind(bundle, isReady, workInfo, onAction)
        }

        override fun getItemCount() = bundles.size

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val tvName    = view.findViewById<TextView>(R.id.tvModelName)
            private val tvDesc    = view.findViewById<TextView>(R.id.tvModelDesc)
            private val tvStatus  = view.findViewById<TextView>(R.id.tvStatus)
            private val progress  = view.findViewById<ProgressBar>(R.id.progressBar)
            private val tvProg    = view.findViewById<TextView>(R.id.tvProgress)
            private val btnAction = view.findViewById<Button>(R.id.btnAction)
            private val btnDelete = view.findViewById<Button>(R.id.btnDelete)

            fun bind(
                bundle: ModelRegistry.ModelBundle,
                isReady: Boolean,
                workInfo: WorkInfo?,
                onAction: (BundleAction, ModelRegistry.ModelBundle) -> Unit
            ) {
                tvName.text = bundle.displayName
                tvDesc.text = bundle.description

                val ctx = itemView.context

                when {
                    isReady -> {
                        setStatus(ctx, "준비됨", R.color.badge_ready)
                        showProgress(false)
                        btnAction.visibility = View.GONE
                        btnDelete.visibility = View.VISIBLE
                        btnDelete.setOnClickListener { onAction(BundleAction.DELETE, bundle) }
                    }
                    workInfo?.state == WorkInfo.State.RUNNING ||
                    workInfo?.state == WorkInfo.State.ENQUEUED -> {
                        val pct    = workInfo.progress.getInt(ModelDownloadManager.DownloadWorker.KEY_PROGRESS, -1)
                        val phase  = workInfo.progress.getString(ModelDownloadManager.DownloadWorker.KEY_PHASE) ?: ""
                        val detail = workInfo.progress.getString(ModelDownloadManager.DownloadWorker.KEY_DETAIL) ?: ""

                        val statusLabel = when (phase) {
                            ModelDownloadManager.DownloadWorker.PHASE_EXTRACT -> "압축 해제 중"
                            ModelDownloadManager.DownloadWorker.PHASE_DONE    -> "완료"
                            else -> if (pct >= 0) "다운로드 중 $pct%" else "다운로드 중"
                        }
                        setStatus(ctx, statusLabel, R.color.badge_downloading)
                        showProgress(true, pct, buildProgressText(phase, pct, detail))
                        btnAction.text = "취소"
                        btnAction.visibility = View.VISIBLE
                        btnDelete.visibility = View.GONE
                        btnAction.setOnClickListener { onAction(BundleAction.CANCEL, bundle) }
                    }
                    workInfo?.state == WorkInfo.State.FAILED -> {
                        setStatus(ctx, "실패", R.color.badge_error)
                        showProgress(false)
                        btnAction.text = "재시도"
                        btnAction.visibility = View.VISIBLE
                        btnDelete.visibility = View.GONE
                        btnAction.setOnClickListener { onAction(BundleAction.RETRY, bundle) }
                    }
                    else -> {
                        setStatus(ctx, "미설치", R.color.badge_not_downloaded)
                        showProgress(false)
                        btnAction.text = "다운로드"
                        btnAction.visibility = View.VISIBLE
                        btnDelete.visibility = View.GONE
                        btnAction.setOnClickListener { onAction(BundleAction.DOWNLOAD, bundle) }
                    }
                }
            }

            private fun setStatus(ctx: android.content.Context, text: String, colorRes: Int) {
                tvStatus.text = text
                tvStatus.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(ctx, colorRes)
                )
            }

            private fun showProgress(visible: Boolean, pct: Int = 0, text: String = "") {
                val vis = if (visible) View.VISIBLE else View.GONE
                progress.visibility = vis
                tvProg.visibility = vis
                if (visible) {
                    if (pct >= 0) {
                        progress.isIndeterminate = false
                        progress.progress = pct
                    } else {
                        progress.isIndeterminate = true
                    }
                    tvProg.text = text
                }
            }

            private fun buildProgressText(phase: String, pct: Int, detail: String): String {
                val base = when (phase) {
                    ModelDownloadManager.DownloadWorker.PHASE_EXTRACT -> "압축 해제 중..."
                    ModelDownloadManager.DownloadWorker.PHASE_DONE    -> "완료"
                    else -> if (pct >= 0) "$pct%" else "연결 중..."
                }
                return if (detail.isNotBlank()) "$base  ·  $detail" else base
            }
        }
    }
}
