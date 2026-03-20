package com.audiosub.overlay

import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.audiosub.R

private const val TAG = "SubtitleOverlayManager"

/** How long a subtitle remains fully visible before fading out (ms). */
private const val SUBTITLE_DISPLAY_MS = 4_500L

/** VAD threshold used for visual bar coloring — keep in sync with AudioCaptureService. */
private const val VAD_THRESHOLD = 0.008f

/** RMS level considered "loud" for bar scaling (corresponds to ~-20 dBFS). */
private const val RMS_MAX_SCALE = 0.1f

private const val BAR_LEN = 12

/**
 * Manages the always-visible floating overlay window.
 *
 * Debug panel layout (when debug mode is ON):
 * ```
 * 소스: 시스템(48k-stereo)  ASR: ✓  번역: ✗
 * ████████░░░░ 0.0421  ▶ 소리 감지
 * ASR: "Hello, this is a test"  lang=en
 * ```
 */
class SubtitleOverlayManager(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var overlayRoot: View? = null
    private var tvSubtitle: TextView? = null
    private var tvStatus: TextView? = null
    private var tvLevel: TextView? = null
    private var tvDebug: TextView? = null
    private var statusDot: View? = null

    private var fadeAnimator: ObjectAnimator? = null
    private var subtitleHideRunnable: Runnable? = null

    private var debugMode: Boolean = false

    // Persists across showDebugInfo calls so the header line stays accurate
    private var captureSourceLine = "소스: 대기 중"
    private var engineStatusLine  = "ASR: -  번역: -"

    val isAttached: Boolean get() = overlayRoot != null

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    fun attach() {
        if (isAttached) return

        val root = LayoutInflater.from(context).inflate(R.layout.overlay_floating, null)
        tvSubtitle = root.findViewById(R.id.tvSubtitle)
        tvStatus   = root.findViewById(R.id.tvStatus)
        tvLevel    = root.findViewById(R.id.tvLevel)
        tvDebug    = root.findViewById(R.id.tvDebug)
        statusDot  = root.findViewById(R.id.statusDot)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 80
        }

        try {
            windowManager.addView(root, params)
            overlayRoot = root
            Log.i(TAG, "Overlay attached")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to attach overlay", e)
        }
    }

    fun detach() {
        mainHandler.removeCallbacksAndMessages(null)
        val root = overlayRoot ?: return
        try {
            windowManager.removeView(root)
            Log.i(TAG, "Overlay detached")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to detach overlay", e)
        } finally {
            overlayRoot = null
            tvSubtitle = null
            tvStatus = null
            tvLevel = null
            tvDebug = null
            statusDot = null
        }
    }

    // -------------------------------------------------------------------------
    // Public update API (all thread-safe via mainHandler)
    // -------------------------------------------------------------------------

    fun setState(state: PipelineState) {
        mainHandler.post {
            tvStatus?.text = state.label
            statusDot?.backgroundTintList =
                android.content.res.ColorStateList.valueOf(state.dotColor)
        }
    }

    fun showSubtitle(text: String, displayMs: Long = SUBTITLE_DISPLAY_MS) {
        if (text.isBlank()) return
        Log.d(TAG, "showSubtitle: \"$text\"")
        mainHandler.post {
            val tv = tvSubtitle ?: return@post
            cancelSubtitleFade()
            tv.text = text
            tv.alpha = 1f
            tv.visibility = View.VISIBLE
            subtitleHideRunnable = Runnable { fadeOutSubtitle() }.also {
                mainHandler.postDelayed(it, displayMs)
            }
        }
    }

    fun setDebugMode(enabled: Boolean) {
        debugMode = enabled
        mainHandler.post {
            tvDebug?.visibility = if (enabled) View.VISIBLE else View.GONE
        }
    }

    /**
     * Called once after engines initialize. Updates the persistent header line in debug panel.
     */
    fun showEngineStatus(asrReady: Boolean, translationReady: Boolean, audioSource: String) {
        val src = when {
            audioSource.startsWith("system") -> "시스템"
            audioSource.startsWith("mic")    -> "마이크"
            else                             -> audioSource
        }
        engineStatusLine = "소스: $src  ASR: ${if (asrReady) "✓" else "✗"}  번역: ${if (translationReady) "✓" else "✗"}"
        if (!debugMode) return
        mainHandler.post {
            val tv = tvDebug ?: return@post
            tv.text = "$engineStatusLine\n$captureSourceLine"
            tv.visibility = View.VISIBLE
        }
    }

    /**
     * Called by AudioCaptureManager callback when actual capture source is confirmed.
     * Shows the exact config that succeeded (e.g., "48k-stereo").
     */
    fun updateCaptureSource(source: String, config: String) {
        captureSourceLine = when (source) {
            "system"       -> "✓ 시스템 오디오 ($config)"
            "mic(fallback)"-> "⚠ 마이크 폴백 (시스템 실패)"
            "mic"          -> "✓ 마이크"
            "error"        -> "✗ 캡처 초기화 실패"
            else           -> source
        }
        if (!debugMode) return
        mainHandler.post {
            val tv = tvDebug ?: return@post
            tv.text = "$engineStatusLine\n$captureSourceLine"
            tv.visibility = View.VISIBLE
        }
    }

    /**
     * Called on every audio chunk. Shows real-time RMS bar and VAD status.
     *
     * Display format:
     * ```
     * 소스: 시스템  ASR: ✓  번역: ✗
     * ✓ 시스템 오디오 (48k-stereo)
     * ████████░░░░ 0.0421  ▶ 소리 감지
     * ASR: "Hello, world"  lang=en
     * ```
     */
    fun showDebugInfo(
        rms: Float,
        asrText: String? = null,
        lang: String? = null,
        translationReady: Boolean = false
    ) {
        if (!debugMode) return
        mainHandler.post {
            val tv = tvDebug ?: return@post

            // Visual RMS bar
            val filled = ((rms / RMS_MAX_SCALE).coerceIn(0f, 1f) * BAR_LEN).toInt()
            val bar = "█".repeat(filled) + "░".repeat(BAR_LEN - filled)

            // VAD status with icon
            val vadStatus = if (rms >= VAD_THRESHOLD) "▶ 소리 감지" else "⏸ 묵음"

            val sb = StringBuilder()
            sb.append(engineStatusLine)
            sb.append("\n").append(captureSourceLine)
            sb.append("\n$bar ${"%.4f".format(rms)}  $vadStatus")

            if (asrText != null) {
                val xlat = if (translationReady) "" else " [번역없음]"
                sb.append("\nASR[${lang ?: "??"}]: \"${asrText.take(60)}\"$xlat")
            }

            tv.text = sb.toString()
            tv.visibility = View.VISIBLE
        }
    }

    /**
     * Shows a warning subtitle when audio has been silent for too long.
     * Visible regardless of debug mode — indicates a likely audio capture blockage.
     */
    fun showSilenceWarning(message: String) {
        showSubtitle(message, displayMs = 8_000L)
    }

    fun updateLevel(dbfs: Float) {
        mainHandler.post {
            val tv = tvLevel ?: return@post
            tv.visibility = View.VISIBLE
            tv.text = "%.0f dBFS".format(dbfs)
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private fun fadeOutSubtitle() {
        val tv = tvSubtitle ?: return
        fadeAnimator = ObjectAnimator.ofFloat(tv, "alpha", 1f, 0f).apply {
            duration = 700L
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    tv.visibility = View.GONE
                }
            })
            start()
        }
    }

    private fun cancelSubtitleFade() {
        fadeAnimator?.cancel()
        fadeAnimator = null
        subtitleHideRunnable?.let { mainHandler.removeCallbacks(it) }
        subtitleHideRunnable = null
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
}
