package com.aurfox.api101bridge.bridge

import io.github.libxposed.api.XposedModule
import java.lang.reflect.Executable
import java.util.concurrent.ConcurrentHashMap

data class HookRegistration(
    val pluginHookerClass: Class<*>,
    val pluginClassLoader: ClassLoader,
)

object BridgeHookRegistry {
    private val registrations = ConcurrentHashMap<String, HookRegistration>()

    fun register(
        hostModule: XposedModule,
        hookedExecutable: Executable,
        pluginHookerClass: Class<*>,
        priority: Int? = null,
    ): Any? {
        registrations[signature(hookedExecutable)] = HookRegistration(
            pluginHookerClass = pluginHookerClass,
            pluginClassLoader = pluginHookerClass.classLoader,
        )
        return null
    }

    fun find(executable: Executable): HookRegistration? = registrations[signature(executable)]

    private fun signature(executable: Executable): String {
        return buildString {
            append(executable.declaringClass.name)
            append('#')
            append(executable.name)
            append('(')
            executable.parameterTypes.joinTo(this, ",") { it.name }
            append(')')
        }
    }
}
