package com.aurfox.api101bridge.bridge

import android.util.Log
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

data class HookRegistration(
    val pluginHookerClass: Class<*>,
    val pluginClassLoader: ClassLoader,
)

data class BridgeInvocationContext(
    val registration: HookRegistration,
    val pluginContext: Any? = null,
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

        Log.e("API101Bridge", "register hook for=" + signature(hookedExecutable))

        return when (hookedExecutable) {
            is Method -> {
                if (priority != null) hostModule.hook(hookedExecutable, priority, BridgeHooker::class.java)
                else hostModule.hook(hookedExecutable, BridgeHooker::class.java)
            }
            is Constructor<*> -> {
                if (priority != null) hostModule.hook(hookedExecutable, priority, BridgeHooker::class.java)
                else hostModule.hook(hookedExecutable, BridgeHooker::class.java)
            }
            else -> null
        }
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
