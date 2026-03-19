package com.audiosub.overlay

import android.graphics.Color

/**
 * Pipeline states reflected in the floating status bar.
 *
 * [label] is the text shown next to the status dot.
 * [dotColor] is the ARGB color of the indicator dot.
 *
 * Implemented as a sealed class (not enum) so [DOWNLOADING] can carry
 * a dynamic label with the current percentage, e.g. "다운로드 중... 42%".
 */
sealed class PipelineState(open val label: String, val dotColor: Int) {

    object INITIALIZING : PipelineState("초기화 중...",       Color.parseColor("#FFA500")) // orange
    data class DOWNLOADING(override val label: String = "모델 다운로드 중...") :
        PipelineState(label, Color.parseColor("#FF9800")) // amber
    object LISTENING    : PipelineState("듣는 중...",         Color.parseColor("#4CAF50")) // green
    object TRANSCRIBING : PipelineState("전사 중...",         Color.parseColor("#2196F3")) // blue
    object TRANSLATING  : PipelineState("번역 중...",         Color.parseColor("#9C27B0")) // purple
    object ERROR        : PipelineState("오류",               Color.parseColor("#F44336")) // red
}
