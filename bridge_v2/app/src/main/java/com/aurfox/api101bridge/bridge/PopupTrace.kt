package com.aurfox.api101bridge.bridge

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.io.File
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

object PopupTrace {
    private val started = AtomicBoolean(false)
    private val tracing = AtomicBoolean(false)
    private val currentActivityRef = AtomicReference<WeakReference<Activity>?>(null)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pollCount = 0

    @JvmStatic
    fun start(ctx: Context, logTag: String) {
        if (!started.compareAndSet(false, true)) return

        val app = ctx.applicationContext as? Application
        if (app == null) {
            Log.e(logTag, "POPUP_TRACE no application context available")
            return
        }

        Log.e(logTag, "POPUP_TRACE start app=" + app.packageName)

        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                currentActivityRef.set(WeakReference(activity))
                Log.e(logTag, "POPUP_TRACE activityCreated=" + activity.javaClass.name)
            }

            override fun onActivityStarted(activity: Activity) {
                currentActivityRef.set(WeakReference(activity))
                Log.e(logTag, "POPUP_TRACE activityStarted=" + activity.javaClass.name)
            }

            override fun onActivityResumed(activity: Activity) {
                currentActivityRef.set(WeakReference(activity))
                Log.e(logTag, "POPUP_TRACE activityResumed=" + activity.javaClass.name)
                dumpOnce(app, logTag, "activityResumed")
            }

            override fun onActivityPaused(activity: Activity) {
                Log.e(logTag, "POPUP_TRACE activityPaused=" + activity.javaClass.name)
            }

            override fun onActivityStopped(activity: Activity) {
                Log.e(logTag, "POPUP_TRACE activityStopped=" + activity.javaClass.name)
            }

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

            override fun onActivityDestroyed(activity: Activity) {
                Log.e(logTag, "POPUP_TRACE activityDestroyed=" + activity.javaClass.name)
            }
        })

        if (tracing.compareAndSet(false, true)) {
            schedule(app, logTag)
        }
    }

    @JvmStatic
    fun note(stage: String, logTag: String) {
        Log.e(logTag, "POPUP_TRACE note=" + stage)
    }

    private fun schedule(app: Application, logTag: String) {
        mainHandler.post(object : Runnable {
            override fun run() {
                try {
                    dumpOnce(app, logTag, "poll")
                } catch (t: Throwable) {
                    Log.e(logTag, "POPUP_TRACE poll failed: ${t.javaClass.simpleName}: ${t.message}", t)
                }
                if (pollCount < 30) {
                    mainHandler.postDelayed(this, 1000L)
                } else {
                    Log.e(logTag, "POPUP_TRACE poll finished")
                }
            }
        })
    }

    private fun dumpOnce(app: Application, logTag: String, reason: String) {
        pollCount++
        val activity = currentActivityRef.get()?.get()
        Log.e(
            logTag,
            "POPUP_TRACE dump reason=$reason poll=$pollCount activity=" + (activity?.javaClass?.name ?: "<null>")
        )
        dumpWindowRoots(logTag)
        if (pollCount == 1 || pollCount % 5 == 0) {
            dumpStorage(logTag, app)
        }
    }

    private fun dumpWindowRoots(logTag: String) {
        val globalClass = Class.forName("android.view.WindowManagerGlobal")
        val global = globalClass.getDeclaredMethod("getInstance").invoke(null)

        val viewsField = globalClass.getDeclaredField("mViews").apply { isAccessible = true }
        val paramsField = runCatching {
            globalClass.getDeclaredField("mParams").apply { isAccessible = true }
        }.getOrNull()

        val views = (viewsField.get(global) as? List<*>)?.filterIsInstance<View>().orEmpty()
        val params = (paramsField?.get(global) as? List<*>).orEmpty()

        Log.e(logTag, "POPUP_TRACE windowRootCount=" + views.size)

        views.forEachIndexed { index, view ->
            val title = params.getOrNull(index)?.toString() ?: "<no-layout-params>"
            val snippet = extractTextSnippet(view)
            Log.e(
                logTag,
                "POPUP_TRACE window[$index] root=" + view.javaClass.name +
                    " shown=" + view.isShown +
                    " attached=" + view.isAttachedToWindow +
                    " focus=" + view.hasWindowFocus() +
                    " title=" + title +
                    " text=" + snippet
            )
        }
    }

    private fun extractTextSnippet(root: View): String {
        val out = linkedSetOf<String>()

        fun walk(view: View, depth: Int) {
            if (depth > 4 || out.size >= 12) return
            if (view is TextView) {
                val text = view.text?.toString()?.trim().orEmpty()
                val hint = view.hint?.toString()?.trim().orEmpty()
                if (text.isNotEmpty()) out += text.take(60)
                if (hint.isNotEmpty()) out += ("hint:" + hint.take(60))
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    walk(view.getChildAt(i), depth + 1)
                }
            }
        }

        walk(root, 0)
        return if (out.isEmpty()) "<no-text>" else out.joinToString(" | ")
    }

    private fun dumpStorage(logTag: String, app: Application) {
        dumpDir(logTag, "shared_prefs", File(app.applicationInfo.dataDir, "shared_prefs"))
        dumpDir(logTag, "mmkv", File(app.filesDir, "mmkv"))
    }

    private fun dumpDir(logTag: String, label: String, dir: File) {
        val files = dir.listFiles()?.sortedBy { it.name }.orEmpty()
        Log.e(logTag, "POPUP_TRACE storage[$label] dir=" + dir.absolutePath + " count=" + files.size)
        files.take(20).forEach { file ->
            val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(file.lastModified()))
            Log.e(
                logTag,
                "POPUP_TRACE storage[$label] file=" + file.name +
                    " size=" + file.length() +
                    " mtime=" + ts
            )
        }
    }
}
