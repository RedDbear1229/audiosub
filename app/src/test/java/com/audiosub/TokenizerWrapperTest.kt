package com.audiosub

import com.audiosub.translation.TokenizerWrapper
import org.junit.Assert.assertEquals
import org.junit.Test

class TokenizerWrapperTest {

    @Test
    fun `English BCP-47 maps to eng_Latn`() {
        assertEquals("eng_Latn", TokenizerWrapper.toFlores200("en"))
    }

    @Test
    fun `Japanese BCP-47 maps to jpn_Jpan`() {
        assertEquals("jpn_Jpan", TokenizerWrapper.toFlores200("ja"))
    }

    @Test
    fun `Korean BCP-47 maps to kor_Hang`() {
        assertEquals("kor_Hang", TokenizerWrapper.toFlores200("ko"))
    }

    @Test
    fun `Chinese simplified maps to zho_Hans`() {
        assertEquals("zho_Hans", TokenizerWrapper.toFlores200("zh"))
    }

    @Test
    fun `Unknown code falls back to eng_Latn`() {
        assertEquals("eng_Latn", TokenizerWrapper.toFlores200("xyz"))
    }

    @Test
    fun `Case-insensitive lookup works`() {
        assertEquals("eng_Latn", TokenizerWrapper.toFlores200("EN"))
        assertEquals("jpn_Jpan", TokenizerWrapper.toFlores200("JA"))
    }

    @Test
    fun `Korean constant matches ko mapping`() {
        assertEquals(TokenizerWrapper.KOREAN, TokenizerWrapper.toFlores200("ko"))
    }
}
