package com.aurfox.api101bridge.bridge

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.util.zip.ZipFile

object NativeLibExtractor {
    private const val ROOT_DIR = "bridge_native_libs"

    @JvmStatic
    fun extractNativeLibs(
        sourceApk: File,
        ctx: Context,
        label: String,
        logTag: String,
    ): File {
        val root = File(ctx.cacheDir, ROOT_DIR).apply { mkdirs() }

        val availableAbis = linkedSetOf<String>().apply {
            Build.SUPPORTED_64_BIT_ABIS?.forEach { add(it) }
            Build.SUPPORTED_32_BIT_ABIS?.forEach { add(it) }
            Build.SUPPORTED_ABIS?.forEach { add(it) }
        }

        val abiToEntries = linkedMapOf<String, MutableList<String>>()

        ZipFile(sourceApk).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                val name = entry.name
                if (!name.startsWith("lib/") || !name.endsWith(".so")) continue
                val parts = name.split("/")
                if (parts.size < 3) continue
                val abi = parts[1]
                abiToEntries.getOrPut(abi) { mutableListOf() }.add(name)
            }
        }

        val selectedAbi = selectAbi(availableAbis, abiToEntries.keys)
            ?: error("no native libs found inside " + sourceApk.absolutePath)

        val abiDir = File(root, "$label-$selectedAbi").apply {
            if (exists()) deleteRecursively()
            mkdirs()
        }

        var extracted = 0
        ZipFile(sourceApk).use { zip ->
            abiToEntries[selectedAbi].orEmpty().forEach { name ->
                val fileName = name.substringAfterLast('/')
                val dest = File(abiDir, fileName)
                zip.getInputStream(zip.getEntry(name)).use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                dest.setReadable(true, false)
                dest.setExecutable(true, false)
                extracted++
                Log.e(logTag, "native extracted: $name -> ${dest.absolutePath}")
            }
        }

        Log.e(
            logTag,
            "native extraction finished: selectedAbi=$selectedAbi, extracted=$extracted, dir=${abiDir.absolutePath}"
        )
        return abiDir
    }

    @JvmStatic
    fun preloadNativeLibs(
        nativeLibDir: File,
        logTag: String,
    ) {
        nativeLibDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".so") }
            ?.sortedBy { it.name }
            ?.forEach { so ->
                Log.e(logTag, "native preload skipped: " + so.absolutePath)
            }
    }

    private fun selectAbi(
        preferred: Set<String>,
        available: Set<String>,
    ): String? {
        preferred.forEach { abi ->
            if (available.contains(abi)) return abi
        }
        if (available.contains("arm64-v8a")) return "arm64-v8a"
        if (available.contains("armeabi-v7a")) return "armeabi-v7a"
        return available.firstOrNull()
    }
}
