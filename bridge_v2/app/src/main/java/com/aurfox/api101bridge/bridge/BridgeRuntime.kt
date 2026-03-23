// I版完整BridgeRuntime（HostClassLoader）
package com.aurfox.api101bridge.bridge

import android.util.Log
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

object BridgeRuntime {
    private const val TAG = "API101BridgeI444"

    fun dispatchPackageLoaded(param: PackageLoadedParam) {
        Log.e(TAG, "PROBE-0323-I-HOST-CLASSLOADER-444")

        try {
            val loader = param.classLoader
            val entry = loader.loadClass("com.ss.android.ugc.awemes.ModuleMain")
            Log.e(TAG, "entry loaded = " + entry.name)
        } catch (e: Throwable) {
            Log.e(TAG, "load failed: " + e.message)
        }
    }
}
