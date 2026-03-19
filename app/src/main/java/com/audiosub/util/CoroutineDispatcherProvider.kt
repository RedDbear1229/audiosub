package com.audiosub.util

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

object CoroutineDispatcherProvider {
    val io: CoroutineDispatcher = Dispatchers.IO
    val default: CoroutineDispatcher = Dispatchers.Default
    val main: CoroutineDispatcher = Dispatchers.Main

    /** Single-thread dispatcher for serial ASR inference (Whisper is not thread-safe). */
    val asr: CoroutineDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "asr-thread").also { it.isDaemon = true }
    }.asCoroutineDispatcher()

    /** Single-thread dispatcher for serial translation inference. */
    val translation: CoroutineDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "translation-thread").also { it.isDaemon = true }
    }.asCoroutineDispatcher()
}
