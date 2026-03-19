package com.audiosub

import android.app.Application
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AudioSubApp : Application() {

    override fun onCreate() {
        super.onCreate()
        installCrashHandler()
    }

    private fun installCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val trace = sw.toString()

                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val msg = buildString {
                    append("=== AudioSub Crash $timestamp ===\n")
                    append("Thread: ${thread.name}\n")
                    append(trace)
                    append("\n")
                }

                Log.e("AudioSubCrash", msg)

                // Write to a file readable without adb
                val dir = getExternalFilesDir(null) ?: filesDir
                val file = File(dir, "crash_$timestamp.txt")
                file.writeText(msg)

                // Also append to a persistent crash log
                val log = File(dir, "crash_log.txt")
                log.appendText(msg)

                Log.e("AudioSubCrash", "Crash log written to: ${file.absolutePath}")
            } catch (e: Exception) {
                Log.e("AudioSubCrash", "Failed to write crash log", e)
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
