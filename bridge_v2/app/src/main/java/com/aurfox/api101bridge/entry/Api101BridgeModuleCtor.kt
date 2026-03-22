package com.aurfox.api101bridge.entry

import com.aurfox.api101bridge.bridge.BridgeRuntime
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

/**
 * Fallback host entry for older modern-api shapes that still construct modules with
 * (XposedInterface, ModuleLoadedParam).
 *
 * IMPORTANT:
 * - Use this only if your local libxposed 101 line still compiles against this signature.
 * - Do not ship both entry variants at the same time.
 */
class Api101BridgeModuleCtor(
    base: XposedInterface,
    param: ModuleLoadedParam,
) : XposedModule(base, param) {
    init {
        BridgeRuntime.attachHost(this, param)
        log("bridge-ctor: attached process=${param.processName}")
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        super.onPackageLoaded(param)
        BridgeRuntime.dispatchPackageLoaded(param)
    }
}
