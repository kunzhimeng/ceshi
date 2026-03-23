package com.aurfox.api101bridge.ui

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            text = "API101 Bridge Host PROBE-0323-KEF-PROBE-888"
            textSize = 18f
            setPadding(48, 48, 48, 48)
        })
    }
}
