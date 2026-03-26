package com.aurfox.api101bridge.bridge

import android.content.Context
import android.util.Log
import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction35c
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction3rc
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.DexFile
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.MethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction3rc
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import java.io.File
import java.util.LinkedHashSet
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

object ConstructorPatch {
    private const val PATCH_DIR = "bridge_ctor_patch"

    @JvmStatic
    fun patchModuleMainConstructor(
        sourceApk: File,
        ctx: Context,
        label: String,
        entryClassName: String,
        runtimeSuperClassName: String,
        logTag: String,
    ): File {
        val outDir = File(ctx.cacheDir, PATCH_DIR).apply { mkdirs() }
        val outFile = File(outDir, sourceApk.nameWithoutExtension + "-$label-ctorpatch.apk")

        val targetType = dotToType(entryClassName)
        val runtimeSuperType = dotToType(runtimeSuperClassName)

        var classesDexCount = 0
        var methodsPatched = 0

        ZipFile(sourceApk).use { zip ->
            ZipOutputStream(outFile.outputStream().buffered()).use { zos ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val newEntry = ZipEntry(entry.name).apply {
                        time = entry.time
                        comment = entry.comment
                    }
                    zos.putNextEntry(newEntry)

                    val raw = if (entry.isDirectory) {
                        ByteArray(0)
                    } else {
                        zip.getInputStream(entry).use { it.readBytes() }
                    }

                    val output = if (!entry.isDirectory && entry.name.matches(Regex("classes(\\d*)\\.dex"))) {
                        classesDexCount++
                        val (patched, count) = patchSingleDex(
                            rawDex = raw,
                            workDir = outDir,
                            entryName = entry.name,
                            targetType = targetType,
                            runtimeSuperType = runtimeSuperType,
                            logTag = logTag,
                        )
                        methodsPatched += count
                        patched
                    } else {
                        raw
                    }

                    zos.write(output)
                    zos.closeEntry()
                }
            }
        }

        Log.e(
            logTag,
            "ctorPatch finished: entry=$entryClassName, runtimeSuper=$runtimeSuperClassName, " +
                "classesDexCount=$classesDexCount, methodsPatched=$methodsPatched, patchApplied=${methodsPatched > 0}"
        )

        return outFile
    }

    private fun patchSingleDex(
        rawDex: ByteArray,
        workDir: File,
        entryName: String,
        targetType: String,
        runtimeSuperType: String,
        logTag: String,
    ): Pair<ByteArray, Int> {
        val inDex = File(workDir, "in-$entryName")
        val outDex = File(workDir, "out-$entryName")
        inDex.writeBytes(rawDex)

        val dexFile = DexFileFactory.loadDexFile(inDex, Opcodes.getDefault())

        val newClasses = LinkedHashSet<ClassDef>()
        var methodsPatched = 0

        dexFile.classes.forEach { classDef ->
            if (classDef.type == targetType) {
                val (patchedClass, patchedCount) = patchTargetClass(
                    classDef = classDef,
                    runtimeSuperType = runtimeSuperType,
                    logTag = logTag,
                )
                newClasses += patchedClass
                methodsPatched += patchedCount
            } else {
                newClasses += classDef
            }
        }

        if (methodsPatched == 0) {
            Log.e(logTag, "ctorPatch no-op for $entryName")
            return rawDex to 0
        }

        DexFileFactory.writeDexFile(
            outDex.absolutePath,
            object : DexFile {
                override fun getClasses(): Set<ClassDef> = newClasses
                override fun getOpcodes(): Opcodes = dexFile.opcodes
            }
        )

        return outDex.readBytes() to methodsPatched
    }

    private fun patchTargetClass(
        classDef: ClassDef,
        runtimeSuperType: String,
        logTag: String,
    ): Pair<ClassDef, Int> {
        val patchedMethods = classDef.methods.map { method ->
            patchMethodIfNeeded(
                ownerType = classDef.type,
                method = method,
                runtimeSuperType = runtimeSuperType,
                logTag = logTag,
            )
        }

        val patchedCount = patchedMethods.count { it.second }
        if (patchedCount == 0) {
            return classDef to 0
        }

        val rebuilt = ImmutableClassDef(
            classDef.type,
            classDef.accessFlags,
            classDef.superclass,
            classDef.interfaces,
            classDef.sourceFile,
            classDef.annotations,
            classDef.fields,
            patchedMethods.map { it.first },
        )
        return rebuilt to patchedCount
    }

    private fun patchMethodIfNeeded(
        ownerType: String,
        method: Method,
        runtimeSuperType: String,
        logTag: String,
    ): Pair<Method, Boolean> {
        if (method.name != "<init>") return method to false
        val paramTypes = method.parameters.map { it.type }
        if (paramTypes.size != 2) return method to false
        if (!paramTypes[0].contains("XposedInterface")) return method to false
        if (!paramTypes[1].contains("ModuleLoadedParam")) return method to false

        val impl = method.implementation ?: return method to false
        val mutable = MutableMethodImplementation(impl)

        var replaced = false
        val newSuperRef = ImmutableMethodReference(
            runtimeSuperType,
            "<init>",
            emptyList<String>(),
            "V"
        )

        val instructions = mutable.instructions
        for (index in instructions.indices) {
            val insn = instructions[index]
            val ref = extractMethodReference(insn) ?: continue
            if (ref.name != "<init>") continue
            if (ref.definingClass != runtimeSuperType) continue
            if (ref.parameterTypes.size != 2) continue

            val thisRegister = extractThisRegister(insn)
            if (thisRegister < 0) continue

            val replacement = if (insn is Instruction3rc || thisRegister > 0xF) {
                BuilderInstruction3rc(
                    Opcode.INVOKE_DIRECT_RANGE,
                    thisRegister,
                    1,
                    newSuperRef,
                )
            } else {
                BuilderInstruction35c(
                    Opcode.INVOKE_DIRECT,
                    1,
                    thisRegister,
                    0,
                    0,
                    0,
                    0,
                    newSuperRef,
                )
            }

            mutable.replaceInstruction(index, replacement)
            Log.e(
                logTag,
                "ctorPatch replaced invoke-direct in $ownerType-><init> " +
                    "from ${ref.parameterTypes} to ()V at instructionIndex=$index"
            )
            replaced = true
            break
        }

        if (!replaced) {
            Log.e(logTag, "ctorPatch target ctor found but no matching super invoke in $ownerType")
            return method to false
        }

        val rebuilt = ImmutableMethod(
            method.definingClass,
            method.name,
            method.parameters,
            method.returnType,
            method.accessFlags,
            method.annotations,
            method.hiddenApiRestrictions,
            mutable,
        )

        return rebuilt to true
    }

    private fun extractMethodReference(insn: Any): MethodReference? {
        if (insn !is ReferenceInstruction) return null
        val ref = insn.reference
        return if (ref is MethodReference) ref else null
    }

    private fun extractThisRegister(insn: Any): Int {
        return when (insn) {
            is Instruction35c -> insn.registerC
            is Instruction3rc -> insn.startRegister
            else -> -1
        }
    }

    private fun dotToType(dotName: String): String {
        return "L" + dotName.replace('.', '/') + ";"
    }
}
