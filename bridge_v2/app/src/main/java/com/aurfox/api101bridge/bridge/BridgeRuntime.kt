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
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

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

private data class RuntimeApiNames(
    val moduleClassName: String,
    val interfaceClassName: String,
    val moduleInterfaceClassName: String,
    val moduleLoadedParamClassName: String,
    val packageLoadedParamClassName: String,
    val beforeHookCallbackClassName: String?,
    val afterHookCallbackClassName: String?,
    val methodUnhookerClassName: String?,
)

private data class RewriteStat(
    val entryName: String,
    val replacementCount: Int,
)

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
        Log.e(TAG, "PROBE-0325-ALT-REWRITE-901")
        val loaded = ensureLoaded(param) ?: run {
            Log.e(TAG, "ensureLoaded returned null")
            return
        }
        runCatching {
            val pluginPackageLoadedParam = loaded.onPackageLoaded?.parameterTypes?.singleOrNull()
                ?: error("onPackageLoaded parameter type unavailable")
            val pluginParam = PluginParamProxyFactory.create(pluginPackageLoadedParam, param)
            Log.e(TAG, "about to invoke legacy onPackageLoaded with paramType=${pluginPackageLoadedParam.name}")
            val onPackageLoaded = loaded.onPackageLoaded ?: error("onPackageLoaded method missing")
            onPackageLoaded.invoke(loaded.entryInstance, pluginParam)
            Log.e(TAG, "legacy onPackageLoaded invoked")
        }.onFailure {
            Log.e(TAG, "dispatchPackageLoaded failed: ${throwableChain(it)}", it)
        }
    }

    private fun ensureLoaded(packageParam: PackageLoadedParam): LoadedPlugin? {
        loadedRef.get()?.let { return it }
        return runCatching {
            val ctx = currentApplicationContextOrNull() ?: run {
                Log.e(TAG, "currentApplication still null, skip this round")
                return null
            }
            Log.e("API101BridgeV2", "currentApplicationContext=" + ctx.packageName)

            val outerApk = PluginStorage.materializeBundledPlugin(ctx, HOST_PACKAGE)
                ?: error("materialized outer apk null")
            Log.e(TAG, "outerApk=" + outerApk.absolutePath)

            inspectDexFileClasses(outerApk, "outer")

            val candidate = resolveCandidate(outerApk, ctx)
            Log.e(TAG, "selected candidate label=" + candidate.label)
            Log.e(TAG, "selected candidate apk=" + candidate.apk.absolutePath)
            Log.e(TAG, "selected candidate entry=" + candidate.entry)

            inspectDexFileClasses(candidate.apk, candidate.label)
            probeDexLoadClass(candidate.apk, candidate.entry, candidate.label)

            val runtimeApiNames = detectRuntimeApiNames(packageParam)
            Log.e(TAG, "runtime api module=${runtimeApiNames.moduleClassName}")
            Log.e(TAG, "runtime api iface=${runtimeApiNames.interfaceClassName}")
            Log.e(TAG, "runtime api moduleParam=${runtimeApiNames.moduleLoadedParamClassName}")
            Log.e(TAG, "runtime api packageParam=${runtimeApiNames.packageLoadedParamClassName}")

            val rewrittenApk = rewritePluginApk(candidate.apk, ctx, candidate.label, runtimeApiNames)
            Log.e(TAG, "rewritten candidate apk=${rewrittenApk.absolutePath}")
            inspectDexFileClasses(rewrittenApk, candidate.label + "-rewritten")
            probeDexLoadClass(rewrittenApk, candidate.entry, candidate.label + "-rewritten")

            val loaderResult = loadEntryClassWithStrategies(rewrittenApk, candidate.entry, ctx)
            Log.e(TAG, "entry load strategy=" + loaderResult.strategy)
            Log.e(TAG, "entryClassLoader=" + loaderResult.entryClass.classLoader)
            Log.e(TAG, "entrySuper=" + loaderResult.entryClass.superclass?.name)
            Log.e(TAG, "entryConstructors=" + loaderResult.entryClass.constructors.joinToString())

            val ctor = loaderResult.entryClass.constructors.firstOrNull { ctor ->
                val params = ctor.parameterTypes
                params.size == 2 &&
                    params[0].simpleName == "XposedInterface" &&
                    params[1].simpleName == "ModuleLoadedParam"
            } ?: error("no 2-arg XposedInterface/ModuleLoadedParam constructor")

            val pluginInterface = ctor.parameterTypes[0]
            val pluginModuleLoadedParam = ctor.parameterTypes[1]
            Log.e(TAG, "ctor param0=" + pluginInterface.name)
            Log.e(TAG, "ctor param1=" + pluginModuleLoadedParam.name)

            val interfaceProxy = Api100InterfaceProxy.create(pluginInterface, hostModule)
            val moduleLoadedParamProxy = PluginParamProxyFactory.create(
                pluginModuleLoadedParam,
                hostModuleLoadedParam,
            )

            val entryInstance = ctor.newInstance(interfaceProxy, moduleLoadedParamProxy)
            val onPackageLoaded = loaderResult.entryClass.methods.firstOrNull {
                it.name == "onPackageLoaded" && it.parameterTypes.size == 1
            }
            Log.e(TAG, "onPackageLoaded method=" + onPackageLoaded)

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

    private fun detectRuntimeApiNames(packageParam: PackageLoadedParam): RuntimeApiNames {
        val moduleClass = XposedModule::class.java
        val xposedInterfaceClass = resolveRuntimeXposedInterfaceClass(moduleClass)
        val moduleInterfaceClass = resolveRuntimeModuleInterfaceClass(moduleClass, xposedInterfaceClass)
        val moduleLoadedParamClass = resolveRuntimeModuleLoadedParamClass(moduleInterfaceClass)
        val packageLoadedParamClass = resolveRuntimePackageLoadedParamClass(packageParam, moduleInterfaceClass)
        val beforeHookCallbackClass = xposedInterfaceClass.declaredClasses.firstOrNull { it.simpleName == "BeforeHookCallback" }
        val afterHookCallbackClass = xposedInterfaceClass.declaredClasses.firstOrNull { it.simpleName == "AfterHookCallback" }
        val methodUnhookerClass = xposedInterfaceClass.declaredClasses.firstOrNull { it.simpleName == "MethodUnhooker" }
        return RuntimeApiNames(
            moduleClassName = moduleClass.name,
            interfaceClassName = xposedInterfaceClass.name,
            moduleInterfaceClassName = moduleInterfaceClass.name,
            moduleLoadedParamClassName = moduleLoadedParamClass.name,
            packageLoadedParamClassName = packageLoadedParamClass.name,
            beforeHookCallbackClassName = beforeHookCallbackClass?.name,
            afterHookCallbackClassName = afterHookCallbackClass?.name,
            methodUnhookerClassName = methodUnhookerClass?.name,
        )
    }

    private fun resolveRuntimeXposedInterfaceClass(moduleClass: Class<*>): Class<*> {
        moduleClass.constructors.forEach { ctor ->
            ctor.parameterTypes.firstOrNull { it.simpleName == "XposedInterface" }?.let { return it }
        }
        moduleClass.declaredFields.firstOrNull { it.type.simpleName == "XposedInterface" }?.let { return it.type }
        moduleClass.methods.firstOrNull { it.returnType.simpleName == "XposedInterface" }?.let { return it.returnType }
        error("cannot resolve runtime XposedInterface class")
    }

    private fun resolveRuntimeModuleInterfaceClass(moduleClass: Class<*>, xposedInterfaceClass: Class<*>): Class<*> {
        moduleClass.interfaces.firstOrNull { it.simpleName == "XposedModuleInterface" }?.let { return it }
        xposedInterfaceClass.declaredClasses.firstOrNull { it.simpleName == "MethodUnhooker" } // touch nested classes for logging side effects
        error("cannot resolve runtime XposedModuleInterface class")
    }

    private fun resolveRuntimeModuleLoadedParamClass(moduleInterfaceClass: Class<*>): Class<*> {
        moduleInterfaceClass.declaredClasses.firstOrNull { it.simpleName == "ModuleLoadedParam" }?.let { return it }
        error("cannot resolve runtime ModuleLoadedParam class")
    }

    private fun resolveRuntimePackageLoadedParamClass(packageParam: Any, moduleInterfaceClass: Class<*>): Class<*> {
        moduleInterfaceClass.declaredClasses.firstOrNull { it.simpleName == "PackageLoadedParam" }?.let { return it }
        packageParam.javaClass.interfaces.firstOrNull { it.simpleName == "PackageLoadedParam" }?.let { return it }
        packageParam.javaClass.interfaces.firstOrNull { it.simpleName == "PackageReadyParam" }?.let { return it }
        moduleInterfaceClass.declaredClasses.firstOrNull { it.simpleName == "PackageReadyParam" }?.let { return it }
        error("cannot resolve runtime PackageLoadedParam class")
    }

    private fun rewritePluginApk(
        pluginApk: File,
        ctx: Context,
        label: String,
        runtimeApiNames: RuntimeApiNames,
    ): File {
        val rewriteDir = File(ctx.cacheDir, "bridge_rewritten_apk").apply { mkdirs() }
        val outFile = File(rewriteDir, pluginApk.nameWithoutExtension + "-$label-rewritten.apk")
        val rewritePairs = buildRewritePairs(runtimeApiNames)
        Log.e(TAG, "rewrite pair count=" + rewritePairs.size)
        rewritePairs.forEach { (oldValue, newValue) ->
            Log.e(TAG, "rewrite pair: $oldValue -> $newValue")
        }

        val stats = mutableListOf<RewriteStat>()
        ZipFile(pluginApk).use { zip ->
            ZipOutputStream(outFile.outputStream().buffered()).use { zos ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val newEntry = ZipEntry(entry.name).apply {
                        time = entry.time
                        comment = entry.comment
                    }
                    zos.putNextEntry(newEntry)
                    val raw = zip.getInputStream(entry).use { it.readBytes() }
                    val output = if (!entry.isDirectory && entry.name.matches(Regex("classes(\\d*)\\.dex"))) {
                        val (patched, count) = patchDexBytes(raw, rewritePairs)
                        stats += RewriteStat(entry.name, count)
                        patched
                    } else {
                        raw
                    }
                    zos.write(output)
                    zos.closeEntry()
                }
            }
        }
        stats.forEach { stat ->
            Log.e(TAG, "rewrote ${stat.entryName}, replacements=${stat.replacementCount}")
        }
        return outFile
    }

    private fun buildRewritePairs(runtimeApiNames: RuntimeApiNames): List<Pair<String, String>> {
        val dotted = linkedMapOf(
            "io.github.libxposed.api.XposedModule" to runtimeApiNames.moduleClassName,
            ReflectionNames.XPOSED_INTERFACE to runtimeApiNames.interfaceClassName,
            ReflectionNames.XPOSED_MODULE_INTERFACE to runtimeApiNames.moduleInterfaceClassName,
            ReflectionNames.MODULE_LOADED_PARAM to runtimeApiNames.moduleLoadedParamClassName,
            ReflectionNames.PACKAGE_LOADED_PARAM to runtimeApiNames.packageLoadedParamClassName,
        )
        runtimeApiNames.beforeHookCallbackClassName?.let {
            dotted[ReflectionNames.BEFORE_CALLBACK] = it
        }
        runtimeApiNames.afterHookCallbackClassName?.let {
            dotted[ReflectionNames.AFTER_CALLBACK] = it
        }
        runtimeApiNames.methodUnhookerClassName?.let {
            dotted[ReflectionNames.METHOD_UNHOOKER] = it
        }

        val pairs = mutableListOf<Pair<String, String>>()
        dotted.forEach { (oldDot, newDot) ->
            if (oldDot == newDot) return@forEach
            val oldSlash = oldDot.replace('.', '/')
            val newSlash = newDot.replace('.', '/')
            if (oldSlash.length != newSlash.length) {
                Log.e(TAG, "skip rewrite length mismatch: $oldSlash -> $newSlash")
                return@forEach
            }
            pairs += oldSlash to newSlash
            val oldDesc = "L$oldSlash;"
            val newDesc = "L$newSlash;"
            if (oldDesc.length == newDesc.length) {
                pairs += oldDesc to newDesc
            }
            if (oldDot.length == newDot.length) {
                pairs += oldDot to newDot
            }
        }
        return pairs.distinct()
    }

    private fun patchDexBytes(input: ByteArray, rewritePairs: List<Pair<String, String>>): Pair<ByteArray, Int> {
        val bytes = input.copyOf()
        var total = 0
        rewritePairs.forEach { (oldValue, newValue) ->
            total += replaceAll(bytes, oldValue.toByteArray(Charsets.UTF_8), newValue.toByteArray(Charsets.UTF_8))
        }
        return bytes to total
    }

    private fun replaceAll(bytes: ByteArray, oldValue: ByteArray, newValue: ByteArray): Int {
        if (oldValue.isEmpty() || oldValue.size != newValue.size) return 0
        var count = 0
        var i = 0
        while (i <= bytes.size - oldValue.size) {
            var match = true
            var j = 0
            while (j < oldValue.size) {
                if (bytes[i + j] != oldValue[j]) {
                    match = false
                    break
                }
                j++
            }
            if (match) {
                newValue.copyInto(bytes, destinationOffset = i)
                count++
                i += oldValue.size
            } else {
                i++
            }
        }
        return count
    }

    private fun loadEntryClassWithStrategies(
        pluginApk: File,
        entryClassName: String,
        ctx: Context,
    ): LoaderResult {
        val parent = hostModule.javaClass.classLoader
        Log.e(TAG, "load parent=$parent")

        runCatching {
            Log.e(TAG, "trying PathClassLoader")
            val loader = PathClassLoader(pluginApk.absolutePath, parent)
            val entryClass = loader.loadClass(entryClassName)
            return LoaderResult(loader, entryClass, "PathClassLoader")
        }.onFailure {
            Log.e(TAG, "PathClassLoader failed: ${throwableChain(it)}", it)
        }

        runCatching {
            Log.e(TAG, "trying InMemoryDexClassLoader")
            val buffers = readAllDexBuffers(pluginApk)
            Log.e(TAG, "creating InMemoryDexClassLoader, dexCount=" + buffers.size)
            val loader = InMemoryDexClassLoader(buffers.toTypedArray(), parent)
            val entryClass = loader.loadClass(entryClassName)
            return LoaderResult(loader, entryClass, "InMemoryDexClassLoader")
        }.onFailure {
            Log.e(TAG, "InMemoryDexClassLoader failed: ${throwableChain(it)}", it)
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
            Log.e(TAG, "DexClassLoader failed: ${throwableChain(it)}", it)
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

    private fun inspectDexFileClasses(apk: File, label: String) {
        runCatching {
            val dexFile = DexFile(apk.absolutePath)
            val e = dexFile.entries()
            val all = mutableListOf<String>()
            while (e.hasMoreElements()) {
                all += e.nextElement()
            }
            dexFile.close()

            Log.e(TAG, "dexfile[$label] class count=" + all.size)
            Log.e(TAG, "dexfile[$label] ModuleMain class exists=" + all.contains("com.ss.android.ugc.awemes.ModuleMain"))
            val awemes = all.filter { it.startsWith("com.ss.android.ugc.awemes") }.sorted().take(80)
            Log.e(TAG, "dexfile[$label] awemes sample=" + awemes.joinToString())
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
            Log.e(TAG, "dexfile[$label] reflective loadClass result=" + result)
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
