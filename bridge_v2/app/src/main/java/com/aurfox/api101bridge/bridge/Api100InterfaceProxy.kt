package com.aurfox.api101bridge.bridge

import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Reflective API100 view over the host module/runtime.
 *
 * This intentionally avoids depending on exact API101 method names at compile time more than
 * necessary. When the host line exposes renamed methods, the bridge tries both old and new names.
 */
object Api100InterfaceProxy {
    fun create(pluginInterface: Class<*>, hostModule: XposedModule): Any {
        return Proxy.newProxyInstance(
            pluginInterface.classLoader,
            arrayOf(pluginInterface),
        ) { _, method, args ->
            when (method.name) {
                "getApiVersion" -> 100
                "getFrameworkName" -> invokeGetter(hostModule, "frameworkName")
                "getFrameworkVersion" -> invokeGetter(hostModule, "frameworkVersion")
                "getFrameworkVersionCode" -> invokeGetter(hostModule, "frameworkVersionCode")
                "getFrameworkCapabilities" ->
                    invokeIfExists(hostModule, "getFrameworkProperties") ?: invokeIfExists(hostModule, "getFrameworkCapabilities")
                "getFrameworkProperties" ->
                    invokeIfExists(hostModule, "getFrameworkProperties") ?: invokeIfExists(hostModule, "getFrameworkCapabilities")
                "log" -> invokeBestEffort(hostModule, method.name, args)
                "hook" -> {
                    val hookedMethod = args?.getOrNull(0) as? Method
                        ?: error("bridge hook expects java.lang.reflect.Method")
                    val pluginHookerClass = args.getOrNull(1) as? Class<*>
                        ?: error("bridge hook expects hooker Class")
                    BridgeHookRegistry.register(hostModule, hookedMethod, pluginHookerClass)
                }
                else -> invokeBestEffort(hostModule, method.name, args)
            }
        }
    }

    private fun invokeGetter(target: Any, propertyName: String): Any? {
        return try {
            val field = target.javaClass.methods.firstOrNull {
                it.name.equals("get" + propertyName.replaceFirstChar(Char::uppercaseChar)) && it.parameterTypes.isEmpty()
            }
            field?.invoke(target)
        } catch (_: Throwable) {
            null
        }
    }

    private fun invokeBestEffort(target: Any, name: String, args: Array<out Any?>?): Any? {
        val candidate = target.javaClass.methods.firstOrNull {
            it.name == name && it.parameterTypes.size == (args?.size ?: 0)
        } ?: return null
        candidate.isAccessible = true
        return candidate.invoke(target, *(args ?: emptyArray()))
    }

    private fun invokeIfExists(target: Any, name: String): Any? {
        val method = target.javaClass.methods.firstOrNull { it.name == name && it.parameterTypes.isEmpty() }
            ?: return null
        method.isAccessible = true
        return method.invoke(target)
    }
}
