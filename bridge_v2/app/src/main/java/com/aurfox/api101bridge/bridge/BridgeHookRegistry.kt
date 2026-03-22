package com.aurfox.api101bridge.bridge

import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

private data class HookRegistration(
    val pluginHookerClass: Class<*>,
)

data class BridgeInvocationContext(
    val registration: HookRegistration?,
    val pluginContext: Any?,
)

object BridgeHookRegistry {
    private val registrations = ConcurrentHashMap<String, HookRegistration>()

    fun register(
        hostModule: XposedModule,
        hookedMethod: Method,
        pluginHookerClass: Class<*>,
    ): Any? {
        registrations[signature(hookedMethod)] = HookRegistration(pluginHookerClass)
        val hookMethod = hostModule.javaClass.methods.firstOrNull {
            it.name == "hook" && it.parameterTypes.size == 2
        } ?: error("No hook(Method, Class) entry found on host module")
        return hookMethod.invoke(hostModule, hookedMethod, BridgeHooker::class.java)
    }

    internal fun find(method: Method): HookRegistration? = registrations[signature(method)]

    private fun signature(method: Method): String {
        return buildString {
            append(method.declaringClass.name)
            append('#')
            append(method.name)
            append('(')
            method.parameterTypes.joinTo(this, ",") { it.name }
            append(')')
        }
    }
}
