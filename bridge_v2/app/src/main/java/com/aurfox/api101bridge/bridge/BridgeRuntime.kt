package com.aurfox.api101bridge.bridge

import android.content.Context
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.io.File
import java.util.zip.ZipFile

object BridgeRuntime {
    private const val TAG = "API101BridgeJ555"
    private const val HOST_PACKAGE = "com.aurfox.api101bridge"

    private lateinit var hostModule: XposedModule
    private lateinit var hostModuleLoadedParam: ModuleLoadedParam

    @JvmStatic
    fun getClassLoader(): ClassLoader? {
        return runCatching { hostModule.javaClass.classLoader }.getOrNull()
    }

    @JvmStatic
    fun attachHost(module: XposedModule, param: ModuleLoadedParam) {
        hostModule = module
        hostModuleLoadedParam = param
        Log.e(TAG, "attachHost called")
    }

    @JvmStatic
    fun dispatchPackageLoaded(param: PackageLoadedParam) {
        Log.e(TAG, "PROBE-0323-J-JAVAINIT-555")
        runCatching {
            val currentContext = currentApplicationContext()
            val pluginApk = PluginStorage.materializeBundledPlugin(
                currentContext = currentContext,
                hostPackage = HOST_PACKAGE,
            ) ?: error("materialized plugin is null")

            Log.e(TAG, "plugin path=" + pluginApk.absolutePath)
            Log.e(TAG, "plugin exists=" + pluginApk.exists())

            val entryName = readJavaInit(pluginApk) ?: error("java_init entry is null")
            Log.e(TAG, "entryName from java_init=" + entryName)

            // 先试宿主 app context classloader
            val hostLoader = currentContext.classLoader
            Log.e(TAG, "trying app context classloader=" + hostLoader.javaClass.name)
            tryLoad(hostLoader, entryName, "appContext")

            // 再试 Bridge 自己的 classloader
            val bridgeLoader = hostModule.javaClass.classLoader
            if (bridgeLoader != null) {
                Log.e(TAG, "trying bridge classloader=" + bridgeLoader.javaClass.name)
                tryLoad(bridgeLoader, entryName, "bridge")
            }

            // 如果 param 暴露了 classLoader 字段/方法，就反射试一下
            val reflected = reflectPackageParamClassLoader(param)
            if (reflected != null) {
                Log.e(TAG, "trying reflected param classloader=" + reflected.javaClass.name)
                tryLoad(reflected, entryName, "reflectedParam")
            } else {
                Log.e(TAG, "reflected param classloader=null")
            }
        }.onFailure {
            Log.e(TAG, "dispatchPackageLoaded failed: ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    private fun tryLoad(loader: ClassLoader, entryName: String, label: String) {
        try {
            val entry = loader.loadClass(entryName)
            Log.e(TAG, "[$label] entry loaded = " + entry.name)
        } catch (e: Throwable) {
            Log.e(TAG, "[$label] load failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun readJavaInit(pluginApk: File): String? {
        return runCatching {
            ZipFile(pluginApk).use { zip ->
                val entry = zip.getEntry("META-INF/xposed/java_init.list") ?: return null
                zip.getInputStream(entry).bufferedReader().use {
                    it.readText().trim().lineSequence().firstOrNull()?.trim()
                }
            }
        }.getOrElse {
            Log.e(TAG, "readJavaInit failed: ${it.javaClass.simpleName}: ${it.message}")
            null
        }
    }

    private fun reflectPackageParamClassLoader(param: PackageLoadedParam): ClassLoader? {
        return runCatching {
            val methods = param.javaClass.methods
            methods.firstOrNull {
                it.parameterCount == 0 &&
                    (it.name.equals("getClassLoader", true) || it.name.equals("classLoader", true))
            }?.invoke(param) as? ClassLoader
        }.getOrElse {
            Log.e(TAG, "reflectPackageParamClassLoader failed: ${it.javaClass.simpleName}: ${it.message}")
            null
        }
    }

    private fun currentApplicationContext(): Context {
        val activityThreadClass = Class.forName("android.app.ActivityThread")
        val currentApplicationMethod = activityThreadClass.getDeclaredMethod("currentApplication")
        val app = currentApplicationMethod.invoke(null) as? Context
        return app ?: error("ActivityThread.currentApplication() returned null")
    }
}
