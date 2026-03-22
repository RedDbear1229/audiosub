package com.audiosub.model

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.StatFs
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

private const val TAG = "ModelDownloadManager"

/** Minimum free storage multiplier: require 2× the archive size before downloading. */
private const val STORAGE_HEADROOM = 2.0

class ModelDownloadManager(private val context: Context) {

    private val workManager = WorkManager.getInstance(context)

    // -------------------------------------------------------------------------
    // Directory helpers
    // -------------------------------------------------------------------------

    fun modelsDir(): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "models").also { it.mkdirs() }

    fun bundleDir(bundle: ModelRegistry.ModelBundle): File =
        File(modelsDir(), bundle.id).also { it.mkdirs() }

    fun whisperModelDir(): File = bundleDir(ModelRegistry.ACTIVE_ASR)
    fun nllbModelDir(): File    = bundleDir(ModelRegistry.NLLB_600M)

    // -------------------------------------------------------------------------
    // Readiness + storage
    // -------------------------------------------------------------------------

    fun isBundleReady(bundle: ModelRegistry.ModelBundle): Boolean {
        val dir = bundleDir(bundle)
        val sizeMap: Map<String, Long> = when (val src = bundle.source) {
            is ModelRegistry.DownloadSource.IndividualFiles ->
                src.files.associate { it.name to it.sizeBytes }
            else -> emptyMap()
        }
        return bundle.requiredFiles.all { name ->
            val file = File(dir, name)
            if (!file.exists() || file.length() == 0L) return@all false
            val expected = sizeMap[name] ?: return@all true   // no size info → trust existence
            val actual = file.length()
            if (actual != expected) {
                Log.w(TAG, "isBundleReady: '$name' size mismatch (${actual} != ${expected}) → not ready")
                return@all false
            }
            true
        }
    }

    fun areModelsReady(bundles: List<ModelRegistry.ModelBundle>): Boolean =
        bundles.all { isBundleReady(it) }

    /**
     * Returns available bytes on the external storage partition that holds the models dir.
     */
    fun availableStorageBytes(): Long =
        modelsDir().let { StatFs(it.path).availableBytes }

    /**
     * Returns true if there is enough space to download [bundle].
     * Requires [STORAGE_HEADROOM]× the archive size (download temp + extracted files).
     */
    fun hasEnoughStorage(bundle: ModelRegistry.ModelBundle): Boolean =
        availableStorageBytes() >= (bundle.totalSizeBytes * STORAGE_HEADROOM).toLong()

    // -------------------------------------------------------------------------
    // Download management
    // -------------------------------------------------------------------------

    /**
     * Enqueue download workers for all bundles in [bundles] that are not yet ready.
     * Returns a list of bundle IDs that were skipped due to insufficient storage.
     */
    fun enqueueDownloads(bundles: List<ModelRegistry.ModelBundle>): List<String> {
        val skippedIds = mutableListOf<String>()
        for (bundle in bundles) {
            when {
                isBundleReady(bundle) -> {
                    Log.i(TAG, "'${bundle.id}' already ready")
                }
                !hasEnoughStorage(bundle) -> {
                    Log.w(TAG, "'${bundle.id}' skipped — insufficient storage " +
                            "(need ${bundle.totalSizeBytes / 1_000_000} MB, " +
                            "have ${availableStorageBytes() / 1_000_000} MB)")
                    skippedIds += bundle.id
                }
                else -> enqueueBundle(bundle)
            }
        }
        return skippedIds
    }

    private fun enqueueBundle(bundle: ModelRegistry.ModelBundle) {
        val inputData = workDataOf(
            DownloadWorker.KEY_BUNDLE_ID   to bundle.id,
            DownloadWorker.KEY_DEST_DIR    to bundleDir(bundle).absolutePath,
            DownloadWorker.KEY_STRATEGY    to when (bundle.source) {
                is ModelRegistry.DownloadSource.Archive        -> DownloadWorker.STRATEGY_ARCHIVE
                is ModelRegistry.DownloadSource.IndividualFiles -> DownloadWorker.STRATEGY_FILES
            },
            // Archive strategy fields
            DownloadWorker.KEY_ARCHIVE_URL    to (bundle.source as? ModelRegistry.DownloadSource.Archive)?.url,
            DownloadWorker.KEY_ARCHIVE_SHA256 to (bundle.source as? ModelRegistry.DownloadSource.Archive)?.sha256,
            DownloadWorker.KEY_ARCHIVE_BYTES  to (bundle.source as? ModelRegistry.DownloadSource.Archive)?.sizeBytes,
            // IndividualFiles strategy fields — serialised as parallel arrays
            DownloadWorker.KEY_FILE_NAMES  to (bundle.source as? ModelRegistry.DownloadSource.IndividualFiles)
                ?.files?.map { it.name }?.toTypedArray(),
            DownloadWorker.KEY_FILE_URLS   to (bundle.source as? ModelRegistry.DownloadSource.IndividualFiles)
                ?.files?.map { it.url }?.toTypedArray(),
            DownloadWorker.KEY_FILE_SHA256S to (bundle.source as? ModelRegistry.DownloadSource.IndividualFiles)
                ?.files?.map { it.sha256 }?.toTypedArray(),
            DownloadWorker.KEY_FILE_SIZES  to (bundle.source as? ModelRegistry.DownloadSource.IndividualFiles)
                ?.files?.map { it.sizeBytes }?.toLongArray(),
            DownloadWorker.KEY_REQUIRED    to bundle.requiredFiles.toTypedArray()
        )

        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(inputData)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .addTag(TAG_DOWNLOAD)
            .addTag("$TAG_BUNDLE_PREFIX${bundle.id}")
            .build()

        workManager.enqueueUniqueWork(
            "download-${bundle.id}",
            ExistingWorkPolicy.KEEP,
            request
        )
        Log.i(TAG, "Enqueued download for '${bundle.id}'")
    }

    /**
     * Cancel any pending download for [bundle] and delete all downloaded files.
     */
    fun deleteBundle(bundle: ModelRegistry.ModelBundle) {
        workManager.cancelUniqueWork("download-${bundle.id}")
        bundleDir(bundle).deleteRecursively()
        // Also clean up any temp archives
        File(modelsDir(), "${bundle.id}.tar.bz2.tmp").delete()
        Log.i(TAG, "'${bundle.id}' deleted")
    }

    /**
     * Cancel and re-enqueue a failed bundle download.
     */
    fun retryBundle(bundle: ModelRegistry.ModelBundle) {
        workManager.cancelUniqueWork("download-${bundle.id}")
        enqueueBundle(bundle)
    }

    fun getWorkInfosLiveData() =
        workManager.getWorkInfosByTagLiveData(TAG_DOWNLOAD)

    fun getBundleWorkInfoLiveData(bundle: ModelRegistry.ModelBundle) =
        workManager.getWorkInfosByTagLiveData("$TAG_BUNDLE_PREFIX${bundle.id}")

    companion object {
        const val TAG_DOWNLOAD       = "model-download"
        const val TAG_BUNDLE_PREFIX  = "bundle-"
    }

    // =========================================================================
    // CoroutineWorker — handles Archive and IndividualFiles strategies
    // =========================================================================

    class DownloadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

        private val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)   // no read timeout for large files
            .addInterceptor { chain ->
                // HuggingFace requires a recognisable User-Agent
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", "audiosub/1.0 (Android)")
                        .build()
                )
            }
            .build()

        override suspend fun doWork(): Result {
            val bundleId = inputData.getString(KEY_BUNDLE_ID) ?: return Result.failure()
            val destDir  = File(inputData.getString(KEY_DEST_DIR) ?: return Result.failure())
                .also { it.mkdirs() }
            val strategy = inputData.getString(KEY_STRATEGY) ?: STRATEGY_ARCHIVE
            val required = inputData.getStringArray(KEY_REQUIRED) ?: emptyArray()

            Log.i(TAG, "═══════════════════════════════════════")
            Log.i(TAG, "[$bundleId] 다운로드 시작")
            Log.i(TAG, "  전략: $strategy")
            Log.i(TAG, "  대상: $destDir")
            Log.i(TAG, "  필요 파일: ${required.toList()}")
            Log.i(TAG, "═══════════════════════════════════════")

            // Immediately promote to foreground so Android 14+ doesn't kill the worker
            try {
                setForeground(buildForegroundInfo(bundleId, 0))
                Log.i(TAG, "[$bundleId] setForeground (initial) OK")
            } catch (e: Exception) {
                Log.w(TAG, "[$bundleId] setForeground (initial) failed: ${e.javaClass.simpleName}: ${e.message}")
            }

            return try {
                val result = when (strategy) {
                    STRATEGY_ARCHIVE -> downloadArchive(bundleId, destDir, required)
                    STRATEGY_FILES   -> downloadIndividualFiles(bundleId, destDir, required)
                    else             -> Result.failure()
                }
                Log.i(TAG, "[$bundleId] 완료: $result")
                result
            } catch (e: Exception) {
                Log.e(TAG, "[$bundleId] Worker crashed", e)
                Result.retry()
            }
        }

        // -------------------------------------------------------------------------
        // Strategy: single tar.bz2 archive
        // -------------------------------------------------------------------------

        private suspend fun downloadArchive(
            bundleId: String,
            destDir: File,
            required: Array<String>
        ): Result {
            val url        = inputData.getString(KEY_ARCHIVE_URL)    ?: return Result.failure()
            val sha256     = inputData.getString(KEY_ARCHIVE_SHA256) ?: ""
            val totalBytes = inputData.getLong(KEY_ARCHIVE_BYTES, -1L)

            val tempFile = File(destDir.parentFile, "$bundleId.tar.bz2.tmp")

            Log.i(TAG, "[$bundleId] Archive URL: $url")
            Log.i(TAG, "[$bundleId] Archive size: ${totalBytes / 1_000_000} MB")

            // --- Download (resumable) ---
            report(0, PHASE_DOWNLOAD, bundleId)
            setForegroundIfLarge(bundleId, 0, totalBytes)

            val downloaded = downloadFile(url, tempFile, totalBytes, bundleId) { pct ->
                report(pct * 90 / 100, PHASE_DOWNLOAD, bundleId)
                setForegroundIfLarge(bundleId, pct, totalBytes)
            }
            if (!downloaded) return Result.retry()

            // --- SHA-256 verify ---
            if (sha256.isNotBlank() && !verifyFile(tempFile, sha256)) {
                Log.e(TAG, "[$bundleId] SHA-256 mismatch — deleting")
                tempFile.delete()
                return Result.failure()
            }

            // --- Extract ---
            report(90, PHASE_EXTRACT, bundleId)
            Log.i(TAG, "[$bundleId] Extracting to $destDir")
            extractTarBz2(tempFile, destDir)
            tempFile.delete()

            // --- Verify required files ---
            val missing = required.filter { !File(destDir, it).exists() }
            if (missing.isNotEmpty()) {
                Log.e(TAG, "[$bundleId] Missing after extraction: $missing")
                return Result.failure()
            }

            report(100, PHASE_DONE, bundleId)
            Log.i(TAG, "[$bundleId] Archive strategy complete")
            return Result.success()
        }

        // -------------------------------------------------------------------------
        // Strategy: individual files (HuggingFace)
        // -------------------------------------------------------------------------

        private suspend fun downloadIndividualFiles(
            bundleId: String,
            destDir: File,
            required: Array<String>
        ): Result {
            val names   = inputData.getStringArray(KEY_FILE_NAMES)   ?: return Result.failure()
            val urls    = inputData.getStringArray(KEY_FILE_URLS)    ?: return Result.failure()
            val sha256s = inputData.getStringArray(KEY_FILE_SHA256S) ?: Array(names.size) { "" }
            val sizes   = inputData.getLongArray(KEY_FILE_SIZES)     ?: LongArray(names.size) { -1L }

            val totalBytes = sizes.filter { it > 0L }.sum()

            for (i in names.indices) {
                val name   = names[i]
                val url    = urls[i]
                val sha256 = sha256s[i]
                val size   = sizes[i]
                val dest   = File(destDir, name)

                if (dest.exists() && dest.length() > 0L) {
                    if (size > 0L && dest.length() != size) {
                        Log.w(TAG, "[$bundleId] '$name' size mismatch (${dest.length()} != $size) → re-downloading")
                        dest.delete()
                    } else {
                        Log.i(TAG, "[$bundleId] '$name' already present (${dest.length()} bytes), skipping")
                        continue
                    }
                }

                Log.i(TAG, "[$bundleId] Downloading file ${i+1}/${names.size}: $name ($url)")
                report(
                    pct = if (totalBytes > 0) ((i.toLong() * 100) / names.size).toInt() else -1,
                    phase = PHASE_DOWNLOAD,
                    bundleId = bundleId,
                    detail = name,
                    fileIndex = i,
                    fileCount = names.size
                )
                setForegroundIfLarge(bundleId, 0, totalBytes)

                val ok = downloadFile(url, dest, size, bundleId) { filePct ->
                    val overall = if (names.size > 1)
                        (i * 100 + filePct) / names.size
                    else
                        filePct
                    report(overall, PHASE_DOWNLOAD, bundleId, name, i, names.size)
                    setForegroundIfLarge(bundleId, overall, totalBytes)
                }
                if (!ok) {
                    dest.delete()
                    return Result.retry()
                }

                if (sha256.isNotBlank() && !verifyFile(dest, sha256)) {
                    Log.e(TAG, "[$bundleId] SHA-256 mismatch for '$name'")
                    dest.delete()
                    return Result.failure()
                }
            }

            val missing = required.filter { !File(destDir, it).exists() }
            if (missing.isNotEmpty()) {
                Log.e(TAG, "[$bundleId] Missing: $missing")
                return Result.failure()
            }

            report(100, PHASE_DONE, bundleId)
            Log.i(TAG, "[$bundleId] IndividualFiles strategy complete")
            return Result.success()
        }

        // -------------------------------------------------------------------------
        // Shared helpers
        // -------------------------------------------------------------------------

        /**
         * Download [url] to [dest], supporting HTTP Range resumption.
         * Calls [onProgress] with 0–100 as bytes arrive.
         * Returns true on success.
         */
        private suspend fun downloadFile(
            url: String,
            dest: File,
            knownTotalBytes: Long,
            bundleId: String,
            onProgress: suspend (Int) -> Unit
        ): Boolean {
            val existingBytes = if (dest.exists()) dest.length() else 0L
            val requestBuilder = Request.Builder().url(url)
            if (existingBytes > 0L) {
                requestBuilder.addHeader("Range", "bytes=$existingBytes-")
                Log.i(TAG, "[$bundleId] Resuming from byte $existingBytes")
            }

            return try {
                client.newCall(requestBuilder.build()).execute().use { response ->
                    val isPartial = response.code == 206
                    if (!response.isSuccessful && !isPartial) {
                        Log.e(TAG, "[$bundleId] HTTP ${response.code} for $url")
                        return false
                    }
                    val body = response.body ?: return false
                    val contentLength = body.contentLength().takeIf { it > 0 } ?: -1L
                    val totalBytes    = if (isPartial) existingBytes + contentLength
                    else contentLength.takeIf { it > 0 } ?: knownTotalBytes

                    val fileOut = if (isPartial && existingBytes > 0)
                        RandomAccessFile(dest, "rw").also { it.seek(existingBytes) }
                    else
                        RandomAccessFile(dest, "rw")

                    fileOut.use { raf ->
                        val buf = ByteArray(131_072) // 128 KB
                        var downloaded = existingBytes
                        var read: Int
                        body.byteStream().use { stream ->
                            while (stream.read(buf).also { read = it } != -1) {
                                raf.write(buf, 0, read)
                                downloaded += read
                                val pct = if (totalBytes > 0)
                                    (downloaded * 100 / totalBytes).toInt().coerceIn(0, 100)
                                else -1
                                onProgress(pct)
                            }
                        }
                    }
                }
                Log.i(TAG, "[$bundleId] Downloaded ${dest.length()} bytes → ${dest.name}")
                true
            } catch (e: Exception) {
                Log.e(TAG, "[$bundleId] Download error for $url", e)
                false
            }
        }

        private fun extractTarBz2(archive: File, destDir: File) {
            BZip2CompressorInputStream(archive.inputStream().buffered(131_072)).use { bz2 ->
                TarArchiveInputStream(bz2).use { tar ->
                    generateSequence { tar.nextTarEntry }
                        .filter { !it.isDirectory }
                        .forEach { entry ->
                            val fileName = entry.name.substringAfterLast('/')
                            if (fileName.isBlank()) return@forEach
                            val out = File(destDir, fileName)
                            FileOutputStream(out).buffered(131_072).use { tar.copyTo(it, 131_072) }
                            Log.v(TAG, "  extracted: $fileName")
                        }
                }
            }
        }

        private fun verifyFile(file: File, expectedSha256: String): Boolean {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered(65_536).use { s ->
                val buf = ByteArray(65_536); var r: Int
                while (s.read(buf).also { r = it } != -1) digest.update(buf, 0, r)
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            return actual.equals(expectedSha256, ignoreCase = true)
        }

        private suspend fun report(
            pct: Int, phase: String, bundleId: String,
            detail: String = "", fileIndex: Int = -1, fileCount: Int = -1
        ) {
            setProgress(
                workDataOf(
                    KEY_PROGRESS   to pct,
                    KEY_PHASE      to phase,
                    KEY_BUNDLE_ID  to bundleId,
                    KEY_DETAIL     to detail,
                    KEY_FILE_INDEX to fileIndex,
                    KEY_FILE_COUNT to fileCount
                )
            )
        }

        /** Promote to foreground service for large downloads so the OS won't kill the worker. */
        private suspend fun setForegroundIfLarge(bundleId: String, pct: Int, totalBytes: Long) {
            if (totalBytes > 0 && totalBytes < FOREGROUND_THRESHOLD_BYTES) return
            try {
                setForeground(buildForegroundInfo(bundleId, pct))
                Log.d(TAG, "[$bundleId] setForeground OK ($pct%)")
            } catch (e: Exception) {
                Log.w(TAG, "[$bundleId] setForeground failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        private fun buildForegroundInfo(bundleId: String, pct: Int): ForegroundInfo {
            val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE)
                    as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "모델 다운로드", NotificationManager.IMPORTANCE_LOW)
                )
            }
            val title = "모델 다운로드 중..."
            val text  = if (pct >= 0) "$bundleId · $pct%" else bundleId
            val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setProgress(100, pct.coerceAtLeast(0), pct < 0)
                .setOngoing(true)
                .build()
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ForegroundInfo(
                    DOWNLOAD_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                ForegroundInfo(DOWNLOAD_NOTIFICATION_ID, notification)
            }
        }

        companion object {
            // Input data keys
            const val KEY_BUNDLE_ID    = "bundle_id"
            const val KEY_DEST_DIR     = "dest_dir"
            const val KEY_STRATEGY     = "strategy"
            const val KEY_ARCHIVE_URL  = "archive_url"
            const val KEY_ARCHIVE_SHA256 = "archive_sha256"
            const val KEY_ARCHIVE_BYTES  = "archive_bytes"
            const val KEY_FILE_NAMES   = "file_names"
            const val KEY_FILE_URLS    = "file_urls"
            const val KEY_FILE_SHA256S = "file_sha256s"
            const val KEY_FILE_SIZES   = "file_sizes"
            const val KEY_REQUIRED     = "required_files"

            // Progress output keys
            const val KEY_PROGRESS   = "progress"
            const val KEY_PHASE      = "phase"
            const val KEY_DETAIL     = "detail"
            const val KEY_FILE_INDEX = "file_index"   // 0-based current file index (-1 = n/a)
            const val KEY_FILE_COUNT = "file_count"   // total file count (-1 = n/a)

            // Strategy values
            const val STRATEGY_ARCHIVE = "archive"
            const val STRATEGY_FILES   = "files"

            // Phase labels
            const val PHASE_DOWNLOAD = "downloading"
            const val PHASE_EXTRACT  = "extracting"
            const val PHASE_DONE     = "done"

            // Promote to foreground for files larger than 50 MB
            private const val FOREGROUND_THRESHOLD_BYTES = 50_000_000L

            private const val CHANNEL_ID              = "model_download"
            private const val DOWNLOAD_NOTIFICATION_ID = 2
        }
    }
}
