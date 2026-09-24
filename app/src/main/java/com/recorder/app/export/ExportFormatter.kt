package com.recorder.app.export

import com.recorder.app.ui.LineView
import com.recorder.core.storage.CorrectionPass
import com.recorder.core.storage.ExportDefaults
import com.recorder.core.storage.Clocks
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Turns lines into an export. Pure — no Android types — so the exact output is unit tested.
 *
 * [content] is original, corrected or both ([ExportDefaults]); [format] is Markdown (with
 * timestamps and headings per day and hour) or plain text.
 */
object ExportFormatter {

    fun render(
        title: String,
        lines: List<LineView>,
        content: String,
        format: String,
        exportedAt: Long = System.currentTimeMillis(),
        zone: TimeZone = TimeZone.getDefault(),
    ): String {
        val markdown = format == ExportDefaults.FORMAT_MARKDOWN
        val day = fmt("EEEE d MMMM yyyy", zone)
        // Exports follow the clock the app is set to, so a file reads the way the screen
        // it came from did.
        val twelve = !Clocks.use24Hour.value
        val hour = fmt(if (twelve) "h:00 a" else "HH:00", zone)
        val clock = fmt(if (twelve) "h:mm:ss a" else "HH:mm:ss", zone)
        val stamp = fmt(if (twelve) "yyyy-MM-dd h:mm a" else "yyyy-MM-dd HH:mm", zone)

        val multiDay = lines.map { day.format(Date(it.segment.startTs)) }.distinct().size > 1
        val multiHour = lines.map { hour.format(Date(it.segment.startTs)) + day.format(Date(it.segment.startTs)) }
            .distinct().size > 1

        return buildString {
            if (markdown) append("# ").append(title).append("\n\n") else append(title).append("\n\n")
            val note = "Exported ${stamp.format(Date(exportedAt))} · ${lines.size} lines · ${describe(content, lines)}"
            if (markdown) append('_').append(note).append("_\n") else append(note).append('\n')

            var lastDay: String? = null
            var lastHour: String? = null
            for (line in lines) {
                val date = Date(line.segment.startTs)
                val d = day.format(date)
                val h = hour.format(date)
                if (multiDay && d != lastDay) {
                    append('\n').append(if (markdown) "## $d" else "== $d ==").append('\n')
                    lastHour = null
                }
                if (multiHour && (h != lastHour || d != lastDay)) {
                    append('\n').append(if (markdown) "${if (multiDay) "###" else "##"} $h" else "-- $h --").append('\n')
                }
                lastDay = d
                lastHour = h

                val time = clock.format(date)
                val primary = when (content) {
                    ExportDefaults.CONTENT_ORIGINAL -> line.original
                    else -> line.corrected
                }
                if (markdown) append("- **").append(time).append("** ").append(primary).append('\n')
                else append('[').append(time).append("] ").append(primary).append('\n')

                if (content == ExportDefaults.CONTENT_BOTH && line.changed) {
                    if (markdown) append("  - _original:_ ").append(line.original).append('\n')
                    else append("           (original) ").append(line.original).append('\n')
                }
            }
        }
    }

    fun fileName(title: String, format: String): String {
        val slug = title.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(60)
            .ifBlank { "export" }
        return "recorder-$slug.${if (format == ExportDefaults.FORMAT_MARKDOWN) "md" else "txt"}"
    }

    private fun describe(content: String, lines: List<LineView>): String {
        val engines = lines.mapNotNull { it.correction }
            .groupingBy { "${it.engine}, ${CorrectionPass.label(it.pass)}" }
            .eachCount()
            .keys
        val by = if (engines.isEmpty()) "" else " by " + engines.joinToString("; ")
        return when (content) {
            ExportDefaults.CONTENT_ORIGINAL -> "original text as transcribed"
            ExportDefaults.CONTENT_BOTH -> "corrected text$by, with the original under any changed line"
            else -> "corrected text$by (original where no correction exists)"
        }
    }

    private fun fmt(pattern: String, zone: TimeZone) =
        SimpleDateFormat(pattern, Locale.getDefault()).apply { timeZone = zone }
}
