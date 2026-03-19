package com.audiosub

import com.audiosub.asr.AsrResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AsrResultTest {

    @Test
    fun `blank text is empty`() {
        assertTrue(AsrResult("").isEmpty)
        assertTrue(AsrResult("   ").isEmpty)
    }

    @Test
    fun `non-blank text is not empty`() {
        assertFalse(AsrResult("Hello").isEmpty)
        assertFalse(AsrResult("안녕하세요").isEmpty)
    }
}
