package com.aurfox.api101bridge.bridge

import android.content.Context
import android.util.Log
import dalvik.system.DexClassLoader
import dalvik.system.DexFile
import dalvik.system.InMemoryDexClassLoader
import dalvik.system.PathClassLoader
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.io.File
import java.lang.reflect.Method
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipFile

private data class LoadedPlugin(
    val classLoader: ClassLoader,
    val entryInstance: Any,
    val onPackageLoaded: Method?,
)

private data class Candidate(
    val apk: File,
    val entry: String,
    val label: String,
)

private data class LoaderResult(
    val classLoader: ClassLoader,
    val entryClass: Class<*>,
    val strategy: String,
)

object BridgeRuntime {
    private const val TAG = "API101BridgeKF777"
    private const val HOST_PACKAGE = "com.aurfox.api101bridge"

    private lateinit var hostModule: XposedModule
    private lateinit var hostModuleLoadedParam: ModuleLoadedParam
    private val loadedRef = AtomicReference<LoadedPlugin?>()

    @JvmStatic
    fun attachHost(module: XposedModule, param: ModuleLoadedParam) {
        hostModule = module
        hostModuleLoadedParam = param
        Log.e(TAG, "attachHost called")
    }

    @JvmStatic
    fun getClassLoader(): ClassLoader? = runCatching { hostModule.javaClass.classLoader }.getOrNull()

    @JvmStatic
    fun dispatchPackageLoaded(param: PackageLoadedParam) {
        Log.e(TAG, "PROBE-0323-KF-MERGED-777")
        val loaded = ensureLoaded() ?: run {
            Log.e(TAG, "ensureLoaded returned null")
            return
        }
        runCatching {
            val pluginPackageLoadedParam = loaded.classLoader.loadClass(ReflectionNames.PACKAGE_LOADED_PARAM)
            val pluginParam = PluginParamProxyFactory.create(pluginPackageLoadedParam, param)
            Log.e(TAG, "about to invoke legacy onPackageLoaded")
            loaded.onPackageLoaded?.invoke(loaded.entryInstance, pluginParam)
            Log.e(TAG, "legacy onPackageLoaded invoked")
        }.onFailure {
            Log.e(TAG, "dispatchPackageLoaded failed: ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    private fun ensureLoaded(): LoadedPlugin? {
        loadedRef.get()?.let { return it }
        return runCatching {
            val ctx = currentApplicationContext()
            val outerApk = PluginStorage.materializeBundledPlugin(ctx, HOST_PACKAGE)
                ?: error("materialized outer apk null")
            Log.e(TAG, "outerApk=" + outerApk.absolutePath)

            val candidate = resolveCandidate(outerApk, ctx)
            Log.e(TAG, "selected candidate label=" + candidate.label)
            Log.e(TAG, "selected candidate apk=" + candidate.apk.absolutePath)
            Log.e(TAG, "selected candidate entry=" + candidate.entry)

            val loaderResult = loadEntryClassWithStrategies(candidate.apk, candidate.entry, ctx)
            Log.e(TAG, "entry load strategy=" + loaderResult.strategy)

            val pluginInterface = loaderResult.classLoader.loadClass(ReflectionNames.XPOSED_INTERFACE)
            val pluginModuleLoadedParam = loaderResult.classLoader.loadClass(ReflectionNames.MODULE_LOADED_PARAM)
            val interfaceProxy = Api100InterfaceProxy.create(pluginInterface, hostModule)
            val moduleLoadedParamProxy = PluginParamProxyFactory.create(
                pluginModuleLoadedParam,
                hostModuleLoadedParam,
            )

            val ctor = loaderResult.entryClass.constructors.firstOrNull {
                it.parameterTypes.size == 2 &&
                    it.parameterTypes[0].name == pluginInterface.name &&
                    it.parameterTypes[1].name == pluginModuleLoadedParam.name
            } ?: error("no 2-arg constructor")

            val entryInstance = ctor.newInstance(interfaceProxy, moduleLoadedParamProxy)
            val onPackageLoaded = loaderResult.entryClass.methods.firstOrNull {
                it.name == "onPackageLoaded" && it.parameterTypes.size == 1
            }

            LoadedPlugin(loaderResult.classLoader, entryInstance, onPackageLoaded).also {
                loadedRef.set(it)
            }
        }.getOrElse {
            Log.e(TAG, "ensureLoaded failed: ${it.javaClass.simpleName}: ${it.message}")
            null
        }
    }

    private fun resolveCandidate(outerApk: File, ctx: Context): Candidate {
        val outerEntry = readJavaInit(outerApk)
        val outerHas = hasDexClass(outerApk, outerEntry)
        Log.e(TAG, "outerEntry=" + outerEntry)
        Log.e(TAG, "outerHasClass=" + outerHas)
        if (!outerEntry.isNullOrBlank() && outerHas) {
            return Candidate(outerApk, outerEntry, "outer")
        }

        val innerApks = extractInnerApks(outerApk, ctx)
        Log.e(TAG, "inner apk count=" + innerApks.size)
        innerApks.forEach { inner ->
            val entry = readJavaInit(inner)
            val has = hasDexClass(inner, entry)
            Log.e(TAG, "inner apk=" + inner.absolutePath)
            Log.e(TAG, "inner entry=" + entry)
            Log.e(TAG, "inner hasClass=" + has)
            if (!entry.isNullOrBlank() && has) {
                return Candidate(inner, entry, "inner")
            }
        }

        error("no valid outer/inner candidate")
    }

    private fun loadEntryClassWithStrategies(
        pluginApk: File,
        entryClassName: String,
        ctx: Context,
    ): LoaderResult {
        val parent = hostModule.javaClass.classLoader

        runCatching {
            Log.e(TAG, "trying PathClassLoader")
            val loader = PathClassLoader(pluginApk.absolutePath, parent)
            val entryClass = loader.loadClass(entryClassName)
            return LoaderResult(loader, entryClass, "PathClassLoader")
        }.onFailure {
            Log.e(TAG, "PathClassLoader failed: ${it.javaClass.simpleName}: ${it.message}")
        }

        runCatching {
            Log.e(TAG, "trying InMemoryDexClassLoader")
            val buffers = readAllDexBuffers(pluginApk)
            Log.e(TAG, "creating InMemoryDexClassLoader, dexCount=" + buffers.size)
            val loader = InMemoryDexClassLoader(buffers.toTypedArray(), parent)
            val entryClass = loader.loadClass(entryClassName)
            return LoaderResult(loader, entryClass, "InMemoryDexClassLoader")
        }.onFailure {
            Log.e(TAG, "InMemoryDexClassLoader failed: ${it.javaClass.simpleName}: ${it.message}")
        }

        runCatching {
            Log.e(TAG, "trying DexClassLoader")
            val optimizedDir = File(ctx.cacheDir, "bridge_dex").apply { mkdirs() }
            val loader = DexClassLoader(
                pluginApk.absolutePath,
                optimizedDir.absolutePath,
                pluginApk.parentFile?.absolutePath,
                parent,
            )
            val entryClass = loader.loadClass(entryClassName)
            return LoaderResult(loader, entryClass, "DexClassLoader")
        }.onFailure {
            Log.e(TAG, "DexClassLoader failed: ${it.javaClass.simpleName}: ${it.message}")
        }

        error("all loader strategies failed for $entryClassName")
    }

    private fun extractInnerApks(outerApk: File, ctx: Context): List<File> {
        val outDir = File(ctx.cacheDir, "bridge_inner_apk").apply { mkdirs() }
        val out = mutableListOf<File>()
        ZipFile(outerApk).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                val name = entry.name
                if (name.startsWith("assets/") && name.endsWith(".apk")) {
                    val dest = File(outDir, name.removePrefix("assets/").replace("/", "_"))
                    zip.getInputStream(entry).use { input ->
                        dest.outputStream().use { output -> input.copyTo(output) }
                    }
                    Log.e(TAG, "extracted inner apk=" + dest.absolutePath)
                    out += dest
                }
            }
        }
        return out
    }

