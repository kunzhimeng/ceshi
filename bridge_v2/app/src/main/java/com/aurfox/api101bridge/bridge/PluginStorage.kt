package com.aurfox.api101bridge.bridge

import android.content.Context
import android.util.Log
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

object PluginStorage {
    private const val TAG = "API101BridgeV2"

    fun materializeBundledPlugin(currentContext: Context, hostPackage: String): File? {
        return runCatching {
            Log.e(TAG, "currentApplicationContext=" + currentContext.packageName)

            val appInfo = currentContext.packageManager.getApplicationInfo(hostPackage, 0)
            val hostApk = File(appInfo.sourceDir)

            Log.e(TAG, "host apk path=" + hostApk.absolutePath)

            if (!hostApk.isFile) {
                Log.e(TAG, "host apk missing")
                return null
            }

            ZipFile(hostApk).use { zip ->
                val entry = findFirstBundledApk(zip)
                Log.e(TAG, "bundled asset name=" + (entry?.name ?: "null"))

                if (entry == null) {
                    return null
                }

                val outDir = File(currentContext.cacheDir, "bridge_plugin").apply { mkdirs() }
                val outFile = File(outDir, "target-api100.apk")

                zip.getInputStream(entry).use { input ->
                    outFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                Log.e(TAG, "materialized plugin path=" + outFile.absolutePath)
                Log.e(TAG, "materialized plugin exists=" + outFile.isFile)

                outFile
            }
        }.getOrElse { e ->
            Log.e(TAG, "materializeBundledPlugin failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private fun findFirstBundledApk(zip: ZipFile): ZipEntry? {
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (!entry.isDirectory && entry.name.startsWith("assets/") && entry.name.endsWith(".apk")) {
                return entry
            }
        }
        return null
    }
}
