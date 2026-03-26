@JvmStatic
fun dispatchPackageLoaded(param: PackageLoadedParam) {
    Log.e(TAG, "PROBE-0325-ALT-REWRITE-906")
    val loaded = ensureLoaded(param) ?: run {
        Log.e(TAG, "ensureLoaded returned null")
        return
    }
    dumpObjectState("entryBeforeDispatch", loaded.entryInstance, 2)
    runCatching {
        val pluginPackageLoadedParam = loaded.onPackageLoaded?.parameterTypes?.singleOrNull()
            ?: error("onPackageLoaded parameter type unavailable")
        val pluginParam = PluginParamProxyFactory.create(pluginPackageLoadedParam, param)
        Log.e(TAG, "about to invoke legacy onPackageLoaded with paramType=${pluginPackageLoadedParam.name}")
        val onPackageLoaded = loaded.onPackageLoaded ?: error("onPackageLoaded method missing")
        onPackageLoaded.invoke(loaded.entryInstance, pluginParam)
        Log.e(TAG, "legacy onPackageLoaded invoked")
    }.onFailure {
        Log.e(TAG, "dispatchPackageLoaded failed: ${throwableChain(it)}", it)
        dumpObjectState("entryAfterDispatchFailure", loaded.entryInstance, 3)
    }
}

private fun ensureLoaded(packageParam: PackageLoadedParam): LoadedPlugin? {
    loadedRef.get()?.let { return it }
    return runCatching {
        val ctx = currentApplicationContextOrNull() ?: run {
            Log.e(TAG, "currentApplication still null, skip this round")
            return null
        }
        Log.e("API101BridgeV2", "currentApplicationContext=${ctx.packageName}")

        val outerApk = PluginStorage.materializeBundledPlugin(ctx, HOST_PACKAGE)
            ?: error("materialized outer apk null")
        Log.e(TAG, "outerApk=${outerApk.absolutePath}")

        inspectDexFileClasses(outerApk, "outer")

        val candidate = resolveCandidate(outerApk, ctx)
        Log.e(TAG, "selected candidate label=${candidate.label}")
        Log.e(TAG, "selected candidate apk=${candidate.apk.absolutePath}")
        Log.e(TAG, "selected candidate entry=${candidate.entry}")

        inspectDexFileClasses(candidate.apk, candidate.label)
        probeDexLoadClass(candidate.apk, candidate.entry, candidate.label)

        val rewrittenApk = rewritePluginApkExpanded(candidate.apk, ctx, candidate.label)
        Log.e(TAG, "rewritten candidate apk=${rewrittenApk.absolutePath}")
        inspectDexFileClasses(rewrittenApk, candidate.label + "-rewritten")
        probeDexLoadClass(rewrittenApk, candidate.entry, candidate.label + "-rewritten")

        val loaderResult = loadEntryClassWithStrategies(rewrittenApk, candidate.entry, ctx)
        Log.e(TAG, "entry load strategy=${loaderResult.strategy}")
        Log.e(TAG, "entryClassLoader=${loaderResult.entryClass.classLoader}")
        Log.e(TAG, "entrySuper=${loaderResult.entryClass.superclass?.name}")
        Log.e(TAG, "entryConstructorCount=${loaderResult.entryClass.constructors.size}")

        dumpClassAbi("runtimeXposedModule", XposedModule::class.java)
        dumpClassAbi("entrySuperClass", loaderResult.entryClass.superclass)
        dumpClassAbi("entryClass", loaderResult.entryClass)
        dumpClassHierarchyAbi("entryHierarchy", loaderResult.entryClass)

        val runtimeInterfaceClass = XposedInterface::class.java
        val runtimeModuleLoadedParamClass = hostModuleLoadedParam.javaClass.interfaces.firstOrNull {
            it.name.endsWith("ModuleLoadedParam") ||
                ModuleLoadedParam::class.java.isAssignableFrom(it) ||
                it.isAssignableFrom(ModuleLoadedParam::class.java)
        } ?: ModuleLoadedParam::class.java
        Log.e(TAG, "runtimeInterfaceClass=${runtimeInterfaceClass.name}")
        Log.e(TAG, "runtimeModuleLoadedParamClass=${runtimeModuleLoadedParamClass.name}")

        loaderResult.entryClass.constructors.forEachIndexed { index, ctor ->
            val names = runCatching { ctor.parameterTypes.joinToString { it.name } }
                .getOrElse { "<unavailable:${throwableChain(it)}>" }
            Log.e(TAG, "ctor[$index] paramTypes=$names")
        }

        val ctor = loaderResult.entryClass.constructors.firstOrNull { c ->
            val params = runCatching { c.parameterTypes.toList() }.getOrNull() ?: return@firstOrNull false
            if (params.size != 2) return@firstOrNull false
            matchesRuntimeType(params[0], runtimeInterfaceClass, "XposedInterface") &&
                matchesRuntimeType(params[1], runtimeModuleLoadedParamClass, "ModuleLoadedParam")
        } ?: loaderResult.entryClass.constructors.firstOrNull { c ->
            runCatching { c.parameterTypes.size == 2 }.getOrDefault(false)
        } ?: error("no usable 2-arg constructor")

        val pluginParams = runCatching { ctor.parameterTypes }.getOrElse {
            error("resolved constructor parameterTypes unavailable: ${throwableChain(it)}")
        }
        val pluginInterface = pluginParams[0]
        val pluginModuleLoadedParam = pluginParams[1]
        Log.e(TAG, "selected ctor param0=${pluginInterface.name}")
        Log.e(TAG, "selected ctor param1=${pluginModuleLoadedParam.name}")

        val interfaceProxy = Api100InterfaceProxy.create(pluginInterface, hostModule)
        val moduleLoadedParamProxy = PluginParamProxyFactory.create(
            pluginModuleLoadedParam,
            hostModuleLoadedParam,
        )

        val entryInstance = instantiateLegacyEntry(
            ctor = ctor,
            interfaceProxy = interfaceProxy,
            moduleLoadedParamProxy = moduleLoadedParamProxy,
        )

        dumpObjectState("entryAfterInstantiate", entryInstance, 3)

        val onPackageLoaded = loaderResult.entryClass.methods.firstOrNull {
            it.name == "onPackageLoaded" && it.parameterTypes.size == 1
        }
        Log.e(TAG, "onPackageLoaded method=$onPackageLoaded")

        LoadedPlugin(loaderResult.classLoader, entryInstance, onPackageLoaded).also {
            loadedRef.set(it)
        }
    }.getOrElse {
        Log.e(TAG, "ensureLoaded failed: ${throwableChain(it)}", it)
        null
    }
}

