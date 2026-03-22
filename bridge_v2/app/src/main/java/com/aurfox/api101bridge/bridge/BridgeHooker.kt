package com.aurfox.api101bridge.bridge

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.AfterHookCallback
import io.github.libxposed.api.XposedInterface.BeforeHookCallback
import io.github.libxposed.api.annotations.AfterInvocation
import io.github.libxposed.api.annotations.BeforeInvocation
import io.github.libxposed.api.annotations.XposedHooker
import java.lang.reflect.Method

/**
 * Generic host-side hooker.
 * It converts modern API101 callbacks into the plugin class loader and then reflectively
 * invokes the plugin's API100-style hooker methods.
 */
@XposedHooker
class BridgeHooker : XposedInterface.Hooker {
    companion object {
        @JvmStatic
        @BeforeInvocation
        fun beforeInvocation(callback: BeforeHookCallback): BridgeInvocationContext {
            val hookedMethod = callback.member as? Method
            val registration = hookedMethod?.let(BridgeHookRegistry::find)
            if (registration == null) return BridgeInvocationContext(null, null)

            val pluginHookerClass = registration.pluginHookerClass
            val pluginBeforeCallbackClass = pluginHookerClass.classLoader.loadClass(ReflectionNames.BEFORE_CALLBACK)
            val pluginCallback = PluginCallbackProxyFactory.create(pluginBeforeCallbackClass, callback)
            val method = pluginHookerClass.methods.firstOrNull {
                it.name == "beforeInvocation" && it.parameterTypes.size == 1
            } ?: return BridgeInvocationContext(registration, null)
            val pluginContext = method.invoke(null, pluginCallback)
            return BridgeInvocationContext(registration, pluginContext)
        }

        @JvmStatic
        @AfterInvocation
        fun afterInvocation(callback: AfterHookCallback, context: BridgeInvocationContext) {
            val registration = context.registration ?: return
            val pluginHookerClass = registration.pluginHookerClass
            val pluginAfterCallbackClass = pluginHookerClass.classLoader.loadClass(ReflectionNames.AFTER_CALLBACK)
            val pluginCallback = PluginCallbackProxyFactory.create(pluginAfterCallbackClass, callback)
            val method = pluginHookerClass.methods.firstOrNull {
                it.name == "afterInvocation" && it.parameterTypes.size == 2
            } ?: return
            method.invoke(null, pluginCallback, context.pluginContext)
        }
    }
}
