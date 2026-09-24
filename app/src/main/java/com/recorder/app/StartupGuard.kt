package com.recorder.app

import android.content.Context
import com.recorder.core.storage.Diagnostics
import java.io.File

/**
 * Makes a crash on start-up recoverable from the phone, with no computer and no reinstall.
 *
 * An always-on recorder starts working the moment the process does: the service comes up, the
 * speech model loads, the AI model loads. If any of that takes the process down — and a
 * malformed model file does exactly that, from native code, where no Kotlin catch can reach —
 * then every launch dies before the screen appears. The app is then unusable and, worse,
 * unreadable: the diagnostics that would explain it are behind a UI that never draws.
 *
 * So each start writes a mark, and the mark is cleared once the app has demonstrably survived
 * — the screen has been up for a few seconds, or the recorder has been running for a minute.
 * Two starts in a row that never got that far and the next one comes up in safe mode: nothing
 * auto-starts, no model is loaded, the screen explains itself and offers the repair.
 *
 * Deliberately a plain file rather than DataStore: it has to be readable and writable in the
 * first milliseconds of Application.onCreate, before anything asynchronous exists, and it has
 * to survive the process being killed mid-write without taking a preferences store with it.
 */
object StartupGuard {

    private const val TAG = "StartupGuard"
    private const val FILE_NAME = "startup-marks"

    /** Two failed starts, not one: a single kill by the system is not a crash loop. */
    private const val SAFE_MODE_AFTER = 2

    @Volatile
    var safeMode: Boolean = false
        private set

    /** How many starts in a row failed to reach a healthy state. */
    @Volatile
    var unhealthyStarts: Int = 0
        private set

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    /**
     * Called first thing in Application.onCreate. Counts this start as unproven until
     * something calls [markHealthy], and decides whether to come up in safe mode.
     */
    fun begin(context: Context) {
        installCrashLogger()
        begin(file(context))
    }

    /** The counting, against a plain file, so the safety net itself can be tested. */
    internal fun begin(marks: File) {
        val before = runCatching { marks.readText().trim().toInt() }.getOrDefault(0)
        unhealthyStarts = before + 1
        safeMode = unhealthyStarts > SAFE_MODE_AFTER
        runCatching { marks.writeText(unhealthyStarts.toString()) }

        when {
            safeMode -> Diagnostics.e(
                TAG,
                "safe mode: $before previous start(s) did not survive. Nothing will start " +
                    "automatically and no model will be loaded. Settings -> Models has the repair.",
            )

            before > 0 -> Diagnostics.w(
                TAG,
                "the previous start did not survive long enough to be called healthy",
            )
        }
    }

    /**
     * This start is fine. Called from two places on purpose: the screen being up for a few
     * seconds covers a normal launch, and the recorder running for a minute covers a start
     * from boot that nobody is looking at.
     */
    fun markHealthy(context: Context) = markHealthy(file(context))

    internal fun markHealthy(marks: File) {
        if (unhealthyStarts == 0) return
        unhealthyStarts = 0
        runCatching { marks.writeText("0") }
        Diagnostics.i(TAG, "start-up looks healthy")
    }

    /** Leaves safe mode for this launch onwards, at the user's request. */
    fun clearSafeMode(context: Context) {
        safeMode = false
        markHealthy(file(context))
        Diagnostics.i(TAG, "safe mode cleared by hand")
    }

    /**
     * Writes an uncaught exception to Diagnostics before the process goes, so the reason is
     * on the next launch's screen instead of only in a logcat nobody can reach. A crash from
     * native code still cannot be caught here, which is what the mark above is for.
     */
    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is CrashLogger) return
        Thread.setDefaultUncaughtExceptionHandler(CrashLogger(previous))
    }

    private class CrashLogger(
        private val next: Thread.UncaughtExceptionHandler?,
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, error: Throwable) {
            runCatching {
                Diagnostics.e(
                    TAG,
                    "crashed on ${thread.name}: ${error.javaClass.name}: ${error.message}" +
                        (error.stackTrace.firstOrNull()?.let { " at $it" } ?: ""),
                )
            }
            next?.uncaughtException(thread, error)
        }
    }
}
