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

/**
 * Manages the always-visible floating overlay window.
 *
 * Layout (bottom of screen):
 * ```
 * ┌──────────────────────────────────┐
 * │   자막 텍스트 (fade-out 5초)      │  ← tvSubtitle  (GONE when empty)
 * │  [진단] ASR: "..." lang=en       │  ← tvDebug     (진단 모드 시 표시)
 * │  ● 듣는 중...  -42 dBFS          │  ← statusBar   (always visible)
 * └──────────────────────────────────┘
 * ```
 *
 * Requires [android.Manifest.permission.SYSTEM_ALERT_WINDOW].
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

    val isAttached: Boolean get() = overlayRoot != null

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Inflate and add the overlay window. Idempotent.
     */
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

    /**
     * Remove the overlay window. Safe to call when not attached.
     */
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

    /**
     * Update the status bar indicator. Call at each pipeline stage transition.
     */
    fun setState(state: PipelineState) {
        mainHandler.post {
            tvStatus?.text = state.label
            statusDot?.backgroundTintList =
                android.content.res.ColorStateList.valueOf(state.dotColor)
        }
    }

    /**
     * Show a subtitle. Fades out after [displayMs] ms. Thread-safe.
     */
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

    /**
     * Show diagnostic information below the subtitle.
     * Displays ASR raw text, detected language, RMS, and translation engine status.
     * Call from AudioCaptureService during processChunk() for pipeline diagnosis.
     */
    fun showDebugInfo(
        rms: Float,
        asrText: String? = null,
        lang: String? = null,
        translationReady: Boolean = false
    ) {
        mainHandler.post {
            val tv = tvDebug ?: return@post
            val sb = StringBuilder()
            sb.append("RMS: ${"%.4f".format(rms)}")
            if (asrText != null) {
                sb.append("  lang=${lang ?: "??"}")
                sb.append("\nASR: \"${asrText.take(80)}\"")
            }
            val xlat = if (translationReady) "번역OK" else "번역없음"
            sb.append("  [$xlat]")
            tv.text = sb.toString()
            tv.visibility = View.VISIBLE
        }
    }

    /**
     * Update debug audio level meter.
     */
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
