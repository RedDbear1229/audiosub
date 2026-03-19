package com.audiosub.audio

import android.util.Log
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel

private const val TAG = "AudioChunker"

/**
 * Sliding-window ring buffer that emits [FloatArray] chunks for ASR inference.
 *
 * Strategy:
 *  - Accumulate audio from an ongoing capture stream.
 *  - Emit a chunk every [stepSamples] new samples (non-overlapping stride).
 *  - Maximum chunk length is capped at [maxChunkSamples] (30 s at 16 kHz = 480 000).
 *  - When VAD (Voice Activity Detection) signals speech end, emit immediately.
 *
 * @param sampleRate   Audio sample rate (Hz). Default 16 000.
 * @param chunkSeconds Window length emitted to ASR (seconds). Default 5 s.
 * @param stepSeconds  How often a new chunk is emitted (seconds). Default 2 s.
 * @param maxSeconds   Hard cap on buffer before forced emit (seconds). Default 30 s.
 * @param output       Channel to send completed chunks.
 */
class AudioChunker(
    private val sampleRate: Int = 16_000,
    private val chunkSeconds: Float = 5f,
    private val stepSeconds: Float = 2f,
    private val maxSeconds: Float = 30f,
    val output: Channel<FloatArray> = Channel(capacity = 3, onBufferOverflow = BufferOverflow.DROP_OLDEST)
) {
    private val chunkSamples = (sampleRate * chunkSeconds).toInt()
    private val stepSamples  = (sampleRate * stepSeconds).toInt()
    private val maxChunkSamples = (sampleRate * maxSeconds).toInt()

    private val buffer = ArrayDeque<Float>(maxChunkSamples + chunkSamples)
    private var samplesSinceLastEmit = 0

    /**
     * Feed new PCM float samples into the ring buffer.
     * Emits a chunk when [stepSamples] new samples have accumulated, or
     * when the buffer exceeds [maxChunkSamples].
     */
    suspend fun feed(samples: FloatArray) {
        for (s in samples) buffer.addLast(s)
        samplesSinceLastEmit += samples.size

        // Force emit if buffer grows too large
        if (buffer.size >= maxChunkSamples) {
            emitChunk()
            return
        }

        if (samplesSinceLastEmit >= stepSamples && buffer.size >= chunkSamples) {
            emitChunk()
        }
    }

    /** Emit the current buffer tail as a chunk and reset the step counter. */
    suspend fun flush() {
        if (buffer.size > 0) emitChunk(all = true)
    }

    private suspend fun emitChunk(all: Boolean = false) {
        val size = if (all) buffer.size else minOf(buffer.size, chunkSamples)
        val chunk = FloatArray(size)
        val list = buffer.toList()
        val startIdx = if (all) 0 else maxOf(0, list.size - size)
        for (i in 0 until size) chunk[i] = list[startIdx + i]

        // Trim oldest samples beyond one window to prevent unbounded growth
        while (buffer.size > chunkSamples) buffer.removeFirst()

        samplesSinceLastEmit = 0
        Log.v(TAG, "Emitting chunk: $size samples (${"%.2f".format(size / sampleRate.toFloat())}s)")
        output.send(chunk)
    }

    fun reset() {
        buffer.clear()
        samplesSinceLastEmit = 0
    }
}
