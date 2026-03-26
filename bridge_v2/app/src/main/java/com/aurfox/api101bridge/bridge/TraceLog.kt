package com.aurfox.api101bridge.bridge

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TraceLog {
    private val lock = Any()
    @Volatile
    private var logFile: File? = null

    @JvmStatic
    fun init(ctx: Context, logTag: String) {
        synchronized(lock) {
            if (logFile == null) {
                val dir = ctx.getExternalFilesDir("bridge_trace")
                    ?: File(ctx.filesDir, "bridge_trace")
                dir.mkdirs()
                val file = File(dir, "bridge-latest.log")
                if (!file.exists()) {
                    runCatching { file.createNewFile() }
                }
                logFile = file
                log(logTag, "TRACE_LOG init path=" + file.absolutePath)
            }
        }
    }

    @JvmStatic
    fun log(tag: String, msg: String) {
        Log.e(tag, msg)
        append("E", tag, msg, null)
    }

    @JvmStatic
    fun log(tag: String, msg: String, t: Throwable) {
        Log.e(tag, msg, t)
        append("E", tag, msg, t)
    }

    private fun append(level: String, tag: String, msg: String, t: Throwable?) {
        val file = logFile ?: return
        val line = buildString {
            append(timeNow())
            append(" ")
            append(level)
            append("/")
            append(tag)
            append(": ")
            append(msg)
            append("\n")
            if (t != null) {
                append(Log.getStackTraceString(t))
                append("\n")
            }
        }
        synchronized(lock) {
            runCatching {
                file.appendText(line)
            }
        }
    }

    private fun timeNow(): String {
        return SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
    }
}