private fun primeRuntimeFields(
    instance: Any,
    interfaceProxy: Any,
    moduleLoadedParamProxy: Any,
) {
    val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
    var hit = 0

    fun seedObject(obj: Any?, depth: Int) {
        if (obj == null) return
        if (depth > 4) return
        if (!seen.add(obj)) return

        walkClassHierarchy(obj.javaClass).forEach { owner ->
            owner.declaredFields.forEach { field ->
                if (Modifier.isStatic(field.modifiers)) return@forEach

                runCatching {
                    field.isAccessible = true
                    val curValue = field.get(obj)

                    if (canReceiveValue(field.type, interfaceProxy)) {
                        field.set(obj, interfaceProxy)
                        hit++
                        Log.e(TAG, "primed field ${owner.name}#${field.name} with interfaceProxy")
                    } else if (
                        canReceiveValue(field.type, moduleLoadedParamProxy) ||
                        (field.name.contains("param", ignoreCase = true) &&
                            field.type.isAssignableFrom(moduleLoadedParamProxy.javaClass))
                    ) {
                        field.set(obj, moduleLoadedParamProxy)
                        hit++
                        Log.e(TAG, "primed field ${owner.name}#${field.name} with moduleLoadedParamProxy")
                    } else if (curValue != null && shouldDescend(curValue)) {
                        Log.e(
                            TAG,
                            "descending into ${owner.name}#${field.name}, " +
                                "valueClass=${curValue.javaClass.name}, depth=$depth"
                        )
                        seedObject(curValue, depth + 1)
                    } else if (curValue == null && shouldConstructHolder(field.type)) {
                        if (holderLooksUseful(field.type, interfaceProxy, moduleLoadedParamProxy)) {
                            val holder = createHolderOrNull(field.type)
                            if (holder != null) {
                                field.set(obj, holder)
                                Log.e(
                                    TAG,
                                    "created holder for ${owner.name}#${field.name}, " +
                                        "holderClass=${holder.javaClass.name}, depth=$depth"
                                )
                                seedObject(holder, depth + 1)
                            }
                        }
                    } else {
                        Unit
                    }
                }.onFailure {
                    Log.e(TAG, "prime field failed ${owner.name}#${field.name}: ${throwableChain(it)}", it)
                }
            }
        }
    }

    seedObject(instance, 0)
    Log.e(TAG, "primeRuntimeFields hitCount=$hit")
}

