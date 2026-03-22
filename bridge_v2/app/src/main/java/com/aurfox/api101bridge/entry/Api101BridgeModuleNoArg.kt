package com.aurfox.api101bridge.entry

import android.util.Log
import com.aurfox.api101bridge.bridge.BridgeRuntime
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

class Api101BridgeModuleNoArg : XposedModule() {
    override fun onModuleLoaded(param: ModuleLoadedParam) {
        Log.e("API101Bridge", "onModuleLoaded called")
        BridgeRuntime.attachHost(this, param)
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        Log.e("API101Bridge", "onPackageLoaded called")
        BridgeRuntime.dispatchPackageLoaded(param)
    }
}
