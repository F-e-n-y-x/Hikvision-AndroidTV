package com.hiktv.viewer.util

import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Minimal file-based crash capture. Field TVs auto-close with no PC attached, so without this a
 * crash leaves no trace the user can read. On any uncaught JVM exception we write the stack (plus
 * device + app info) to a single file in app-private storage, then re-raise to the previous handler
 * so the process still dies normally. Surfaced in Settings → "Last crash".
 *
 * Caveat: a JVM handler can NOT catch native crashes (Mali GL / decoder SIGSEGV) — those still need
 * `adb logcat -b crash -d`. This covers Java/Kotlin OOM, ANR-less exceptions, and logic crashes.
 */
object CrashLog {

    private const val FILE = "last_crash.txt"

    /** Install the process-wide handler. Call once from Application.onCreate. */
    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, ex ->
            runCatching { write(app, thread, ex) }
            previous?.uncaughtException(thread, ex)
        }
    }

    private fun write(context: Context, thread: Thread, ex: Throwable) {
        val trace = StringWriter().also { ex.printStackTrace(PrintWriter(it)) }.toString()
        val text = buildString {
            append("Time  : ").append(STAMP.format(Date())).append('\n')
            append("App   : ").append(appVersion(context)).append('\n')
            append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append("  (Android ").append(Build.VERSION.RELEASE)
                .append(", hw=").append(Build.HARDWARE).append(")").append('\n')
            append("Thread: ").append(thread.name).append("\n\n")
            append(trace)
        }
        File(context.filesDir, FILE).writeText(text)
    }

    /** The most recent captured crash, or null if none has been recorded. */
    fun last(context: Context): String? {
        val f = File(context.applicationContext.filesDir, FILE)
        return if (f.exists()) runCatching { f.readText() }.getOrNull() else null
    }

    fun clear(context: Context) {
        runCatching { File(context.applicationContext.filesDir, FILE).delete() }
    }

    private fun appVersion(context: Context): String = runCatching {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        "${pi.versionName} (${versionCode(pi)})"
    }.getOrDefault("?")

    @Suppress("DEPRECATION")
    private fun versionCode(pi: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pi.longVersionCode else pi.versionCode.toLong()

    private val STAMP: SimpleDateFormat
        get() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
}
