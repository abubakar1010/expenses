package com.app.finance.log

import android.os.StrictMode
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant

/**
 * The app's entire crash-reporting story — 04-system-architecture.md §8.
 *
 * > "**No crash reporting SDK.** NFR-SEC-02 forbids it, and a single-user app
 * > has no fleet to monitor. Unhandled exceptions are written to an app-private
 * > log file that the user can attach to a manual bug report if they choose."
 *
 * `proguard-rules.pro` already keeps `SourceFile` and `LineNumberTable` for
 * exactly this file, so without it the release build was paying APK bytes for
 * line numbers nothing recorded.
 *
 * Nothing leaves the device. The directory is app-private internal storage, the
 * same place the database lives (NFR-SEC-03), and there is no network layer to
 * send it anywhere even if something wanted to.
 *
 * This sits in its own `log/` package rather than in `core/`, which 04 §3.1
 * reserves for pure Kotlin — the `architectureCheck` task rejected it there,
 * correctly, because it needs `android.os.StrictMode`.
 */
class CrashLog(private val directory: () -> File) {

    /**
     * Installs the handler, chaining to whatever was there before so the
     * platform still shows its dialog and still terminates the process. A
     * handler that swallows the crash would turn a visible failure into a
     * frozen app, which is worse.
     *
     * The directory is a lambda, not a `File`, for one reason: resolving
     * `Context.filesDir` calls `getDataDir()`, which stats the filesystem.
     * Passing a resolved `File` cost 30 ms of main-thread disk during
     * `Application.onCreate` — caught by StrictMode, and a straight violation
     * of NFR-PERF-09's "zero occurrences" on the tightest budget in the app.
     * Nothing here touches storage until something actually crashes.
     */
    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Most recent first. For manual triage; call off the main thread. */
    fun recent(): List<File> =
        directory().listFiles()?.sortedByDescending(File::lastModified).orEmpty()

    private fun write(thread: Thread, error: Throwable) {
        // The handler runs on the crashing thread, which is usually the main
        // one, and StrictMode's penaltyDeath is armed in debug builds. Killing
        // the process for writing a crash log during a crash would lose the
        // very artifact this exists to produce.
        val policy = StrictMode.allowThreadDiskWrites()
        try {
            val dir = directory()
            if (!dir.exists() && !dir.mkdirs()) return

            val stack = StringWriter().also { error.printStackTrace(PrintWriter(it)) }
            val stamp = Instant.now().toString().replace(':', '-')

            File(dir, "crash-$stamp.txt").writeText(
                buildString {
                    appendLine("time:   ${Instant.now()}")
                    appendLine("thread: ${thread.name}")
                    appendLine()
                    append(stack.toString())
                },
            )
            prune()
        } finally {
            StrictMode.setThreadPolicy(policy)
        }
    }

    /** Keeps the last few. An unbounded log is a slow storage leak. */
    private fun prune() {
        directory().listFiles()
            ?.sortedByDescending(File::lastModified)
            ?.drop(KEEP)
            ?.forEach { it.delete() }
    }

    private companion object {
        const val KEEP = 5
    }
}
