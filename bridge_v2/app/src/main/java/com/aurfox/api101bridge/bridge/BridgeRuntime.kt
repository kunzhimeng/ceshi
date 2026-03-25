package com.aurfox.api101bridge.bridge

import android.content.Context
import android.util.Log
import dalvik.system.DexClassLoader
import dalvik.system.DexFile
import dalvik.system.InMemoryDexClassLoader
import dalvik.system.PathClassLoader
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
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

private data class ParentCandidate(
    val label: String,
    val loader: ClassLoader?,
)

private class ApiBridgeParent(
    parent: ClassLoader?,
    private val known: Map<String, Class<*>>,
) : ClassLoader(parent) {
    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        known[name]?.let { return it }
        return super.loadClass(name, resolve)
    }
}

object BridgeRuntime {
    private const val TAG = "API101BridgeKEF888"
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
        Log.e(TAG, "PROBE-0323-KEF-PROBE-888")
        val loaded = ensureLoaded(param) ?: run {
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
            Log.e(TAG, "dispatchPackageLoaded failed: ${throwableChain(it)}", it)
        }
    }

    private fun ensureLoaded(packageParam: PackageLoadedParam? = null): LoadedPlugin? {
        loadedRef.get()?.let { return it }
        return runCatching {
            val ctx = currentApplicationContextOrNull() ?: run {
                Log.e(TAG, "currentApplication still null, skip this round")
                return null
            }

            val outerApk = PluginStorage.materializeBundledPlugin(ctx, HOST_PACKAGE)
                ?: error("materialized outer apk null")
            Log.e(TAG, "outerApk=${outerApk.absolutePath}")

            inspectDexFileClasses(outerApk, "outer")

            val candidate = resolveCandidate(outerApk, ctx)
            Log.e(TAG, "selected candidate label=${candidate.label}")
            Log.e(TAG, "selected candidate apk=${candidate.apk.absolutePath}")
            Log.e(TAG, "selected candidate entry=${candidate.entry}")

            inspectDexFileClasses(candidate.apk, candidate.label)
            probeDexLoadClass(candidate.apk, candidate.entry, candidate.label)

            val apiBridgeParent = buildApiBridgeParent(packageParam ?: error("packageParam required on first load"))
            val loaderResult = loadEntryClassWithStrategies(candidate.apk, candidate.entry, ctx, apiBridgeParent)
            Log.e(TAG, "entry load strategy=${loaderResult.strategy}")
            Log.e(TAG, "entryClassLoader=${loaderResult.entryClass.classLoader}")
            Log.e(TAG, "entrySuper=${loaderResult.entryClass.superclass?.name}")
            Log.e(TAG, "entryConstructors=${loaderResult.entryClass.constructors.joinToString()}")

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
            Log.e(TAG, "ensureLoaded failed: ${throwableChain(it)}", it)
            null
        }
    }

    private fun resolveCandidate(outerApk: File, ctx: Context): Candidate {
        val outerEntry = readJavaInit(outerApk)
        val outerHas = hasDexClass(outerApk, outerEntry)
        Log.e(TAG, "outerEntry=$outerEntry")
        Log.e(TAG, "outerHasClass=$outerHas")
        if (!outerEntry.isNullOrBlank() && outerHas) {
            return Candidate(outerApk, outerEntry, "outer")
        }

        val innerApks = extractInnerApks(outerApk, ctx)
        Log.e(TAG, "inner apk count=${innerApks.size}")
        innerApks.forEach { inner ->
            val entry = readJavaInit(inner)
            val has = hasDexClass(inner, entry)
            Log.e(TAG, "inner apk=${inner.absolutePath}")
            Log.e(TAG, "inner entry=$entry")
            Log.e(TAG, "inner hasClass=$has")
            if (!entry.isNullOrBlank() && has) {
                return Candidate(inner, entry, "inner")
            }
        }

        error("no valid outer/inner candidate")
    }

    private fun buildApiBridgeParent(packageParam: PackageLoadedParam): ClassLoader {
        val runtimeParent =
            hostModule.javaClass.classLoader
                ?: BridgeRuntime::class.java.classLoader
                ?: XposedModule::class.java.classLoader

        val known = linkedMapOf<String, Class<*>>()
        known[XposedModule::class.java.name] = XposedModule::class.java
        known[XposedInterface::class.java.name] = XposedInterface::class.java
        known[XposedModuleInterface::class.java.name] = XposedModuleInterface::class.java
        known[ModuleLoadedParam::class.java.name] = ModuleLoadedParam::class.java
        known[PackageLoadedParam::class.java.name] = PackageLoadedParam::class.java
        known[hostModule.javaClass.name] = hostModule.javaClass
        known[hostModuleLoadedParam.javaClass.name] = hostModuleLoadedParam.javaClass
        known[packageParam.javaClass.name] = packageParam.javaClass
        hostModuleLoadedParam.javaClass.interfaces.forEach { known[it.name] = it }
        packageParam.javaClass.interfaces.forEach { known[it.name] = it }

        Log.e(TAG, "api bridge runtimeParent=$runtimeParent")
        Log.e(TAG, "api bridge known=${known.keys.joinToString()}")

        return ApiBridgeParent(runtimeParent, known)
    }

    private fun parentCandidates(apiBridgeParent: ClassLoader): List<ParentCandidate> {
        val list = mutableListOf<ParentCandidate>()
        list += ParentCandidate("ApiBridgeParent", apiBridgeParent)
        list += ParentCandidate("hostModule.javaClass.classLoader", hostModule.javaClass.classLoader)
        list += ParentCandidate("BridgeRuntime::class.java.classLoader", BridgeRuntime::class.java.classLoader)
        list += ParentCandidate("XposedModule::class.java.classLoader", XposedModule::class.java.classLoader)
        hostModule.javaClass.classLoader?.parent?.let {
            list += ParentCandidate("hostModule.javaClass.classLoader.parent", it)
        }
        list += ParentCandidate("boot(null)", null)
        return list.distinctBy { System.identityHashCode(it.loader) }
    }

    private fun loadEntryClassWithStrategies(
        pluginApk: File,
        entryClassName: String,
        ctx: Context,
        apiBridgeParent: ClassLoader,
    ): LoaderResult {
        val parents = parentCandidates(apiBridgeParent)

        for (p in parents) {
            Log.e(
                TAG,
                "parent=${p.label}, loader=${p.loader}, " +
                    "canXposedModule=${canLoad(p.loader, XposedModule::class.java.name)}, " +
                    "canXposedInterface=${canLoad(p.loader, ReflectionNames.XPOSED_INTERFACE)}, " +
                    "canModuleLoadedParam=${canLoad(p.loader, ReflectionNames.MODULE_LOADED_PARAM)}, " +
                    "canPackageLoadedParam=${canLoad(p.loader, ReflectionNames.PACKAGE_LOADED_PARAM)}",
            )

            runCatching {
                Log.e(TAG, "trying PathClassLoader parent=${p.label}")
                val loader = PathClassLoader(pluginApk.absolutePath, p.loader)
                val entryClass = loader.loadClass(entryClassName)
                return LoaderResult(loader, entryClass, "PathClassLoader/${p.label}")
            }.onFailure {
                Log.e(TAG, "PathClassLoader/${p.label} failed: ${throwableChain(it)}", it)
            }

            runCatching {
                Log.e(TAG, "trying InMemoryDexClassLoader parent=${p.label}")
                val buffers = readAllDexBuffers(pluginApk)
                Log.e(TAG, "creating InMemoryDexClassLoader, dexCount=${buffers.size}")
                val loader = InMemoryDexClassLoader(buffers.toTypedArray(), p.loader)
                val entryClass = loader.loadClass(entryClassName)
                return LoaderResult(loader, entryClass, "InMemoryDexClassLoader/${p.label}")
            }.onFailure {
                Log.e(TAG, "InMemoryDexClassLoader/${p.label} failed: ${throwableChain(it)}", it)
            }

            runCatching {
                Log.e(TAG, "trying DexClassLoader parent=${p.label}")
                val optimizedDir = File(ctx.cacheDir, "bridge_dex").apply { mkdirs() }
                val loader = DexClassLoader(
                    pluginApk.absolutePath,
                    optimizedDir.absolutePath,
                    pluginApk.parentFile?.absolutePath,
                    p.loader,
                )
                val entryClass = loader.loadClass(entryClassName)
                return LoaderResult(loader, entryClass, "DexClassLoader/${p.label}")
            }.onFailure {
                Log.e(TAG, "DexClassLoader/${p.label} failed: ${throwableChain(it)}", it)
            }
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
                    Log.e(TAG, "extracted inner apk=${dest.absolutePath}")
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
        Log.e(TAG, "readJavaInit failed: ${throwableChain(it)}", it)
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
            Log.e(TAG, "hasDexClass failed: ${throwableChain(it)}", it)
            false
        }
    }

    private fun readAllDexBuffers(apk: File): List<ByteBuffer> {
        return ZipFile(apk).use { zip ->
            val dexEntries = zip.entries().asSequence()
                .filter { !it.isDirectory && it.name.matches(Regex("classes(\\d*)\\.dex")) }
                .sortedBy { dexOrder(it.name) }
                .toList()
            Log.e(TAG, "dex entries=${dexEntries.joinToString { it.name }}")
            dexEntries.map { entry ->
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                Log.e(TAG, "dex entry ${entry.name} size=${bytes.size}")
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

    private fun inspectDexFileClasses(apk: File, label: String) {
        runCatching {
            val dexFile = DexFile(apk.absolutePath)
            val e = dexFile.entries()
            val all = mutableListOf<String>()
            while (e.hasMoreElements()) {
                all += e.nextElement()
            }
            dexFile.close()

            Log.e(TAG, "dexfile[$label] class count=${all.size}")
            Log.e(TAG, "dexfile[$label] ModuleMain class exists=${all.contains("com.ss.android.ugc.awemes.ModuleMain")}")
            val awemes = all.filter { it.startsWith("com.ss.android.ugc.awemes") }.sorted().take(80)
            Log.e(TAG, "dexfile[$label] awemes sample=${awemes.joinToString()}")
        }.onFailure {
            Log.e(TAG, "inspectDexFileClasses[$label] failed: ${throwableChain(it)}", it)
        }
    }

    private fun probeDexLoadClass(apk: File, className: String, label: String) {
        runCatching {
            val dexFile = DexFile(apk.absolutePath)
            val method = dexFile.javaClass.methods.firstOrNull {
                it.name == "loadClass" && it.parameterCount == 2
            } ?: error("DexFile.loadClass(String, ClassLoader) not found")
            val result = method.invoke(dexFile, className, hostModule.javaClass.classLoader)
            Log.e(TAG, "dexfile[$label] reflective loadClass result=$result")
            dexFile.close()
        }.onFailure {
            Log.e(TAG, "probeDexLoadClass[$label] failed: ${throwableChain(it)}", it)
        }
    }

    private fun currentApplicationContextOrNull(): Context? {
        return runCatching {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val currentApplicationMethod = activityThreadClass.getDeclaredMethod("currentApplication")
            currentApplicationMethod.invoke(null) as? Context
        }.getOrNull()
    }

    private fun canLoad(loader: ClassLoader?, className: String): Boolean {
        if (loader == null) return false
        return runCatching {
            loader.loadClass(className)
            true
        }.getOrDefault(false)
    }

    private fun throwableChain(t: Throwable): String {
        val parts = mutableListOf<String>()
        var cur: Throwable? = t
        while (cur != null) {
            parts += "${cur.javaClass.name}: ${cur.message}"
            cur = cur.cause
        }
        return parts.joinToString(" <- ")
    }
}
