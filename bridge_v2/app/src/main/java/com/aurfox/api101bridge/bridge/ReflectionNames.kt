package com.aurfox.api101bridge.bridge

object ReflectionNames {
    const val XPOSED_INTERFACE = "io.github.libxposed.api.XposedInterface"
    const val XPOSED_MODULE_INTERFACE = "io.github.libxposed.api.XposedModuleInterface"
    const val MODULE_LOADED_PARAM = "io.github.libxposed.api.XposedModuleInterface\$ModuleLoadedParam"
    const val PACKAGE_LOADED_PARAM = "io.github.libxposed.api.XposedModuleInterface\$PackageLoadedParam"
    const val BEFORE_CALLBACK = "io.github.libxposed.api.XposedInterface\$BeforeHookCallback"
    const val AFTER_CALLBACK = "io.github.libxposed.api.XposedInterface\$AfterHookCallback"
    const val METHOD_UNHOOKER = "io.github.libxposed.api.XposedInterface\$MethodUnhooker"
}
