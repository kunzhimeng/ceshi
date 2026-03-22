package com.aurfox.api101bridge.bridge

import android.annotation.SuppressLint
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import dalvik.system.DexClassLoader
import java.io.File
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicReference

private data class LoadedPlugin(
    val info: TargetPluginInfo,
    val classLoader: DexClassLoader,
    val entryInstance: Any,
    val onPackageLoaded: Method?,
)

object BridgeRuntime {
    private lateinit var hostModule: XposedModule
    private lateinit var hostModuleLoadedParam: ModuleLoadedParam
    private val loadedPluginRef = AtomicReference<LoadedPlugin?>()

    fun attachHost(module: XposedModule, param: ModuleLoadedParam) {
        hostModule = module
        hostModuleLoadedParam = param
    }

    fun dispatchPackageLoaded(param: PackageLoadedParam) {
        val plugin = ensurePluginLoaded() ?: return
        val pluginPackageLoadedParam = plugin.classLoader.loadClass(ReflectionNames.PACKAGE_LOADED_PARAM)
        val pluginParam = PluginParamProxyFactory.create(pluginPackageLoadedParam, param)
        plugin.onPackageLoaded?.invoke(plugin.entryInstance, pluginParam)
    }

    @SuppressLint("UnsafeDynamicallyLoadedCode")
    private fun ensurePluginLoaded(): LoadedPlugin? {
        loadedPluginRef.get()?.let { return it }

        val pluginApk = PluginStorage.getDefaultPluginFile(resolveHostDataDir())
        if (!pluginApk.isFile) {
            hostModule.log("BridgeRuntime: target plugin not found at ${pluginApk.absolutePath}")
            return null
        }
        val info = PluginApkInspector.inspect(pluginApk)
        if (info.minApiVersion != 100 || info.targetApiVersion != 100) {
            hostModule.log("BridgeRuntime: target is not an API100 module: ${info.apk.name}")
        }

        val optimizedDir = File(resolveHostCacheDir(), "bridge_dex").apply { mkdirs() }
        val classLoader = DexClassLoader(
            info.apk.absolutePath,
            optimizedDir.absolutePath,
            info.apk.parentFile?.absolutePath,
            hostModule.javaClass.classLoader,
        )

        val entryClass = classLoader.loadClass(info.entryClass)
        val pluginInterface = classLoader.loadClass(ReflectionNames.XPOSED_INTERFACE)
        val pluginModuleLoadedParam = classLoader.loadClass(ReflectionNames.MODULE_LOADED_PARAM)
        val interfaceProxy = Api100InterfaceProxy.create(pluginInterface, hostModule)
        val moduleLoadedParamProxy = PluginParamProxyFactory.create(pluginModuleLoadedParam, hostModuleLoadedParam)

        val entryInstance = instantiateEntry(
            entryClass,
            pluginInterface,
            pluginModuleLoadedParam,
            interfaceProxy,
            moduleLoadedParamProxy,
        )
        val onPackageLoaded = entryClass.methods.firstOrNull {
            it.name == "onPackageLoaded" && it.parameterTypes.size == 1
        }

        return LoadedPlugin(
            info = info,
            classLoader = classLoader,
            entryInstance = entryInstance,
            onPackageLoaded = onPackageLoaded,
        ).also {
            loadedPluginRef.set(it)
            hostModule.log("BridgeRuntime: loaded API100 plugin entry ${info.entryClass}")
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
        if (ctor != null) return ctor.newInstance(interfaceProxy, moduleLoadedParamProxy)

        val noArg = entryClass.constructors.firstOrNull { it.parameterTypes.isEmpty() }
            ?: error("No supported constructor found for ${entryClass.name}")
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

    private fun resolveHostDataDir(): String {
        return hostModule.applicationInfo.dataDir
            ?: error("Host applicationInfo.dataDir is null")
    }

    private fun resolveHostCacheDir(): File {
        val dataDir = resolveHostDataDir()
        return File(dataDir, "cache")
    }
}
