package com.vellora.dualapp.virtual

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Captures the same log lines that go to android.util.Log (visible via
 * adb/logcat) into a plain text file inside the app's own storage — so the
 * user can read/copy them straight from within the app, no adb or external
 * logcat viewer needed.
 */
object AppLogger {
    private const val FILE_NAME = "virtual_engine_log.txt"

    // User explicitly asked for the log to capture EVERYTHING, however big
    // it gets — bumped way up from the original 300KB so a long testing
    // session (many launches, many crashes) doesn't get silently trimmed
    // away mid-session. Still capped (not literally unbounded) so a
    // runaway logging loop can't fill up the device's storage.
    private const val MAX_BYTES = 8_000_000 // ~8 MB

    private var logFile: File? = null
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        val firstInit = logFile == null
        if (firstInit) {
            logFile = File(context.filesDir, FILE_NAME)
            i(
                "VirtualEngine",
                "── App session started: Android ${android.os.Build.VERSION.RELEASE} " +
                    "(SDK ${android.os.Build.VERSION.SDK_INT}), " +
                    "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} ──"
            )
        }
    }

    fun i(tag: String, msg: String) {
        Log.i(tag, msg)
        write("I", tag, msg, null)
    }

    fun e(tag: String, msg: String, t: Throwable? = null) {
        Log.e(tag, msg, t)
        write("E", tag, msg, t)
    }

    private fun write(level: String, tag: String, msg: String, t: Throwable?) {
        val file = logFile ?: return
        try {
            val time = dateFormat.format(Date())
            val line = StringBuilder("$time $level/$tag: $msg")
            var cause: Throwable? = t
            var depth = 0
            while (cause != null && depth < 8) {
                val prefix = if (depth == 0) "" else "Caused by: "
                line.append("\n    $prefix${cause.javaClass.name}: ${cause.message}")
                cause.stackTrace.take(15).forEach { line.append("\n        at $it") }
                cause = cause.cause
                depth++
            }
            file.appendText(line.toString() + "\n")
            if (file.length() > MAX_BYTES) {
                val trimmed = file.readText().takeLast(MAX_BYTES / 2)
                file.writeText(trimmed)
            }
        } catch (_: Exception) {
            // Logging must never crash the app it's trying to help debug.
        }
    }

    fun readAll(): String {
        val file = logFile ?: return "(logger not initialized yet)"
        return try {
            if (file.exists()) file.readText().ifBlank { "(no logs yet — clone an app and tap it, then come back here)" }
            else "(no logs yet — clone an app and tap it, then come back here)"
        } catch (e: Exception) {
            "Error reading log file: ${e.message}"
        }
    }

    fun clear() {
        try {
            logFile?.writeText("")
        } catch (_: Exception) {
        }
    }

    /** Exposes the raw log file for FileProvider-based sharing (see MainActivity's LogViewerScreen). */
    fun logFileForSharing(context: Context): File? {
        init(context) // safe no-op if already initialized
        val file = logFile ?: return null
        return if (file.exists()) file else null
    }

    /**
     * Copies the log into the device's public Downloads folder so it shows
     * up in any file manager / "Files" app, no share-sheet round-trip
     * needed — just like any other downloaded file.
     */
    fun saveToDownloads(context: Context): android.net.Uri? {
        val file = logFileForSharing(context) ?: return null
        val fileName = "cloner_log_${System.currentTimeMillis()}.txt"
        return try {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                val resolver = context.contentResolver
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain")
                }
                val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                uri?.let {
                    resolver.openOutputStream(it)?.use { out ->
                        file.inputStream().use { input -> input.copyTo(out) }
                    }
                }
                uri
            } else {
                @Suppress("DEPRECATION")
                val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS
                )
                val outFile = File(downloadsDir, fileName)
                file.copyTo(outFile, overwrite = true)
                android.net.Uri.fromFile(outFile)
            }
        } catch (e: Exception) {
            Log.e("VirtualEngine", "saveToDownloads FAILED", e)
            null
        }
    }
}
