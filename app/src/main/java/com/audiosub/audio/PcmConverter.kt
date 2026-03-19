package com.audiosub.audio

/**
 * Converts PCM audio data between formats.
 */
object PcmConverter {

    /**
     * Convert a PCM Int16 byte array (little-endian) to Float32 normalized to [-1.0, 1.0].
     */
    fun int16BytesToFloat32(bytes: ByteArray, byteCount: Int): FloatArray {
        val sampleCount = byteCount / 2
        val floats = FloatArray(sampleCount)
        for (i in 0 until sampleCount) {
            val lo = bytes[i * 2].toInt() and 0xFF
            val hi = bytes[i * 2 + 1].toInt()
            val sample = (hi shl 8) or lo
            floats[i] = sample.toShort().toFloat() / 32768.0f
        }
        return floats
    }

    /**
     * Downsample [input] from [srcRate] to [dstRate] using linear interpolation.
     * Only supports integer or simple rational ratios cleanly — for production use
     * a proper polyphase resampler.
     */
    fun downsample(input: FloatArray, srcRate: Int, dstRate: Int): FloatArray {
        if (srcRate == dstRate) return input
        val ratio = srcRate.toDouble() / dstRate.toDouble()
        val outLen = (input.size / ratio).toInt()
        val output = FloatArray(outLen)
        for (i in 0 until outLen) {
            val srcIdx = i * ratio
            val lo = srcIdx.toInt().coerceIn(0, input.size - 1)
            val hi = (lo + 1).coerceIn(0, input.size - 1)
            val frac = (srcIdx - lo).toFloat()
            output[i] = input[lo] * (1f - frac) + input[hi] * frac
        }
        return output
    }

    /**
     * Stereo interleaved → mono by averaging L and R channels.
     */
    fun stereoToMono(stereo: FloatArray): FloatArray {
        val mono = FloatArray(stereo.size / 2)
        for (i in mono.indices) {
            mono[i] = (stereo[i * 2] + stereo[i * 2 + 1]) / 2f
        }
        return mono
    }
}
