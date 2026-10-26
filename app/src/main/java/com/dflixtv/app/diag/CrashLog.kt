package com.dflixtv.app.diag

import android.content.Context
import android.os.Build
import com.dflixtv.app.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the last few crashes in a file so they can be read in Settings > Diagnostics and sent from
 * the TV, which otherwise leaves no trace of a crash.
 */
object CrashLog {
    private const val MAX_CRASHES = 3
    private const val SEPARATOR = "\n\n========\n\n"
    private lateinit var file: File

    fun install(context: Context) {
        file = File(context.filesDir, "crashes.txt")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { record(thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun record(thread: Thread, error: Throwable) {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val entry = buildString {
            appendLine("${timestamp()} · DFLIX ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}, ${BuildConfig.FLAVOR})")
            appendLine("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · thread ${thread.name}")
            append(trace.take(12_000))
        }
        val kept = (listOf(entry) + crashes()).take(MAX_CRASHES)
        file.writeText(kept.joinToString(SEPARATOR))
    }

    /** Newest first. */
    fun crashes(): List<String> =
        runCatching { file.readText() }.getOrNull()?.split(SEPARATOR)?.filter { it.isNotBlank() }.orEmpty()

    /** "26 Oct 21:14 · IllegalStateException: …" for the newest crash, or null. */
    fun lastSummary(): String? = crashes().firstOrNull()?.lines()?.let { lines ->
        val time = lines.firstOrNull()?.substringBefore(" · ") ?: return null
        val cause = lines.drop(2).firstOrNull { it.isNotBlank() }?.substringAfterLast('.')?.take(120) ?: "unknown error"
        "$time · $cause"
    }

    fun clear() {
        runCatching { file.delete() }
    }

    private fun timestamp() = SimpleDateFormat("d MMM yyyy HH:mm", Locale.ENGLISH).format(Date())
}
