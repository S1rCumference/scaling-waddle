package com.recorder.core.storage

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DiagnosticEntry(
    val timestamp: Long,
    val level: Level,
    val tag: String,
    val message: String,
) {
    enum class Level { INFO, WARN, ERROR }

    fun render(clock: SimpleDateFormat): String =
        "${clock.format(Date(timestamp))} ${level.name.padEnd(5)} $tag: $message"
}

/**
 * A small, always-on record of what this app did and what went wrong — readable from the
 * phone alone, with nothing to plug in.
 *
 * The bring-up steps that need logcat (README, docs/BRING_UP.md) needed a computer, which is
 * exactly what this app's own premise says the phone's owner may not have. So every failure
 * worth knowing about — recording refused to start, a model would not load, a download
 * failed, an update check failed — is recorded here as well as to Logcat, in the same
 * process, and appended to a small capped file so a crash or a kill does not lose the entries
 * that explain it. Settings → Diagnostics shows and exports this.
 *
 * Deliberately not a general-purpose logging framework: a fixed, short list of call sites
 * write here (see their own comments), chosen because they are exactly the places earlier
 * bugs in this app were invisible without a computer.
 */
object Diagnostics {

    private const val MAX_ENTRIES = 300
    private const val FILE_NAME = "diagnostics.log"

    private val _entries = MutableStateFlow<List<DiagnosticEntry>>(emptyList())

    /** Newest first. */
    val entries: StateFlow<List<DiagnosticEntry>> = _entries.asStateFlow()

    private var logFile: File? = null
    private val lock = Any()

    /** Reads whatever survived from before this process started. Safe to call more than once. */
    fun init(context: Context) {
        synchronized(lock) {
            if (logFile != null) return
            val file = File(context.filesDir, FILE_NAME)
            logFile = file
            val loaded = runCatching {
                if (!file.isFile) return@runCatching emptyList()
                file.readLines().mapNotNull(::parse)
            }.getOrDefault(emptyList())
            _entries.value = loaded.asReversed()
        }
    }

    fun i(tag: String, message: String) = log(tag, message, DiagnosticEntry.Level.INFO)
    fun w(tag: String, message: String) = log(tag, message, DiagnosticEntry.Level.WARN)
    fun e(tag: String, message: String) = log(tag, message, DiagnosticEntry.Level.ERROR)

    /** [throwable]'s message is folded into [message] rather than a stack trace: this is for a
     * person reading their own phone, not a bug tracker, and a stack trace from a stripped
     * release build is class and method names with no line numbers — noise, not a clue. */
    fun w(tag: String, message: String, throwable: Throwable) =
        log(tag, "$message (${throwable.message ?: throwable.javaClass.simpleName})", DiagnosticEntry.Level.WARN)

    fun e(tag: String, message: String, throwable: Throwable) =
        log(tag, "$message (${throwable.message ?: throwable.javaClass.simpleName})", DiagnosticEntry.Level.ERROR)

    private fun log(tag: String, message: String, level: DiagnosticEntry.Level) {
        // A logging call must never be the reason something else crashes — including in a
        // plain JUnit test, where android.util.Log's methods are unimplemented stubs that
        // throw rather than a real logger.
        runCatching {
            when (level) {
                DiagnosticEntry.Level.INFO -> Log.i(tag, message)
                DiagnosticEntry.Level.WARN -> Log.w(tag, message)
                DiagnosticEntry.Level.ERROR -> Log.e(tag, message)
            }
        }
        val entry = DiagnosticEntry(System.currentTimeMillis(), level, tag, message.replace('\n', ' '))
        synchronized(lock) {
            _entries.value = (listOf(entry) + _entries.value).take(MAX_ENTRIES)
            appendToFile(entry)
        }
    }

    private fun appendToFile(entry: DiagnosticEntry) {
        val file = logFile ?: return
        runCatching {
            file.appendText("${entry.timestamp}\t${entry.level.name}\t${entry.tag}\t${entry.message}\n")
            // Trim occasionally rather than on every write, which would mean re-reading and
            // re-writing a growing file on every single log line.
            if (file.length() > TRIM_ABOVE_BYTES) {
                val lines = file.readLines().takeLast(MAX_ENTRIES)
                file.writeText(lines.joinToString("\n", postfix = "\n"))
            }
        }
    }

    private fun parse(line: String): DiagnosticEntry? {
        val parts = line.split('\t', limit = 4)
        if (parts.size != 4) return null
        val ts = parts[0].toLongOrNull() ?: return null
        val level = runCatching { DiagnosticEntry.Level.valueOf(parts[1]) }.getOrElse { return null }
        return DiagnosticEntry(ts, level, parts[2], parts[3])
    }

    fun clear() {
        synchronized(lock) {
            _entries.value = emptyList()
            runCatching { logFile?.writeText("") }
        }
    }

    /** Everything, oldest first, as plain text — for Copy and Share. [header] goes on top. */
    fun renderText(header: String): String {
        val clock = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        return buildString {
            append(header.trimEnd())
            append("\n\n")
            _entries.value.asReversed().forEach { append(it.render(clock)).append('\n') }
            if (_entries.value.isEmpty()) append("(nothing logged yet)\n")
        }
    }

    private const val TRIM_ABOVE_BYTES = 256 * 1024
}
