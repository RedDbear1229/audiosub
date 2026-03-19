package com.audiosub

import com.audiosub.audio.PcmConverter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PcmConverterTest {

    @Test
    fun `int16 max positive value converts to 1_0`() {
        // 32767 as little-endian bytes = 0xFF, 0x7F
        val bytes = byteArrayOf(0xFF.toByte(), 0x7F.toByte())
        val floats = PcmConverter.int16BytesToFloat32(bytes, 2)
        assertEquals(1, floats.size)
        assertTrue("Expected ~1.0, got ${floats[0]}", abs(floats[0] - (32767f / 32768f)) < 0.001f)
    }

    @Test
    fun `int16 min negative value converts to near -1_0`() {
        // -32768 as little-endian = 0x00, 0x80
        val bytes = byteArrayOf(0x00.toByte(), 0x80.toByte())
        val floats = PcmConverter.int16BytesToFloat32(bytes, 2)
        assertEquals(1, floats.size)
        assertTrue("Expected -1.0, got ${floats[0]}", abs(floats[0] - (-32768f / 32768f)) < 0.001f)
    }

    @Test
    fun `silence int16 bytes convert to 0_0`() {
        val bytes = byteArrayOf(0x00, 0x00, 0x00, 0x00)
        val floats = PcmConverter.int16BytesToFloat32(bytes, 4)
        assertEquals(2, floats.size)
        floats.forEach { assertTrue(abs(it) < 0.0001f) }
    }

    @Test
    fun `downsample 44100 to 16000 reduces length`() {
        val srcRate = 44_100
        val dstRate = 16_000
        val input = FloatArray(44_100) { 0f } // 1 second
        val output = PcmConverter.downsample(input, srcRate, dstRate)
        val expectedLen = (44_100.toDouble() / 44_100 * 16_000).toInt()
        assertEquals(expectedLen, output.size)
    }

    @Test
    fun `downsample identity when rates equal`() {
        val input = FloatArray(1000) { it.toFloat() }
        val output = PcmConverter.downsample(input, 16_000, 16_000)
        assertTrue(input contentEquals output)
    }

    @Test
    fun `stereoToMono averages channels`() {
        val stereo = floatArrayOf(1f, 0f, 0f, 1f) // L=1 R=0, L=0 R=1
        val mono = PcmConverter.stereoToMono(stereo)
        assertEquals(2, mono.size)
        assertEquals(0.5f, mono[0], 0.001f)
        assertEquals(0.5f, mono[1], 0.001f)
    }
}
