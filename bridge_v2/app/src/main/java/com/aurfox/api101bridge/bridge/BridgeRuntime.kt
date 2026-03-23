package com.aurfox.api101bridge.bridge

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

object BridgeRuntime {
    private const val TAG = "API101BridgeI444"

    private lateinit var hostModule: XposedModule
    private lateinit var hostModuleLoadedParam: ModuleLoadedParam

    @JvmStatic
    val classLoader: ClassLoader?
        get() = runCatching { hostModule.javaClass.classLoader }.getOrNull()

    @JvmStatic
    fun attachHost(module: XposedModule, param: ModuleLoadedParam) {
        hostModule = module
        hostModuleLoadedParam = param
        Log.e(TAG, "attachHost called")
    }

    @JvmStatic
    fun dispatchPackageLoaded(param: PackageLoadedParam) {
        Log.e(TAG, "PROBE-0323-I-HOST-CLASSLOADER-444")

        try {
            val loader = param.classLoader
            Log.e(TAG, "using host classloader=" + loader.javaClass.name)

            val entry = loader.loadClass("com.ss.android.ugc.awemes.ModuleMain")
            Log.e(TAG, "entry loaded = " + entry.name)
        } catch (e: Throwable) {
            Log.e(TAG, "load failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
