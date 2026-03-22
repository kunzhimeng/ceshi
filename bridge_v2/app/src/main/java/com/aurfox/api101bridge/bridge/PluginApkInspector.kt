package com.aurfox.api101bridge.bridge

import java.io.File
import java.util.Properties
import java.util.zip.ZipFile

data class TargetPluginInfo(
    val apk: File,
    val entryClass: String,
    val minApiVersion: Int?,
    val targetApiVersion: Int?,
    val staticScope: Boolean,
)

object PluginApkInspector {
    fun inspect(apk: File): TargetPluginInfo {
        ZipFile(apk).use { zip ->
            val entryClass = zip.getEntry("META-INF/xposed/java_init.list")
                ?.let { zip.getInputStream(it).bufferedReader().readLines().firstOrNull() }
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: error("Missing META-INF/xposed/java_init.list")

            val props = Properties()
            zip.getEntry("META-INF/xposed/module.prop")?.let { entry ->
                zip.getInputStream(entry).use(props::load)
            }

            return TargetPluginInfo(
                apk = apk,
                entryClass = entryClass,
                minApiVersion = props.getProperty("minApiVersion")?.toIntOrNull(),
                targetApiVersion = props.getProperty("targetApiVersion")?.toIntOrNull(),
                staticScope = props.getProperty("staticScope")?.toBooleanStrictOrNull() ?: false,
            )
        }
    }
}
