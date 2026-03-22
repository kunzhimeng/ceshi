package com.aurfox.api101bridge.bridge

import android.content.Context
import java.io.File

object PluginStorage {
    private const val TARGET_FILE_NAME = "target-api100.apk"

    /**
     * The bridge entry runs inside a hooked process, so this fixed location is more reliable
     * than depending on interactive state.
     */
    fun getDefaultPluginFile(moduleDataDir: String): File {
        return File(moduleDataDir, "files/$TARGET_FILE_NAME")
    }

    /**
     * Optional helper for the visible shell app.
     */
    fun importPlugin(context: Context, source: File): File {
        val target = File(context.filesDir, TARGET_FILE_NAME)
        source.inputStream().use { input ->
            target.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        return target
    }
}
