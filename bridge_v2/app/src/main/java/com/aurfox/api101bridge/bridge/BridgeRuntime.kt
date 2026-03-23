package com.aurfox.api101bridge.bridge

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import dalvik.system.DexClassLoader
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
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
    private const val TAG = "API101BridgeV2"
    private const val HOST_PACKAGE = "com.aurfox.api101bridge"

    private lateinit var hostModule: XposedModule
    private lateinit var hostModuleLoadedParam: ModuleLoadedParam
    private val loadedPluginRef = AtomicReference<LoadedPlugin?>()

    fun attachHost(module: XposedModule, param: ModuleLoadedParam) {
        hostModule = module
        hostModuleLoadedParam = param
    }

    fun dispatchPackageLoaded(param: PackageLoadedParam) {
        Log.e(TAG, "dispatchPackageLoaded start PROBE-0323-A")

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

            val pluginApk = PluginStorage.materializeBundledPlugin(
                currentContext = currentContext,
                hostPackage = HOST_PACKAGE,
            ) ?: return null

            val info = PluginApkInspector.inspect(pluginApk)

            val optimizedDir = File(currentContext.cacheDir, "bridge_dex").apply { mkdirs() }

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
            val moduleLoadedParamProxy = PluginParamProxyFactory.create(
                pluginModuleLoadedParam,
                hostModuleLoadedParam,
            )

            val entryInstance = instantiateEntry(
                entryClass = entryClass,
                pluginInterface = pluginInterface,
                pluginModuleLoadedParam = pluginModuleLoadedParam,
                interfaceProxy = interfaceProxy,
                moduleLoadedParamProxy = moduleLoadedParamProxy,
            )

            val onPackageLoaded = entryClass.methods.firstOrNull {
                it.name == "onPackageLoaded" && it.parameterTypes.size == 1
            }

            LoadedPlugin(
                info = info,
                classLoader = classLoader,
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
