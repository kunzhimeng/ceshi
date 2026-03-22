package com.aurfox.api101bridge.bridge

import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Executable

class BridgeHooker : XposedInterface.Hooker {
    companion object {
        @JvmStatic
        fun before(callback: Any): Any? {
            val executable = extractExecutable(callback) ?: return null
            val registration = BridgeHookRegistry.find(executable) ?: return null
            return try {
                val beforeInterface = registration.pluginClassLoader.loadClass(ReflectionNames.BEFORE_CALLBACK)
                val pluginCallback = PluginCallbackProxyFactory.create(beforeInterface, callback)
                val beforeMethod = registration.pluginHookerClass.methods.firstOrNull {
                    it.name == "before" && it.parameterTypes.size == 1
                } ?: return BridgeInvocationContext(registration, null)
                beforeMethod.isAccessible = true
                val pluginContext = beforeMethod.invoke(null, pluginCallback)
                Log.e("API101Bridge", "before dispatched for=" + executable.name)
                BridgeInvocationContext(registration, pluginContext)
            } catch (t: Throwable) {
                Log.e("API101Bridge", "before dispatch failed", t)
                null
            }
        }

        @JvmStatic
        fun after(callback: Any, ctx: Any?) {
            val executable = extractExecutable(callback) ?: return
            val context = ctx as? BridgeInvocationContext ?: return
            val registration = context.registration
            try {
                val afterInterface = registration.pluginClassLoader.loadClass(ReflectionNames.AFTER_CALLBACK)
                val pluginCallback = PluginCallbackProxyFactory.create(afterInterface, callback)
                val afterMethod = registration.pluginHookerClass.methods.firstOrNull {
                    it.name == "after" && (it.parameterTypes.size == 1 || it.parameterTypes.size == 2)
                } ?: return
                afterMethod.isAccessible = true
                if (afterMethod.parameterTypes.size == 2) {
                    afterMethod.invoke(null, pluginCallback, context.pluginContext)
                } else {
                    afterMethod.invoke(null, pluginCallback)
                }
                Log.e("API101Bridge", "after dispatched for=" + executable.name)
            } catch (t: Throwable) {
                Log.e("API101Bridge", "after dispatch failed", t)
            }
        }

        private fun extractExecutable(callback: Any): Executable? {
            val methods = listOf("getExecutable", "getMember")
            for (name in methods) {
                val m = callback.javaClass.methods.firstOrNull { it.name == name && it.parameterTypes.isEmpty() } ?: continue
                m.isAccessible = true
                val value = m.invoke(callback)
                if (value is Executable) return value
            }
            return null
        }
    }
}
