package com.mohammadjafari.sortview

import android.app.Application
import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Application class with an in-app crash log (v1.0.1).
 *
 * The first real-device test (v1.0.0) crashed while opening a folder and,
 * with no adb/PC available, the stack trace was unreachable — the app then
 * looked broken with no diagnostic path. The default uncaught exception
 * handler now appends every crash to filesDir/crash_log.txt before the
 * normal crash flow continues (the previous handler still runs, so the
 * system crash dialog and process death are unchanged). The About dialog
 * shows the latest entry with copy/clear buttons, so the next crash can be
 * reported to the developer straight from the phone.
 */
class SortViewApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrashEntry(this, thread, throwable)
            } catch (_: Throwable) {
                // Logging must never mask the original crash.
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        private const val CRASH_FILE = "crash_log.txt"

        /** Keep the log bounded (~64 KB): drop the oldest half when larger. */
        private const val MAX_LOG_CHARS = 64_000

        /** Cap one displayed/copied entry. */
        private const val MAX_ENTRY_CHARS = 6_000

        private const val SEPARATOR = "--------------------------------------------------"

        fun crashLogFile(context: Context): File = File(context.filesDir, CRASH_FILE)

        /**
         * The most recent crash entry (separator + stack trace), or "" when
         * the log is missing/empty — used by the About dialog.
         */
        fun lastCrashReport(context: Context): String {
            val text = try {
                crashLogFile(context).readText(Charsets.UTF_8)
            } catch (_: Exception) {
                return ""
            }
            if (text.isBlank()) return ""
            val idx = text.lastIndexOf(SEPARATOR)
            val entry = if (idx >= 0) text.substring(idx) else text
            return entry.take(MAX_ENTRY_CHARS)
        }

        fun clearCrashLog(context: Context) {
            try {
                crashLogFile(context).delete()
            } catch (_: Exception) {
            }
        }

        private fun writeCrashEntry(app: Application, thread: Thread, throwable: Throwable) {
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val entry = buildString {
                append(SEPARATOR).append('\n')
                append("SortView crash  ").append(stamp)
                append("  (thread: ").append(thread.name).append(")\n")
                append(sw.toString().take(MAX_ENTRY_CHARS))
                append('\n')
            }
            val f = crashLogFile(app)
            val old = try {
                f.readText(Charsets.UTF_8)
            } catch (_: Exception) {
                ""
            }
            val kept = if (old.length + entry.length > MAX_LOG_CHARS) {
                old.substring(old.length / 2)
            } else {
                old
            }
            f.writeText(kept + entry, Charsets.UTF_8)
        }
    }
}
