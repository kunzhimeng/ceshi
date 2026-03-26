package com.aurfox.api101bridge.bridge

import android.content.Context
import android.util.Log
import java.io.File
import java.util.zip.ZipFile

object ConstructorPatch {
    private const val PATCH_DIR = "bridge_ctor_patch"

    /**
     * v10 scaffold:
     * - keeps the existing APK bytes intact
     * - creates a separate artifact for the constructor-patch stage
     * - logs enough information so the next pass can focus on true dex instruction patching
     *
     * This does NOT yet rewrite invoke-direct super(...). It is intentionally isolated
     * so BridgeRuntime can switch to the constructor-patch pipeline without touching
     * the rest of the loading flow again.
     */
    @JvmStatic
    fun patchModuleMainConstructor(
        sourceApk: File,
        ctx: Context,
        label: String,
        entryClassName: String,
        runtimeSuperClassName: String,
        logTag: String,
    ): File {
        val outDir = File(ctx.cacheDir, PATCH_DIR).apply { mkdirs() }
        val outFile = File(outDir, sourceApk.nameWithoutExtension + "-$label-ctorpatch.apk")
        sourceApk.copyTo(outFile, overwrite = true)

        val runtimeSuperSlash = runtimeSuperClassName.replace('.', '/')
        val runtimeSuperDesc = "L$runtimeSuperSlash;"

        var classesDexCount = 0
        var entryStringHits = 0
        var superDescHits = 0

        runCatching {
            ZipFile(outFile).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory) continue
                    if (!entry.name.matches(Regex("classes(\\d*)\\.dex"))) continue
                    classesDexCount++
                    val raw = zip.getInputStream(entry).use { it.readBytes() }
                    val text = raw.toString(Charsets.ISO_8859_1)
                    entryStringHits += countOccurrences(text, entryClassName)
                    superDescHits += countOccurrences(text, runtimeSuperDesc)
                }
            }
        }.onFailure {
            Log.e(logTag, "ctorPatch preflight failed: ${it.javaClass.simpleName}: ${it.message}", it)
        }

        Log.e(
            logTag,
            "ctorPatch preflight: entry=$entryClassName, runtimeSuper=$runtimeSuperClassName, " +
                "classesDexCount=$classesDexCount, entryStringHits=$entryStringHits, superDescHits=$superDescHits"
        )
        Log.e(logTag, "ctorPatch patchApplied=false (v10 scaffold only)")

        return outFile
    }

    private fun countOccurrences(haystack: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var idx = 0
        while (true) {
            val found = haystack.indexOf(needle, idx)
            if (found < 0) return count
            count++
            idx = found + needle.length
        }
    }
}
