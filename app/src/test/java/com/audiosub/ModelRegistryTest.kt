package com.audiosub

import com.audiosub.model.ModelRegistry
import com.audiosub.model.ModelRegistry.DownloadSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelRegistryTest {

    @Test
    fun `whisper-tiny uses Archive strategy`() {
        assertTrue(ModelRegistry.WHISPER_TINY.source is DownloadSource.Archive)
    }

    @Test
    fun `whisper-tiny archive URL is valid`() {
        val src = ModelRegistry.WHISPER_TINY.source as DownloadSource.Archive
        assertTrue(src.url.startsWith("https://"))
        assertTrue(src.url.endsWith(".tar.bz2"))
    }

    @Test
    fun `whisper-tiny required files complete`() {
        val required = ModelRegistry.WHISPER_TINY.requiredFiles
        assertTrue(required.containsAll(listOf("encoder.int8.onnx", "decoder.int8.onnx", "tokens.txt")))
    }

    @Test
    fun `NLLB uses IndividualFiles strategy`() {
        assertTrue(ModelRegistry.NLLB_600M.source is DownloadSource.IndividualFiles)
    }

    @Test
    fun `NLLB has all required file entries`() {
        val src = ModelRegistry.NLLB_600M.source as DownloadSource.IndividualFiles
        val names = src.files.map { it.name }
        assertTrue(names.contains("encoder_model.onnx"))
        assertTrue(names.contains("decoder_model_merged.onnx"))
        assertTrue(names.contains("sentencepiece.bpe.model"))
    }

    @Test
    fun `NLLB file URLs start with https`() {
        val src = ModelRegistry.NLLB_600M.source as DownloadSource.IndividualFiles
        src.files.forEach {
            assertTrue("'${it.name}' URL invalid: ${it.url}", it.url.startsWith("https://"))
        }
    }

    @Test
    fun `ACTIVE_ASR is whisper-tiny for Phase 3`() {
        assertEquals("whisper-tiny", ModelRegistry.ACTIVE_ASR.id)
    }

    @Test
    fun `ALL_ASR does not include bundles with blank URLs`() {
        val blankUrl = ModelRegistry.ALL_ASR.filter { bundle ->
            when (val src = bundle.source) {
                is DownloadSource.Archive        -> src.url.isBlank()
                is DownloadSource.IndividualFiles -> src.files.any { it.url.isBlank() }
            }
        }
        assertTrue("ALL_ASR has bundles with blank URLs: ${blankUrl.map { it.id }}",
            blankUrl.isEmpty())
    }

    @Test
    fun `CATALOG bundle IDs are unique`() {
        val ids = ModelRegistry.CATALOG.map { it.id }
        assertEquals("Bundle IDs must be unique", ids.toSet().size, ids.size)
    }

    @Test
    fun `totalSizeBytes is positive for all bundles`() {
        ModelRegistry.CATALOG.forEach {
            assertTrue("'${it.id}' totalSizeBytes must be > 0", it.totalSizeBytes > 0)
        }
    }
}