    private fun readJavaInit(apk: File): String? = runCatching {
        ZipFile(apk).use { zip ->
            val entry = zip.getEntry("META-INF/xposed/java_init.list") ?: return null
            zip.getInputStream(entry).bufferedReader().use { br ->
                br.readText().trim().lineSequence().firstOrNull()?.trim()
            }
        }
    }.getOrElse {
        Log.e(TAG, "readJavaInit failed: ${it.javaClass.simpleName}: ${it.message}")
        null
    }

    private fun hasDexClass(apk: File, className: String?): Boolean {
        if (className.isNullOrBlank()) return false
        return runCatching {
            val dexFile = DexFile(apk.absolutePath)
            val e = dexFile.entries()
            var found = false
            while (e.hasMoreElements()) {
                if (e.nextElement() == className) {
                    found = true
                    break
                }
            }
            dexFile.close()
            found
        }.getOrElse {
            Log.e(TAG, "hasDexClass failed: ${it.javaClass.simpleName}: ${it.message}")
            false
        }
    }

    private fun readAllDexBuffers(apk: File): List<ByteBuffer> {
        return ZipFile(apk).use { zip ->
            val dexEntries = zip.entries().asSequence()
                .filter { !it.isDirectory && it.name.matches(Regex("classes(\\d*)\\.dex")) }
                .sortedBy { dexOrder(it.name) }
                .toList()
            Log.e(TAG, "dex entries=" + dexEntries.joinToString { it.name })
            dexEntries.map { entry ->
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                Log.e(TAG, "dex entry ${entry.name} size=" + bytes.size)
                ByteBuffer.wrap(bytes)
            }
        }
    }

    private fun dexOrder(name: String): Int {
        return when (name) {
            "classes.dex" -> 1
            else -> name.removePrefix("classes").removeSuffix(".dex").toIntOrNull() ?: Int.MAX_VALUE
        }
    }

    private fun currentApplicationContext(): Context {
        val activityThreadClass = Class.forName("android.app.ActivityThread")
        val currentApplicationMethod = activityThreadClass.getDeclaredMethod("currentApplication")
        val app = currentApplicationMethod.invoke(null) as? Context
        return app ?: error("ActivityThread.currentApplication() returned null")
    }
}
