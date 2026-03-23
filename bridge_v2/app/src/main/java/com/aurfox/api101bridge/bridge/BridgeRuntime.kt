package com.aurfox.api101bridge.bridge

import android.annotation.SuppressLint
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
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipFile

private data class LoadedPlugin(
    val info: TargetPluginInfo,
    val classLoader: ClassLoader,
    val entryInstance: Any,
    val onPackageLoaded: Method?,
)

private data class LoaderResult(
    val classLoader: ClassLoader,
    val entryClass: Class<*>,
    val strategy: String,
)

private data class PluginCandidate(
    val sourceApk: File,
    val entryClassName: String,
    val label: String,
)

object BridgeRuntime {
    private const val TAG = "API101BridgeH333"
    private const val HOST_PACKAGE = "com.aurfox.api101bridge"

    private lateinit var hostModule: XposedModule
    private lateinit var hostModuleLoadedParam: ModuleLoadedParam
    private val loadedPluginRef = AtomicReference<LoadedPlugin?>()

    fun attachHost(module: XposedModule, param: ModuleLoadedParam) {
        hostModule = module
        hostModuleLoadedParam = param
    }

    fun dispatchPackageLoaded(param: PackageLoadedParam) {
        Log.e(TAG, "dispatchPackageLoaded start PROBE-0323-H-INNER-APK-333")

        val plugin = ensurePluginLoaded() ?: run {
            Log.e(TAG, "ensurePluginLoaded returned null")
            return
        }

        runCatching {
            val pluginPackageLoadedParam = plugin.classLoader.loadClass(ReflectionNames.PACKAGE_LOADED_PARAM)
            val pluginParam = PluginParamProxyFactory.create(pluginPackageLoadedParam, param)

            Log.e(TAG, "about to invoke legacy onPackageLoaded")
            plugin.onPackageLoaded?.invoke(plugin.entryInstance, pluginParam)
            Log.e(TAG, "legacy onPackageLoaded invoked")
        }.onFailure { e ->
            Log.e(TAG, "dispatchPackageLoaded failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    @SuppressLint("UnsafeDynamicallyLoadedCode")
    private fun ensurePluginLoaded(): LoadedPlugin? {
        loadedPluginRef.get()?.let { return it }

        return runCatching {
            val currentContext = currentApplicationContext()

            val outerApk = PluginStorage.materializeBundledPlugin(
                currentContext = currentContext,
                hostPackage = HOST_PACKAGE,
            ) ?: return null

            inspectMaterializedPlugin(outerApk, "outer")
            inspectDexFileClasses(outerApk, "outer")
            inspectHiddenCodeContainers(outerApk)

            val candidate = resolveActualPluginCandidate(outerApk, currentContext)
            Log.e(TAG, "selected candidate label=" + candidate.label)
            Log.e(TAG, "selected candidate path=" + candidate.sourceApk.absolutePath)
            Log.e(TAG, "selected candidate entry=" + candidate.entryClassName)

            inspectMaterializedPlugin(candidate.sourceApk, candidate.label)
            inspectDexFileClasses(candidate.sourceApk, candidate.label)

            val actualInfo = PluginApkInspector.inspect(candidate.sourceApk)

            val loaderResult = loadEntryClassWithStrategies(
                pluginApk = candidate.sourceApk,
                entryClassName = candidate.entryClassName,
                currentContext = currentContext,
            )

            Log.e(TAG, "entry load strategy=" + loaderResult.strategy)

            val pluginInterface = loaderResult.classLoader.loadClass(ReflectionNames.XPOSED_INTERFACE)
            val pluginModuleLoadedParam = loaderResult.classLoader.loadClass(ReflectionNames.MODULE_LOADED_PARAM)

            val interfaceProxy = Api100InterfaceProxy.create(pluginInterface, hostModule)
            val moduleLoadedParamProxy = PluginParamProxyFactory.create(
                pluginModuleLoadedParam,
                hostModuleLoadedParam,
            )

            val entryInstance = instantiateEntry(
                entryClass = loaderResult.entryClass,
                pluginInterface = pluginInterface,
                pluginModuleLoadedParam = pluginModuleLoadedParam,
                interfaceProxy = interfaceProxy,
                moduleLoadedParamProxy = moduleLoadedParamProxy,
            )

            val onPackageLoaded = loaderResult.entryClass.methods.firstOrNull {
                it.name == "onPackageLoaded" && it.parameterTypes.size == 1
            }

            LoadedPlugin(
                info = actualInfo,
                classLoader = loaderResult.classLoader,
                entryInstance = entryInstance,
                onPackageLoaded = onPackageLoaded,
            ).also {
                loadedPluginRef.set(it)
            }
        }.getOrElse { e ->
            Log.e(TAG, "ensurePluginLoaded failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private fun resolveActualPluginCandidate(outerApk: File, currentContext: Context): PluginCandidate {
        val outerEntry = readJavaInit(outerApk)
        val outerHasClass = hasDexClass(outerApk, outerEntry)
        Log.e(TAG, "outer entry=" + outerEntry)
        Log.e(TAG, "outer entry class exists=" + outerHasClass)

        if (outerEntry != null && outerHasClass) {
            return PluginCandidate(outerApk, outerEntry, "outer")
        }

        val innerApks = extractInnerApks(outerApk, currentContext)
        Log.e(TAG, "inner apk count=" + innerApks.size)

        innerApks.forEach { inner ->
            val entry = readJavaInit(inner)
            val hasClass = hasDexClass(inner, entry)
            Log.e(TAG, "inner apk path=" + inner.absolutePath)
            Log.e(TAG, "inner entry=" + entry)
            Log.e(TAG, "inner entry class exists=" + hasClass)
            if (entry != null && hasClass) {
                return PluginCandidate(inner, entry, "inner")
            }
        }

        return PluginCandidate(
            sourceApk = outerApk,
            entryClassName = outerEntry ?: "com.ss.android.ugc.awemes.ModuleMain",
            label = "outer-fallback",
        )
    }

    private fun extractInnerApks(outerApk: File, currentContext: Context): List<File> {
        return runCatching {
            val outDir = File(currentContext.cacheDir, "bridge_inner_apk").apply { mkdirs() }
            val extracted = mutableListOf<File>()

            ZipFile(outerApk).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory) continue
                    val name = entry.name
                    if (name.startsWith("assets/") && name.endsWith(".apk")) {
                        val safeName = name.removePrefix("assets/").replace("/", "_")
                        val outFile = File(outDir, safeName)
                        zip.getInputStream(entry).use { input ->
                            outFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        Log.e(TAG, "extracted inner apk=" + outFile.absolutePath)
                        extracted += outFile
                    }
                }
            }

            extracted
        }.getOrElse { e ->
            Log.e(TAG, "extractInnerApks failed: ${e.javaClass.simpleName}: ${e.message}")
            emptyList()
        }
    }

    private fun readJavaInit(pluginApk: File): String? {
        return runCatching {
            ZipFile(pluginApk).use { zip ->
                val entry = zip.getEntry("META-INF/xposed/java_init.list") ?: return null
                zip.getInputStream(entry).bufferedReader().use { it.readText().trim().lineSequence().firstOrNull()?.trim() }
            }
        }.getOrElse {
            Log.e(TAG, "readJavaInit failed: ${it.javaClass.simpleName}: ${it.message}")
            null
        }
    }

    private fun hasDexClass(pluginApk: File, className: String?): Boolean {
        if (className.isNullOrBlank()) return false
        return runCatching {
            val dexFile = DexFile(pluginApk.absolutePath)
            val entries = dexFile.entries()
            var found = false
            while (entries.hasMoreElements()) {
                if (entries.nextElement() == className) {
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

    private fun loadEntryClassWithStrategies(
        pluginApk: File,
        entryClassName: String,
        currentContext: Context,
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
            val optimizedDir = File(currentContext.cacheDir, "bridge_dex").apply { mkdirs() }
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

        error("All loader strategies failed for $entryClassName")
    }

    private fun readAllDexBuffers(pluginApk: File): List<ByteBuffer> {
        return ZipFile(pluginApk).use { zip ->
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

    private fun inspectMaterializedPlugin(pluginApk: File, label: String) {
        runCatching {
            ZipFile(pluginApk).use { zip ->
                val javaInitEntry = zip.getEntry("META-INF/xposed/java_init.list")
                Log.e(TAG, "inspect[$label] java_init exists=" + (javaInitEntry != null))

                if (javaInitEntry != null) {
                    val javaInit = zip.getInputStream(javaInitEntry).bufferedReader().use { it.readText() }
                    Log.e(TAG, "inspect[$label] java_init content=" + javaInit.trim())
                }

                val dexEntries = zip.entries().asSequence()
                    .filter { !it.isDirectory && it.name.matches(Regex("classes(\\d*)\\.dex")) }
                    .sortedBy { dexOrder(it.name) }
                    .toList()

                Log.e(TAG, "inspect[$label] dex entry count=" + dexEntries.size)
                Log.e(TAG, "inspect[$label] dex entries=" + dexEntries.joinToString { it.name })

                dexEntries.forEach { dexEntry ->
                    val dexBytes = zip.getInputStream(dexEntry).use { it.readBytes() }
                    val hit = dexBytes.toString(Charsets.ISO_8859_1)
                        .contains("com/ss/android/ugc/awemes/ModuleMain")
                    Log.e(TAG, "inspect[$label] ${dexEntry.name} ModuleMain string exists=" + hit)
                }
            }
        }.onFailure { e ->
            Log.e(TAG, "inspectMaterializedPlugin[$label] failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun inspectDexFileClasses(pluginApk: File, label: String) {
        runCatching {
            val dexFile = DexFile(pluginApk.absolutePath)
            val entries = dexFile.entries()
            val all = mutableListOf<String>()
            while (entries.hasMoreElements()) {
                all += entries.nextElement()
            }
            dexFile.close()

            Log.e(TAG, "dexfile[$label] class count=" + all.size)
            Log.e(TAG, "dexfile[$label] ModuleMain class exists=" + all.contains("com.ss.android.ugc.awemes.ModuleMain"))
            val awemes = all.filter { it.startsWith("com.ss.android.ugc.awemes") }.take(50)
            Log.e(TAG, "dexfile[$label] awemes sample=" + awemes.joinToString())
        }.onFailure { e ->
            Log.e(TAG, "inspectDexFileClasses[$label] failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun inspectHiddenCodeContainers(pluginApk: File) {
        runCatching {
            ZipFile(pluginApk).use { zip ->
                val assetDex = mutableListOf<String>()
                val assetApk = mutableListOf<String>()
                val assetJar = mutableListOf<String>()
                val nativeSo = mutableListOf<String>()
                val suspicious = mutableListOf<String>()

                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory) continue
                    val name = entry.name

                    when {
                        name.startsWith("assets/") && name.endsWith(".dex") -> assetDex += name
                        name.startsWith("assets/") && name.endsWith(".apk") -> assetApk += name
                        name.startsWith("assets/") && name.endsWith(".jar") -> assetJar += name
                        name.startsWith("lib/") && name.endsWith(".so") -> nativeSo += name
                    }

                    if (
                        name.contains("dex", ignoreCase = true) ||
                        name.contains("plugin", ignoreCase = true) ||
                        name.contains("module", ignoreCase = true) ||
                        name.contains("load", ignoreCase = true) ||
                        name.contains("hook", ignoreCase = true)
                    ) {
                        suspicious += name
                    }
                }

                Log.e(TAG, "hidden asset dex=" + assetDex.joinToString())
                Log.e(TAG, "hidden asset apk=" + assetApk.joinToString())
                Log.e(TAG, "hidden asset jar=" + assetJar.joinToString())
                Log.e(TAG, "hidden native so=" + nativeSo.joinToString())
                Log.e(TAG, "hidden suspicious entries=" + suspicious.take(100).joinToString())
            }
        }.onFailure { e ->
            Log.e(TAG, "inspectHiddenCodeContainers failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun instantiateEntry(
        entryClass: Class<*>,
        pluginInterface: Class<*>,
        pluginModuleLoadedParam: Class<*>,
        interfaceProxy: Any,
        moduleLoadedParamProxy: Any,
    ): Any {
        val ctor: Constructor<*>? = entryClass.constructors.firstOrNull {
            it.parameterTypes.size == 2 &&
                it.parameterTypes[0].name == pluginInterface.name &&
                it.parameterTypes[1].name == pluginModuleLoadedParam.name
        }

        if (ctor != null) {
            Log.e(TAG, "instantiating legacy entry with 2-arg constructor")
            return ctor.newInstance(interfaceProxy, moduleLoadedParamProxy)
        }

        val noArg = entryClass.constructors.firstOrNull { it.parameterTypes.isEmpty() }
            ?: error("No supported constructor found for ${entryClass.name}")

        Log.e(TAG, "instantiating legacy entry with no-arg constructor")

        val instance = noArg.newInstance()

        val attachFramework = entryClass.methods.firstOrNull {
            it.name == "attachFramework" && it.parameterTypes.size == 1
        }
        attachFramework?.invoke(instance, interfaceProxy)

        val onModuleLoaded = entryClass.methods.firstOrNull {
            it.name == "onModuleLoaded" && it.parameterTypes.size == 1
        }
        onModuleLoaded?.invoke(instance, moduleLoadedParamProxy)

        return instance
    }

    private fun currentApplicationContext(): Context {
        val activityThreadClass = Class.forName("android.app.ActivityThread")
        val currentApplicationMethod = activityThreadClass.getDeclaredMethod("currentApplication")
        val app = currentApplicationMethod.invoke(null) as? Context
        return app ?: error("ActivityThread.currentApplication() returned null")
    }
}
