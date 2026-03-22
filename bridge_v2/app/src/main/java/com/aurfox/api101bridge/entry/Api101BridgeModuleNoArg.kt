package com.aurfox.api101bridge.entry

import com.aurfox.api101bridge.bridge.BridgeRuntime
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

/**
 * Best-effort host entry for the observed API101 shape used by the working reference APK.
 * This matches the no-arg constructor + onModuleLoaded(...) + onPackageLoaded(...) flow.
 *
 * IMPORTANT:
 * - Keep this file when your libxposed 101 AAR expects a no-arg module entry.
 * - Remove/disable the ctor-based entry if you use this one to avoid duplicate java_init targets.
 */
class Api101BridgeModuleNoArg : XposedModule() {
    override fun onModuleLoaded(param: ModuleLoadedParam) {
        super.onModuleLoaded(param)
        BridgeRuntime.attachHost(this, param)
        log("bridge-noarg: attached process=${param.processName}")
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        super.onPackageLoaded(param)
        BridgeRuntime.dispatchPackageLoaded(param)
    }
}