private fun canReceiveValue(fieldType: Class<*>, value: Any): Boolean {
    return fieldType.isInstance(value) || fieldType.isAssignableFrom(value.javaClass)
}

private fun shouldDescend(value: Any): Boolean {
    val clazz = value.javaClass
    val n = clazz.name
    if (clazz.isPrimitive) return false
    if (clazz.isEnum) return false
    if (clazz.isArray) return false
    if (n.startsWith("java.")) return false
    if (n.startsWith("javax.")) return false
    if (n.startsWith("kotlin.")) return false
    if (n.startsWith("android.")) return false
    return true
}

private fun shouldConstructHolder(clazz: Class<*>): Boolean {
    val n = clazz.name
    if (clazz.isPrimitive) return false
    if (clazz.isEnum) return false
    if (clazz.isArray) return false
    if (clazz.isInterface) return false
    if (Modifier.isAbstract(clazz.modifiers)) return false
    if (n.startsWith("java.")) return false
    if (n.startsWith("javax.")) return false
    if (n.startsWith("kotlin.")) return false
    if (n.startsWith("android.")) return false
    if (n.startsWith("okhttp3.")) return false
    return true
}

private fun holderLooksUseful(
    clazz: Class<*>,
    interfaceProxy: Any,
    moduleLoadedParamProxy: Any,
): Boolean {
    return walkClassHierarchy(clazz).any { owner ->
        owner.declaredFields.any { field ->
            !Modifier.isStatic(field.modifiers) && (
                canReceiveValue(field.type, interfaceProxy) ||
                    canReceiveValue(field.type, moduleLoadedParamProxy) ||
                    field.name.contains("param", ignoreCase = true) ||
                    field.name.contains("base", ignoreCase = true) ||
                    field.type.name.contains("ModuleLoadedParam")
                )
        }
    }
}

private fun createHolderOrNull(clazz: Class<*>): Any? {
    return runCatching {
        val ctor = clazz.getDeclaredConstructor()
        ctor.isAccessible = true
        ctor.newInstance()
    }.getOrElse {
        runCatching { allocateInstanceWithoutConstructor(clazz) }.getOrNull()
    }
}

private fun dumpClassHierarchyAbi(label: String, start: Class<*>) {
    walkClassHierarchy(start).forEachIndexed { index, clazz ->
        dumpClassAbi("$label[$index]", clazz)
    }
}

private fun dumpObjectState(label: String, root: Any?, maxDepth: Int) {
    if (root == null) {
        Log.e(TAG, "$label = <null>")
        return
    }

    val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())

    fun visit(obj: Any, depth: Int, path: String) {
        if (depth > maxDepth) return
        if (!seen.add(obj)) return

        Log.e(TAG, "$path objectClass=${obj.javaClass.name}")

        walkClassHierarchy(obj.javaClass).forEach { owner ->
            owner.declaredFields.forEach { field ->
                if (Modifier.isStatic(field.modifiers)) return@forEach

                runCatching {
                    field.isAccessible = true
                    val value = field.get(obj)
                    val valueDesc = value?.javaClass?.name ?: "<null>"
                    Log.e(
                        TAG,
                        "$path field ${owner.name}#${field.name} type=${field.type.name} value=$valueDesc"
                    )
                    if (value != null && shouldDescend(value)) {
                        visit(value, depth + 1, "$path.${field.name}")
                    }
                }.onFailure {
                    Log.e(TAG, "$path dump failed ${owner.name}#${field.name}: ${throwableChain(it)}", it)
                }
            }
        }
    }

    visit(root, 0, label)
}
