package com.aurfox.api101bridge.bridge

import java.lang.reflect.Proxy

object PluginParamProxyFactory {
    fun create(pluginParamInterface: Class<*>, hostParam: Any): Any {
        return Proxy.newProxyInstance(
            pluginParamInterface.classLoader,
            arrayOf(pluginParamInterface),
        ) { _, method, args ->
            val hostMethod = hostParam.javaClass.methods.firstOrNull {
                it.name == method.name && it.parameterTypes.size == (args?.size ?: 0)
            } ?: return@newProxyInstance when (method.name) {
                "toString" -> "BridgeParamProxy(${pluginParamInterface.name})"
                "hashCode" -> System.identityHashCode(hostParam)
                "equals" -> hostParam === args?.firstOrNull()
                else -> null
            }
            hostMethod.isAccessible = true
            hostMethod.invoke(hostParam, *(args ?: emptyArray()))
        }
    }
}
