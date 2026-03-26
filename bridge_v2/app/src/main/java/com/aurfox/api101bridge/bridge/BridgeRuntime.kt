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
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
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
        Log.e(TAG, "PROBE-0325-ALT-REWRITE-905")
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
            Log.e("API101BridgeV2", "currentApplicationContext=${ctx.packageName}")

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

            val rewrittenApk = rewritePluginApkExpanded(candidate.apk, ctx, candidate.label)
            Log.e(TAG, "rewritten candidate apk=${rewrittenApk.absolutePath}")
            inspectDexFileClasses(rewrittenApk, candidate.label + "-rewritten")
            probeDexLoadClass(rewrittenApk, candidate.entry, candidate.label + "-rewritten")

            val loaderResult = loadEntryClassWithStrategies(rewrittenApk, candidate.entry, ctx)
            Log.e(TAG, "entry load strategy=${loaderResult.strategy}")
            Log.e(TAG, "entryClassLoader=${loaderResult.entryClass.classLoader}")
            Log.e(TAG, "entrySuper=${loaderResult.entryClass.superclass?.name}")
            Log.e(TAG, "entryConstructorCount=${loaderResult.entryClass.constructors.size}")

            dumpClassAbi("runtimeXposedModule", XposedModule::class.java)
            dumpClassAbi("entrySuperClass", loaderResult.entryClass.superclass)
            dumpClassAbi("entryClass", loaderResult.entryClass)

            val runtimeInterfaceClass = XposedInterface::class.java
            val runtimeModuleLoadedParamClass = hostModuleLoadedParam.javaClass.interfaces.firstOrNull {
                it.name.endsWith("ModuleLoadedParam") ||
                    ModuleLoadedParam::class.java.isAssignableFrom(it) ||
                    it.isAssignableFrom(ModuleLoadedParam::class.java)
            } ?: ModuleLoadedParam::class.java
            Log.e(TAG, "runtimeInterfaceClass=${runtimeInterfaceClass.name}")
            Log.e(TAG, "runtimeModuleLoadedParamClass=${runtimeModuleLoadedParamClass.name}")

            loaderResult.entryClass.constructors.forEachIndexed { index, ctor ->
                val names = runCatching { ctor.parameterTypes.joinToString { it.name } }
                    .getOrElse { "<unavailable:${throwableChain(it)}>" }
                Log.e(TAG, "ctor[$index] paramTypes=$names")
            }

            val ctor = loaderResult.entryClass.constructors.firstOrNull { c ->
                val params = runCatching { c.parameterTypes.toList() }.getOrNull() ?: return@firstOrNull false
                if (params.size != 2) return@firstOrNull false
                matchesRuntimeType(params[0], runtimeInterfaceClass, "XposedInterface") &&
                    matchesRuntimeType(params[1], runtimeModuleLoadedParamClass, "ModuleLoadedParam")
            } ?: loaderResult.entryClass.constructors.firstOrNull { c ->
                runCatching { c.parameterTypes.size == 2 }.getOrDefault(false)
            } ?: error("no usable 2-arg constructor")

            val pluginParams = runCatching { ctor.parameterTypes }.getOrElse {
                error("resolved constructor parameterTypes unavailable: ${throwableChain(it)}")
            }
            val pluginInterface = pluginParams[0]
            val pluginModuleLoadedParam = pluginParams[1]
            Log.e(TAG, "selected ctor param0=${pluginInterface.name}")
            Log.e(TAG, "selected ctor param1=${pluginModuleLoadedParam.name}")

            val interfaceProxy = Api100InterfaceProxy.create(pluginInterface, hostModule)
            val moduleLoadedParamProxy = PluginParamProxyFactory.create(
                pluginModuleLoadedParam,
                hostModuleLoadedParam,
            )

            val entryInstance = instantiateLegacyEntry(
                ctor = ctor,
                interfaceProxy = interfaceProxy,
                moduleLoadedParamProxy = moduleLoadedParamProxy,
            )

            val onPackageLoaded = loaderResult.entryClass.methods.firstOrNull {
                it.name == "onPackageLoaded" && it.parameterTypes.size == 1
            }
            Log.e(TAG, "onPackageLoaded method=$onPackageLoaded")

            LoadedPlugin(loaderResult.classLoader, entryInstance, onPackageLoaded).also {
                loadedRef.set(it)
            }
        }.getOrElse {
            Log.e(TAG, "ensureLoaded failed: ${throwableChain(it)}", it)
            null
        }
    }

    private fun instantiateLegacyEntry(
        ctor: Constructor<*>,
        interfaceProxy: Any,
        moduleLoadedParamProxy: Any,
    ): Any {
        return try {
            ctor.isAccessible = true
            ctor.newInstance(interfaceProxy, moduleLoadedParamProxy)
        } catch (t: Throwable) {
            val root = unwrapThrowable(t)
            val chain = throwableChain(t)
            val hitSuperCtorMismatch =
                root is NoSuchMethodError ||
                    chain.contains("No direct method <init>(") ||
                    chain.contains("NoSuchMethodError")

            if (!hitSuperCtorMismatch) {
                throw t
            }

            Log.e(TAG, "ctor invoke hit super ctor mismatch, trying allocateInstance fallback: $chain", t)

            val entryClass = ctor.declaringClass
            val instance = allocateInstanceWithoutConstructor(entryClass)
            primeRuntimeFields(instance, interfaceProxy, moduleLoadedParamProxy)
            instance
        }
    }

    private fun allocateInstanceWithoutConstructor(clazz: Class<*>): Any {
        val attempts = listOf(
            "sun.misc.Unsafe" to "theUnsafe",
            "jdk.internal.misc.Unsafe" to "theUnsafe",
        )

        attempts.forEach { (className, fieldName) ->
            runCatching {
                val unsafeClass = Class.forName(className)
                val unsafeField = unsafeClass.getDeclaredField(fieldName)
                unsafeField.isAccessible = true
                val unsafe = unsafeField.get(null)
                val allocate = unsafeClass.getDeclaredMethod("allocateInstance", Class::class.java)
                allocate.isAccessible = true
                val instance = allocate.invoke(unsafe, clazz)
                Log.e(TAG, "allocateInstance success via $className")
                return instance
            }.onFailure {
                Log.e(TAG, "allocateInstance failed via $className: ${throwableChain(it)}")
            }
        }

        error("allocateInstance failed for ${clazz.name}")
    }

    private fun primeRuntimeFields(
        instance: Any,
        interfaceProxy: Any,
        moduleLoadedParamProxy: Any,
    ) {
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
        var hit = 0

        fun shouldDescend(value: Any): Boolean {
            if (value === interfaceProxy || value === moduleLoadedParamProxy) return false
            val clazz = value.javaClass
            val n = clazz.name
            if (clazz.isPrimitive) return false
            if (clazz.isEnum) return false
            if (clazz.isArray) return false
            if (n.startsWith("java.")) return false
            if (n.startsWith("javax.")) return false
            if (n.startsWith("kotlin.")) return false
            if (n.startsWith("android.")) return false
            return true
        }

        fun seedObject(obj: Any?, depth: Int) {
            if (obj == null) return
            if (depth > 3) return
            if (!seen.add(obj)) return

            walkClassHierarchy(obj.javaClass).forEach { owner ->
                owner.declaredFields.forEach { field ->
                    if (Modifier.isStatic(field.modifiers)) return@forEach

                    runCatching {
                        field.isAccessible = true
                        val curValue = field.get(obj)

                        if (field.type.isInstance(interfaceProxy)) {
                            field.set(obj, interfaceProxy)
                            hit++
                            Log.e(TAG, "primed field ${owner.name}#${field.name} with interfaceProxy")
                        } else if (field.type.isInstance(moduleLoadedParamProxy)) {
                            field.set(obj, moduleLoadedParamProxy)
                            hit++
                            Log.e(TAG, "primed field ${owner.name}#${field.name} with moduleLoadedParamProxy")
                        } else if (
                            field.name.contains("param", ignoreCase = true) &&
                            field.type.isAssignableFrom(moduleLoadedParamProxy.javaClass)
                        ) {
                            field.set(obj, moduleLoadedParamProxy)
                            hit++
                            Log.e(TAG, "primed field ${owner.name}#${field.name} with moduleLoadedParamProxy by name")
                        } else if (curValue != null && shouldDescend(curValue)) {
                            Log.e(
                                TAG,
                                "descending into ${owner.name}#${field.name}, " +
                                    "valueClass=${curValue.javaClass.name}, depth=$depth"
                            )
                            seedObject(curValue, depth + 1)
                        }
                    }.onFailure {
                        Log.e(TAG, "prime field failed ${owner.name}#${field.name}: ${throwableChain(it)}", it)
                    }
                }
            }
        }

        seedObject(instance, 0)
        Log.e(TAG, "primeRuntimeFields hitCount=$hit")
    }

    private fun walkClassHierarchy(start: Class<*>): List<Class<*>> {
        val out = mutableListOf<Class<*>>()
        var cur: Class<*>? = start
        while (cur != null && cur != Any::class.java) {
            out += cur
            cur = cur.superclass
        }
        return out
    }

    private fun dumpClassAbi(label: String, clazz: Class<*>?) {
        if (clazz == null) {
            Log.e(TAG, "$label = <null>")
            return
        }
        Log.e(TAG, "$label name=${clazz.name}")
        clazz.declaredConstructors.forEachIndexed { index, ctor ->
            val params = runCatching { ctor.parameterTypes.joinToString { it.name } }
                .getOrElse { "<unavailable:${throwableChain(it)}>" }
            Log.e(TAG, "$label ctor[$index] modifiers=${ctor.modifiers} params=$params")
        }
        clazz.declaredFields.forEachIndexed { index, field ->
            Log.e(
                TAG,
                "$label field[$index] modifiers=${field.modifiers} name=${field.name} type=${field.type.name}",
            )
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

    private fun rewritePluginApkExpanded(
        pluginApk: File,
        ctx: Context,
        label: String,
    ): File {
        val rewriteDir = File(ctx.cacheDir, "bridge_rewritten_apk").apply { mkdirs() }
        val outFile = File(rewriteDir, pluginApk.nameWithoutExtension + "-$label-rewritten.apk")
        val runtimeModuleClassName = XposedModule::class.java.name
        val rewritePairs = buildMinimalRewritePairs(runtimeModuleClassName)
        Log.e(TAG, "runtime module class=$runtimeModuleClassName")
        Log.e(TAG, "rewrite pair count=${rewritePairs.size}")
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
                    val output = if (!entry.isDirectory && entry.name.matches(Regex("classes(\d*)\.dex"))) {
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

    private fun buildMinimalRewritePairs(runtimeModuleClassName: String): List<Pair<String, String>> {
        val runtimeMappings = linkedMapOf(
            "io.github.libxposed.api.XposedModule" to runtimeModuleClassName,
            "io.github.libxposed.api.XposedInterface" to XposedInterface::class.java.name,
            "io.github.libxposed.api.XposedModuleInterface" to XposedModuleInterface::class.java.name,
            "io.github.libxposed.api.XposedModuleInterface\$ModuleLoadedParam" to ModuleLoadedParam::class.java.name,
            "io.github.libxposed.api.XposedModuleInterface\$PackageLoadedParam" to PackageLoadedParam::class.java.name,
        )

        val pairs = mutableListOf<Pair<String, String>>()
        runtimeMappings.forEach { (oldDot, newDot) ->
            if (oldDot == newDot) {
                Log.e(TAG, "rewrite skipped canonical mapping: $oldDot")
                return@forEach
            }

            addRewritePair(pairs, oldDot.replace('.', '/'), newDot.replace('.', '/'))
            addRewritePair(pairs, "L${oldDot.replace('.', '/')};", "L${newDot.replace('.', '/')};")
            addRewritePair(pairs, oldDot, newDot)
        }
        return pairs.distinct()
    }

    private fun addRewritePair(
        out: MutableList<Pair<String, String>>,
        oldValue: String,
        newValue: String,
    ) {
        if (oldValue == newValue) return
        if (oldValue.length != newValue.length) {
            Log.e(TAG, "rewrite skipped length mismatch: $oldValue -> $newValue")
            return
        }
        out += oldValue to newValue
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
            Log.e(TAG, "creating InMemoryDexClassLoader, dexCount=${buffers.size}")
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
                .filter { !it.isDirectory && it.name.matches(Regex("classes(\d*)\.dex")) }
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

    private fun matchesRuntimeType(candidate: Class<*>, runtime: Class<*>, fallbackSimpleName: String): Boolean {
        return candidate == runtime ||
            candidate.name == runtime.name ||
            candidate.simpleName == fallbackSimpleName ||
            candidate.name.endsWith(".${fallbackSimpleName}") ||
            candidate.name.endsWith("$" + fallbackSimpleName) ||
            candidate.isAssignableFrom(runtime) ||
            runtime.isAssignableFrom(candidate)
    }

    private fun unwrapThrowable(t: Throwable): Throwable {
        var cur = t
        while (true) {
            val next = when (cur) {
                is InvocationTargetException -> cur.targetException ?: cur.cause
                else -> cur.cause
            } ?: return cur
            cur = next
        }
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
