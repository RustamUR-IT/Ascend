package com.ascend.ui

import android.app.Application
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Runs before any Ascend screen or service: loads fonts and installs the crash logger. */
class AscendApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        Fonts.init(this)
    }
}

/**
 * Writes the last crash to a file so it can be shown and shared from the Ascend screen.
 * Nothing is uploaded; the user decides whether to share it.
 */
object CrashLog {
    private const val FILE = "last_crash.txt"

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, err ->
            try { write(app, thread.name, err) } catch (_: Throwable) { }
            previous?.uncaughtException(thread, err)
        }
    }

    private fun write(ctx: Context, thread: String, err: Throwable) {
        val sw = StringWriter()
        err.printStackTrace(PrintWriter(sw))
        val version = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        } catch (_: Exception) { "?" }
        val text = buildString {
            appendLine("Ascend $version crash")
            appendLine(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
            appendLine("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("Thread: $thread")
            appendLine()
            append(sw.toString().take(12000))
        }
        File(ctx.filesDir, FILE).writeText(text)
    }

    fun read(ctx: Context): String? = try {
        File(ctx.filesDir, FILE).takeIf { it.exists() }?.readText()
    } catch (_: Exception) { null }

    fun clear(ctx: Context) {
        try { File(ctx.filesDir, FILE).delete() } catch (_: Exception) { }
    }
}
