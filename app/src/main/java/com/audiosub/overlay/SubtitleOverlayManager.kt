package com.audiosub.overlay

import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.GestureDetector
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
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
    private var tvOriginal: TextView? = null
    private var tvDebug: TextView? = null
    private var statusDot: View? = null

    private var fadeAnimator: ObjectAnimator? = null
    private var subtitleHideRunnable: Runnable? = null

    private var debugMode: Boolean = false

    // Set to true when detach() is called intentionally (service stopped).
    // Prevents ensureAttached() from re-adding the overlay after service destruction.
    private var isPermanentlyDetached = false

    // Persists across showDebugInfo calls so the header line stays accurate
    private var captureSourceLine = "소스: 대기 중"
    private var engineStatusLine  = "ASR: -  번역: -"

    // Drag state
    private var isDragging = false
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var dragStartParamX = 0
    private var dragStartParamY = 0
    private var layoutParams: WindowManager.LayoutParams? = null

    private val prefs by lazy {
        context.getSharedPreferences("audiosub_overlay_prefs", Context.MODE_PRIVATE)
    }

    val isAttached: Boolean get() = overlayRoot?.isAttachedToWindow == true

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Re-attach the overlay if it was removed by the system (e.g. permission revocation,
     * system memory pressure, fullscreen transition).
     */
    private fun ensureAttached() {
        if (isPermanentlyDetached) return  // Service intentionally stopped — do not re-attach
        if (isAttached) return
        if (overlayRoot != null) {
            // View exists but is no longer attached to the window — clean up and re-attach
            Log.w(TAG, "Overlay detached by system, re-attaching")
            detach()
        }
        attach()
    }

    fun attach() {
        isPermanentlyDetached = false
        if (isAttached) return
        // Clean up stale reference if view exists but is not attached
        if (overlayRoot != null) {
            try { windowManager.removeView(overlayRoot) } catch (_: Exception) {}
            overlayRoot = null
        }

        val root = LayoutInflater.from(context).inflate(R.layout.overlay_floating, null)
        tvSubtitle = root.findViewById(R.id.tvSubtitle)
        tvOriginal = root.findViewById(R.id.tvOriginal)
        tvDebug    = root.findViewById(R.id.tvDebug)
        statusDot  = root.findViewById(R.id.statusDot)

        val hasSavedPos = prefs.contains("overlay_x")
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            if (hasSavedPos) {
                gravity = Gravity.TOP or Gravity.START
                x = prefs.getInt("overlay_x", 0)
                y = prefs.getInt("overlay_y", 0)
            } else {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = 80
            }
        }
        layoutParams = params

        setupTouchHandling(root)

        try {
            windowManager.addView(root, params)
            overlayRoot = root
            Log.i(TAG, "Overlay attached (savedPos=$hasSavedPos)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to attach overlay", e)
        }
    }

    fun detach() {
        isPermanentlyDetached = true
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
            tvOriginal = null
            tvDebug = null
            statusDot = null
            layoutParams = null
            isDragging = false
        }
    }

    // -------------------------------------------------------------------------
    // Drag handling
    // -------------------------------------------------------------------------

    @Suppress("ClickableViewAccessibility")
    private fun setupTouchHandling(root: View) {
        val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: MotionEvent) {
                isDragging = true
                dragStartX = e.rawX
                dragStartY = e.rawY
                val lp = layoutParams ?: return
                // Convert gravity-based position to absolute coordinates if needed
                if (lp.gravity != (Gravity.TOP or Gravity.START)) {
                    val location = IntArray(2)
                    root.getLocationOnScreen(location)
                    lp.gravity = Gravity.TOP or Gravity.START
                    lp.x = location[0]
                    lp.y = location[1]
                    safeUpdateLayout(root, lp)
                }
                dragStartParamX = lp.x
                dragStartParamY = lp.y
                // Haptic feedback + scale up
                root.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                root.animate().scaleX(1.05f).scaleY(1.05f).setDuration(150).start()
                Log.d(TAG, "Drag started at (${lp.x}, ${lp.y})")
            }
        })

        root.setOnTouchListener { v, event ->
            gestureDetector.onTouchEvent(event)
            if (isDragging) {
                when (event.action) {
                    MotionEvent.ACTION_MOVE -> {
                        val lp = layoutParams ?: return@setOnTouchListener false
                        lp.x = dragStartParamX + (event.rawX - dragStartX).toInt()
                        lp.y = dragStartParamY + (event.rawY - dragStartY).toInt()
                        safeUpdateLayout(v, lp)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        isDragging = false
                        v.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
                        val lp = layoutParams
                        if (lp != null) {
                            prefs.edit()
                                .putInt("overlay_x", lp.x)
                                .putInt("overlay_y", lp.y)
                                .apply()
                            Log.d(TAG, "Drag ended, saved position (${lp.x}, ${lp.y})")
                        }
                    }
                }
                true
            } else {
                false
            }
        }
    }

    // -------------------------------------------------------------------------
    // Public update API (all thread-safe via mainHandler)
    // -------------------------------------------------------------------------

    fun setState(state: PipelineState) {
        mainHandler.post {
            ensureAttached()
            statusDot?.backgroundTintList =
                android.content.res.ColorStateList.valueOf(state.dotColor)
        }
    }

    fun showSubtitle(text: String, displayMs: Long = SUBTITLE_DISPLAY_MS) {
        if (text.isBlank()) return
        Log.d(TAG, "showSubtitle: \"$text\"")
        mainHandler.post {
            ensureAttached()
            val tv = tvSubtitle ?: return@post
            cancelSubtitleFade()
            tv.text = text
            tv.alpha = 1f
            tv.visibility = View.VISIBLE
            // Hide original text when translation (main subtitle) arrives
            tvOriginal?.visibility = View.GONE
            subtitleHideRunnable = Runnable { fadeOutSubtitle() }.also {
                mainHandler.postDelayed(it, displayMs)
            }
        }
    }

    /**
     * Shows original language text in small font above the main subtitle.
     * Used during streaming ASR partial results and while translation is in progress.
     * Does NOT reset the main subtitle — previous Korean translation stays visible.
     */
    fun showOriginalText(text: String) {
        if (text.isBlank()) return
        mainHandler.post {
            ensureAttached()
            val tv = tvOriginal ?: return@post
            tv.text = text
            tv.visibility = View.VISIBLE
        }
    }

    fun hideOriginalText() {
        mainHandler.post {
            tvOriginal?.visibility = View.GONE
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
        translationReady: Boolean = false,
        extraInfo: String? = null
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
            if (extraInfo != null) sb.append("\n$extraInfo")

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
        // Level display removed — kept as no-op for API compatibility
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /** Safely update the overlay layout, re-attaching if the window token is stale. */
    private fun safeUpdateLayout(view: View, params: WindowManager.LayoutParams) {
        try {
            if (view.isAttachedToWindow) {
                windowManager.updateViewLayout(view, params)
            }
        } catch (e: Exception) {
            Log.e(TAG, "updateViewLayout failed: ${e.message}, re-attaching")
            ensureAttached()
        }
    }

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
