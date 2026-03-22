package com.aurfox.api101bridge.ui

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = TextView(this).apply {
            text = """
                API101 Bridge Host

                This app is the visible shell of the bridge module.
                Put an API100 module APK into the bridge app's private storage,
                then let the bridge entry load it with DexClassLoader.

                Current proof-of-concept target profile:
                - entry: com.ss.android.ugc.awemes.ModuleMain
                - minApiVersion: 100
                - targetApiVersion: 100
            """.trimIndent()
            setPadding(48, 48, 48, 48)
        }
        setContentView(text)
    }
}
