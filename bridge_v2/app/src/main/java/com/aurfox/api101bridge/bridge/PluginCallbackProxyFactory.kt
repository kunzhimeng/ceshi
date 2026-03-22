package com.aurfox.api101bridge.bridge

import java.lang.reflect.Proxy

object PluginCallbackProxyFactory {
    fun create(pluginCallbackInterface: Class<*>, hostCallback: Any): Any {
        return Proxy.newProxyInstance(
            pluginCallbackInterface.classLoader,
            arrayOf(pluginCallbackInterface),
        ) { _, method, args ->
            val name = method.name
            val hostMethod = hostCallback.javaClass.methods.firstOrNull {
                it.name == name && it.parameterTypes.size == (args?.size ?: 0)
            } ?: return@newProxyInstance when (name) {
                "toString" -> "BridgeCallbackProxy(${pluginCallbackInterface.name})"
                "hashCode" -> System.identityHashCode(hostCallback)
                "equals" -> hostCallback === args?.firstOrNull()
                else -> null
            }
            hostMethod.isAccessible = true
            hostMethod.invoke(hostCallback, *(args ?: emptyArray()))
        }
    }
}
