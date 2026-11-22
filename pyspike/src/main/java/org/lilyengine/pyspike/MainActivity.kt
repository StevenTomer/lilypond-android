package org.lilyengine.pyspike

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = TextView(this).apply { textSize = 12f; setPadding(24, 96, 24, 24) }
        setContentView(ScrollView(this).apply { addView(view) })
        Thread {
            val t0 = System.nanoTime()
            if (!Python.isStarted()) Python.start(AndroidPlatform(this))
            val startMs = (System.nanoTime() - t0) / 1_000_000
            val text = runCatching {
                "Python start: $startMs ms\n\n" + Python.getInstance().getModule("spike").callAttr("run").toString()
            }.getOrElse { it.stackTraceToString() }
            Log.i("PySpike", text)
            runOnUiThread { view.text = text }
        }.start()
    }
}
