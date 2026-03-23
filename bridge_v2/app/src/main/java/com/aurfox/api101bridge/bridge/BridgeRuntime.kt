package com.aurfox.api101bridge.bridge

import android.content.Context
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

object BridgeRuntime {
    private const val TAG = "API101BridgeI445"

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
        Log.e(TAG, "PROBE-0323-I-HOST-CLASSLOADER-445")

        try {
            val appContext = currentApplicationContext()
            val loader = appContext.classLoader
            Log.e(TAG, "using app context classloader=" + loader.javaClass.name)
            Log.e(TAG, "packageName=" + appContext.packageName)

            val entry = loader.loadClass("com.ss.android.ugc.awemes.ModuleMain")
            Log.e(TAG, "entry loaded = " + entry.name)
        } catch (e: Throwable) {
            Log.e(TAG, "load failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun currentApplicationContext(): Context {
        val activityThreadClass = Class.forName("android.app.ActivityThread")
        val currentApplicationMethod = activityThreadClass.getDeclaredMethod("currentApplication")
        val app = currentApplicationMethod.invoke(null) as? Context
        return app ?: error("ActivityThread.currentApplication() returned null")
    }
}
