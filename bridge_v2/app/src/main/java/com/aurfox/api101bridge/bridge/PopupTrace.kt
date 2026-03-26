package com.aurfox.api101bridge.bridge

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
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
    private val polling = AtomicBoolean(false)
    private val currentActivityRef = AtomicReference<WeakReference<Activity>?>(null)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val observers = mutableListOf<FileObserver>()
    private var pollCount = 0
    private var awemeXmlSnapshot: Map<String, String> = emptyMap()

    @JvmStatic
    fun start(ctx: Context, logTag: String) {
        if (!started.compareAndSet(false, true)) return

        val app = ctx.applicationContext as? Application
        if (app == null) {
            TraceLog.log(logTag, "POPUP_TRACE no application context available")
            return
        }

        TraceLog.log(logTag, "POPUP_TRACE start app=" + app.packageName)

        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                currentActivityRef.set(WeakReference(activity))
                TraceLog.log(logTag, "POPUP_TRACE activityCreated=" + activity.javaClass.name)
            }

            override fun onActivityStarted(activity: Activity) {
                currentActivityRef.set(WeakReference(activity))
                TraceLog.log(logTag, "POPUP_TRACE activityStarted=" + activity.javaClass.name)
            }

            override fun onActivityResumed(activity: Activity) {
                currentActivityRef.set(WeakReference(activity))
                TraceLog.log(logTag, "POPUP_TRACE activityResumed=" + activity.javaClass.name)
                dumpOnce(app, logTag, "activityResumed")
            }

            override fun onActivityPaused(activity: Activity) {
                TraceLog.log(logTag, "POPUP_TRACE activityPaused=" + activity.javaClass.name)
            }

            override fun onActivityStopped(activity: Activity) {
                TraceLog.log(logTag, "POPUP_TRACE activityStopped=" + activity.javaClass.name)
            }

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

            override fun onActivityDestroyed(activity: Activity) {
                TraceLog.log(logTag, "POPUP_TRACE activityDestroyed=" + activity.javaClass.name)
            }
        })

        val dataDir = File(app.applicationInfo.dataDir)
        val sharedPrefsDir = File(dataDir, "shared_prefs")
        val mmkvDir = File(app.filesDir, "mmkv")
        val filesDir = app.filesDir
        val cacheDir = app.cacheDir
        val databasesDir = File(dataDir, "databases")

        attachFileObserver(logTag, "shared_prefs", sharedPrefsDir) { changed ->
            if (changed == "aweme-app.xml") {
                dumpAwemePrefsDiff(logTag, File(sharedPrefsDir, "aweme-app.xml"), "fileObserver")
                dumpWindowRoots(logTag)
            }
        }
        attachFileObserver(logTag, "mmkv", mmkvDir, null)
        attachFileObserver(logTag, "files", filesDir, null)
        attachFileObserver(logTag, "cache", cacheDir, null)
        attachFileObserver(logTag, "databases", databasesDir, null)

        dumpAwemePrefsDiff(logTag, File(sharedPrefsDir, "aweme-app.xml"), "start")

        if (polling.compareAndSet(false, true)) {
            schedule(app, logTag)
        }
    }

    @JvmStatic
    fun note(stage: String, logTag: String) {
        TraceLog.log(logTag, "POPUP_TRACE note=" + stage)
        val current = currentActivityRef.get()?.get()
        if (current != null) {
            dumpWindowRoots(logTag)
        }
    }

    private fun schedule(app: Application, logTag: String) {
        mainHandler.post(object : Runnable {
            override fun run() {
                try {
                    dumpOnce(app, logTag, "poll")
                } catch (t: Throwable) {
                    TraceLog.log(logTag, "POPUP_TRACE poll failed: ${t.javaClass.simpleName}: ${t.message}", t)
                }
                if (pollCount < 60) {
                    mainHandler.postDelayed(this, 1000L)
                } else {
                    TraceLog.log(logTag, "POPUP_TRACE poll finished")
                }
            }
        })
    }

    private fun dumpOnce(app: Application, logTag: String, reason: String) {
        pollCount++
        val activity = currentActivityRef.get()?.get()
        TraceLog.log(
            logTag,
            "POPUP_TRACE dump reason=$reason poll=$pollCount activity=" + (activity?.javaClass?.name ?: "<null>")
        )
        dumpWindowRoots(logTag)

        val sharedPrefsDir = File(app.applicationInfo.dataDir, "shared_prefs")
        val mmkvDir = File(app.filesDir, "mmkv")
        if (pollCount == 1 || pollCount % 5 == 0) {
            dumpDir(logTag, "shared_prefs", sharedPrefsDir)
            dumpDir(logTag, "mmkv", mmkvDir)
            dumpDir(logTag, "files", app.filesDir)
            dumpDir(logTag, "cache", app.cacheDir)
            dumpDir(logTag, "databases", File(app.applicationInfo.dataDir, "databases"))
            dumpAwemePrefsDiff(logTag, File(sharedPrefsDir, "aweme-app.xml"), "poll")
        }
    }

    private fun attachFileObserver(
        logTag: String,
        label: String,
        dir: File,
        onChange: ((String) -> Unit)?,
    ) {
        if (!dir.exists()) {
            TraceLog.log(logTag, "POPUP_TRACE observer[$label] missingDir=" + dir.absolutePath)
            return
        }
        val mask = FileObserver.CREATE or FileObserver.MODIFY or FileObserver.CLOSE_WRITE or
            FileObserver.MOVED_TO or FileObserver.DELETE
        val observer = object : FileObserver(dir, mask) {
            override fun onEvent(event: Int, path: String?) {
                val name = path ?: "<null>"
                val file = if (path != null) File(dir, path) else null
                val size = file?.takeIf { it.exists() }?.length()?.toString() ?: "<na>"
                TraceLog.log(logTag, "POPUP_TRACE file[$label] event=$event path=$name size=$size")
                if (path != null && label == "databases" && isInterestingDbFile(path)) {
                    onInterestingDbChanged(logTag, label, dir, path)
                }
                if (path != null) {
                    onChange?.invoke(path)
                }
            }
        }
        observer.startWatching()
        observers += observer
        TraceLog.log(logTag, "POPUP_TRACE observer[$label] start dir=" + dir.absolutePath)
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

        TraceLog.log(logTag, "POPUP_TRACE windowRootCount=" + views.size)

        views.forEachIndexed { index, view ->
            val title = params.getOrNull(index)?.toString() ?: "<no-layout-params>"
            val snippet = extractTextSnippet(view)
            TraceLog.log(
                logTag,
                "POPUP_TRACE window[$index] root=" + view.javaClass.name +
                    " shown=" + view.isShown +
                    " attached=" + view.isAttachedToWindow +
                    " focus=" + view.hasWindowFocus() +
                    " title=" + title +
                    " text=" + snippet
            )
            dumpControlSnapshot(logTag, index, view)
        }
    }

    private fun extractTextSnippet(root: View): String {
        val out = linkedSetOf<String>()

        fun walk(view: View, depth: Int) {
            if (depth > 5 || out.size >= 16) return
            if (view is TextView) {
                val text = view.text?.toString()?.trim().orEmpty()
                val hint = view.hint?.toString()?.trim().orEmpty()
                if (text.isNotEmpty()) out += text.take(80)
                if (hint.isNotEmpty()) out += ("hint:" + hint.take(80))
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


    private fun dumpControlSnapshot(logTag: String, windowIndex: Int, root: View) {
        val controls = mutableListOf<String>()

        fun walk(view: View, depth: Int) {
            if (depth > 6 || controls.size >= 40) return

            val className = view.javaClass.simpleName
            val idName = runCatching {
                if (view.id != View.NO_ID) {
                    view.resources.getResourceEntryName(view.id)
                } else null
            }.getOrNull() ?: "<no-id>"

            when (view) {
                is TextView -> {
                    val text = view.text?.toString()?.trim().orEmpty()
                    val hint = view.hint?.toString()?.trim().orEmpty()
                    controls += "TextView class=$className id=$idName text=" + shorten(text) + " hint=" + shorten(hint)
                }
            }

            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    walk(view.getChildAt(i), depth + 1)
                }
            }
        }

        walk(root, 0)

        if (controls.isEmpty()) {
            TraceLog.log(logTag, "POPUP_TRACE controls[$windowIndex] count=0")
        } else {
            TraceLog.log(logTag, "POPUP_TRACE controls[$windowIndex] count=" + controls.size)
            controls.take(40).forEach { item ->
                TraceLog.log(logTag, "POPUP_TRACE controls[$windowIndex] " + item)
            }
        }
    }


    private fun isInterestingDbFile(name: String): Boolean {
        val n = name.lowercase(Locale.US)
        return n == "verifystorage.db" ||
            n == "verifystorage.db-wal" ||
            n == "verifystorage.db-shm" ||
            n == "pz_database" ||
            n == "pz_database-wal" ||
            n == "pz_database-shm"
    }

    private fun onInterestingDbChanged(logTag: String, label: String, dir: File, path: String) {
        val file = File(dir, path)
        val size = if (file.exists()) file.length() else -1L
        TraceLog.log(
            logTag,
            "POPUP_TRACE interesting-db[$label] path=" + path +
                " size=" + size +
                " exists=" + file.exists()
        )
        val activity = currentActivityRef.get()?.get()
        TraceLog.log(
            logTag,
            "POPUP_TRACE interesting-db[$label] currentActivity=" +
                (activity?.javaClass?.name ?: "<null>")
        )
        dumpWindowRoots(logTag)
    }

    private fun dumpDir(logTag: String, label: String, dir: File) {
        val files = dir.listFiles()?.sortedBy { it.name }.orEmpty()
        TraceLog.log(logTag, "POPUP_TRACE storage[$label] dir=" + dir.absolutePath + " count=" + files.size)
        files.take(40).forEach { file ->
            val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(file.lastModified()))
            val prefix = if (label == "databases" && isInterestingDbFile(file.name)) {
                "POPUP_TRACE storage[$label] IMPORTANT file="
            } else {
                "POPUP_TRACE storage[$label] file="
            }
            TraceLog.log(
                logTag,
                prefix + file.name +
                    " size=" + file.length() +
                    " mtime=" + ts
            )
        }
    }

    private fun dumpAwemePrefsDiff(logTag: String, xmlFile: File, reason: String) {
        if (!xmlFile.exists()) {
            TraceLog.log(logTag, "POPUP_TRACE aweme-app.xml missing reason=" + reason)
            return
        }

        val current = parseSimplePrefsXml(xmlFile)
        if (awemeXmlSnapshot.isEmpty()) {
            awemeXmlSnapshot = current
            TraceLog.log(logTag, "POPUP_TRACE aweme-app snapshot init size=" + current.size + " reason=" + reason)
            current.entries.take(20).forEach { (k, v) ->
                TraceLog.log(logTag, "POPUP_TRACE aweme-app INIT " + k + "=" + shorten(v))
            }
            return
        }

        val changed = mutableListOf<String>()

        current.forEach { (k, v) ->
            val old = awemeXmlSnapshot[k]
            if (old == null) {
                changed += "ADD " + k + "=" + shorten(v)
            } else if (old != v) {
                changed += "CHG " + k + ": " + shorten(old) + " -> " + shorten(v)
            }
        }

        awemeXmlSnapshot.keys.forEach { k ->
            if (!current.containsKey(k)) {
                changed += "DEL " + k
            }
        }

        if (changed.isNotEmpty()) {
            TraceLog.log(logTag, "POPUP_TRACE aweme-app diff reason=" + reason + " count=" + changed.size)
            changed.sorted().take(80).forEach { item ->
                TraceLog.log(logTag, "POPUP_TRACE aweme-app " + item)
            }
        } else {
            TraceLog.log(logTag, "POPUP_TRACE aweme-app diff reason=" + reason + " count=0")
        }

        awemeXmlSnapshot = current
    }

    private fun parseSimplePrefsXml(xmlFile: File): Map<String, String> {
        val text = runCatching { xmlFile.readText() }.getOrElse { return emptyMap() }
        val out = linkedMapOf<String, String>()

        val regex = Regex("""<(string|int|long|boolean|float)\s+name="([^"]+)"(?:\s+value="([^"]*)")?>([^<]*)</?(string|int|long|boolean|float)?>?""")
        regex.findAll(text).forEach { m ->
            val name = m.groupValues.getOrNull(2).orEmpty()
            val valueAttr = m.groupValues.getOrNull(3).orEmpty()
            val valueText = m.groupValues.getOrNull(4).orEmpty()
            val value = if (valueAttr.isNotEmpty()) valueAttr else valueText
            if (name.isNotEmpty()) out[name] = value
        }

        val boolRegex = Regex("""<boolean\s+name="([^"]+)"\s+value="([^"]*)"\s*/>""")
        boolRegex.findAll(text).forEach { m ->
            out[m.groupValues[1]] = m.groupValues[2]
        }

        val intRegex = Regex("""<(int|long|float)\s+name="([^"]+)"\s+value="([^"]*)"\s*/>""")
        intRegex.findAll(text).forEach { m ->
            out[m.groupValues[2]] = m.groupValues[3]
        }

        return out
    }

    private fun shorten(value: String): String {
        return if (value.length <= 120) value else value.take(120) + "...(" + value.length + ")"
    }
}
