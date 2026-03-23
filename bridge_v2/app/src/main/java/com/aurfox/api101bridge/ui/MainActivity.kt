package com.aurfox.api101bridge.ui

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val status = runCatching {
            val assetName = assets.list("")?.firstOrNull {
                it.contains(".apk")
            } ?: error("assets 里没找到旧版 APK")

            val target = File(filesDir, "target-api100.apk")

            assets.open(assetName).use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            buildString {
                appendLine("API101 Bridge Host PROBE-0323-D-UNIQUE-777")
                appendLine()
                appendLine("已导入旧模块：$assetName")
                appendLine("目标路径：${target.absolutePath}")
                appendLine("文件大小：${target.length()} bytes")
            }
        }.getOrElse { e ->
            buildString {
                appendLine("API101 Bridge Host PROBE-0323-D-UNIQUE-777")
                appendLine()
                appendLine("导入失败")
                appendLine("${e.javaClass.simpleName}: ${e.message}")
            }
        }

        setContentView(
            TextView(this).apply {
                text = status
                setPadding(48, 48, 48, 48)
            }
        )
    }
}
