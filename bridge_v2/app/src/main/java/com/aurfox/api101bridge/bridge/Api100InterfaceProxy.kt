package com.aurfox.api101bridge.bridge

import android.util.Log
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Reflective API100 view over the host module/runtime.
 */
object Api100InterfaceProxy {
    fun create(pluginInterface: Class<*>, hostModule: XposedModule): Any {
        return Proxy.newProxyInstance(
            pluginInterface.classLoader,
            arrayOf(pluginInterface),
        ) { _, method, args ->
            when (method.name) {
                "getApiVersion" -> 100
                "getFrameworkName" -> invokeIfExists(hostModule, "getFrameworkName")
                "getFrameworkVersion" -> invokeIfExists(hostModule, "getFrameworkVersion")
                "getFrameworkVersionCode" -> invokeIfExists(hostModule, "getFrameworkVersionCode")
                "getFrameworkCapabilities", "getFrameworkProperties" ->
                    invokeIfExists(hostModule, "getFrameworkProperties") ?: invokeIfExists(hostModule, "getFrameworkCapabilities")
                "getApplicationInfo", "getModuleApplicationInfo" ->
                    invokeIfExists(hostModule, "getApplicationInfo") ?: invokeIfExists(hostModule, "getModuleApplicationInfo")
                "invokeOrigin" -> invokeOrigin(hostModule, args)
                "log" -> invokeLog(hostModule, args)
                "hook" -> invokeHook(hostModule, method.returnType, args)
                else -> invokeBestEffort(hostModule, method.name, args)
            }
        }
    }

    private fun invokeHook(hostModule: XposedModule, returnType: Class<*>, args: Array<out Any?>?): Any? {
        val executable = args?.getOrNull(0) as? Executable
            ?: error("bridge hook expects java.lang.reflect.Executable")
        val pluginHookerClass = args.getOrNull(args.lastIndex) as? Class<*>
            ?: error("bridge hook expects hooker Class")
        val priority = args.getOrNull(1) as? Int

        val hostUnhooker = BridgeHookRegistry.register(hostModule, executable, pluginHookerClass, priority)
        return createUnhookerProxy(returnType, hostUnhooker)
    }

    private fun createUnhookerProxy(pluginReturnType: Class<*>, hostUnhooker: Any?): Any? {
        if (hostUnhooker == null || !pluginReturnType.isInterface) return null
        return Proxy.newProxyInstance(
            pluginReturnType.classLoader,
            arrayOf(pluginReturnType),
        ) { _, method, args ->
            when (method.name) {
                "toString" -> "BridgeMethodUnhooker(${hostUnhooker.javaClass.name})"
                "hashCode" -> System.identityHashCode(hostUnhooker)
                "equals" -> hostUnhooker === args?.firstOrNull()
                else -> {
                    val target = hostUnhooker.javaClass.methods.firstOrNull {
                        it.name == method.name && it.parameterTypes.size == (args?.size ?: 0)
                    } ?: return@newProxyInstance null
                    target.isAccessible = true
                    target.invoke(hostUnhooker, *(args ?: emptyArray()))
                }
            }
        }
    }

    private fun invokeOrigin(hostModule: XposedModule, args: Array<out Any?>?): Any? {
        val executable = args?.getOrNull(0) as? Executable ?: return null
        val thisObject = args.getOrNull(1)
        @Suppress("UNCHECKED_CAST")
        val callArgs = (args.getOrNull(2) as? Array<Any?>) ?: emptyArray()
        return when (executable) {
            is Method -> invokeMatching(hostModule, "invokeOrigin", arrayOf(executable, thisObject, callArgs))
            is Constructor<*> -> invokeMatching(hostModule, "invokeOrigin", arrayOf(executable, thisObject, callArgs))
            else -> null
        }
    }

    private fun invokeLog(hostModule: XposedModule, args: Array<out Any?>?): Any? {
        return when (args?.size ?: 0) {
            1 -> invokeMatching(hostModule, "log", arrayOf(args?.get(0)))
            2 -> invokeMatching(hostModule, "log", arrayOf(args?.get(0), args?.get(1)))
            3 -> invokeMatching(hostModule, "log", args ?: emptyArray())
            4 -> invokeMatching(hostModule, "log", args ?: emptyArray())
            else -> null
        }
    }

    private fun invokeBestEffort(target: Any, name: String, args: Array<out Any?>?): Any? {
        return invokeMatching(target, name, args ?: emptyArray())
    }

    private fun invokeMatching(target: Any, name: String, args: Array<out Any?>): Any? {
        val candidate = target.javaClass.methods.firstOrNull {
            it.name == name && it.parameterTypes.size == args.size && areArgsCompatible(it.parameterTypes, args)
        } ?: return null
        candidate.isAccessible = true
        return candidate.invoke(target, *args)
    }

    private fun areArgsCompatible(paramTypes: Array<Class<*>>, args: Array<out Any?>): Boolean {
        return paramTypes.indices.all { index ->
            val arg = args[index]
            val param = boxType(paramTypes[index])
            arg == null || param.isInstance(arg) || (param.isArray && arg.javaClass.isArray)
        }
    }

    private fun boxType(type: Class<*>): Class<*> = when (type) {
        java.lang.Integer.TYPE -> java.lang.Integer::class.java
        java.lang.Long.TYPE -> java.lang.Long::class.java
        java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
        java.lang.Float.TYPE -> java.lang.Float::class.java
        java.lang.Double.TYPE -> java.lang.Double::class.java
        java.lang.Short.TYPE -> java.lang.Short::class.java
        java.lang.Byte.TYPE -> java.lang.Byte::class.java
        java.lang.Character.TYPE -> java.lang.Character::class.java
        else -> type
    }

    private fun invokeIfExists(target: Any, name: String): Any? {
        val method = target.javaClass.methods.firstOrNull { it.name == name && it.parameterTypes.isEmpty() }
            ?: return null
        method.isAccessible = true
        return method.invoke(target)
    }
}
