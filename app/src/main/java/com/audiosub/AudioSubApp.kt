package com.audiosub

import android.app.Application
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
        checkPreviousCrash()
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
                    append("=== AudioSub Java Crash $timestamp ===\n")
                    append("Thread: ${thread.name}\n")
                    append(trace)
                    append("\n")
                }
                Log.e("AudioSubCrash", msg)
                val dir = getExternalFilesDir(null) ?: filesDir
                File(dir, "crash_$timestamp.txt").writeText(msg)
                File(dir, "crash_log.txt").appendText(msg)
                // Clear the last-op marker since we handled this crash
                File(dir, "last_op.txt").delete()
            } catch (e: Exception) {
                Log.e("AudioSubCrash", "Failed to write crash log", e)
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    /**
     * On startup, check if last_op.txt exists.
     * If it does, the previous run died without cleaning up — likely a native (JNI) crash
     * during whatever operation was logged there.
     */
    private fun checkPreviousCrash() {
        val dir = getExternalFilesDir(null) ?: filesDir
        val lastOp = File(dir, "last_op.txt")
        if (lastOp.exists()) {
            val content = try { lastOp.readText() } catch (_: Exception) { "읽기 실패" }
            Log.e("AudioSubCrash", "⚠ 이전 실행 비정상 종료 감지 (네이티브 크래시 추정)\n마지막 작업: $content")
            val msg = "=== Native Crash Suspected ===\n마지막 작업:\n$content\n\n"
            try { File(dir, "crash_log.txt").appendText(msg) } catch (_: Exception) {}
            lastOp.delete()
        }
    }

    companion object {
        /**
         * Record the current operation to detect native crashes.
         * Call before any JNI-heavy operation (ASR transcription).
         * Call with null to clear the marker after success.
         *
         * Usage:
         *   AudioSubApp.markOperation(app, "TRANSCRIBING chunk=80000")
         *   // ... JNI call ...
         *   AudioSubApp.markOperation(app, null)  // clear on success
         */
        fun markOperation(app: Application?, op: String?) {
            val dir = app?.getExternalFilesDir(null) ?: return
            val file = File(dir, "last_op.txt")
            if (op == null) {
                file.delete()
            } else {
                val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
                try { file.writeText("[$ts] $op\n") } catch (_: Exception) {}
            }
        }
    }
}
