package com.recorder.app.export

import com.recorder.app.ui.LineView
import com.recorder.core.storage.Clocks
import com.recorder.core.storage.CorrectionPass
import com.recorder.core.storage.ExportDefaults
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Turns lines into a file. Pure — no Android types — so the exact bytes are unit tested.
 *
 * Four formats. Markdown and plain text are for reading; CSV and JSON Lines are for handing to
 * something else, which is why their columns are fixed and their timestamps are ISO regardless
 * of the clock setting. Inside the readable formats, times follow the app's 12/24-hour setting,
 * so a file reads the way the screen it came from did.
 */
object ExportFormatter {

    /** CSV's header, and the order of its columns. */
    const val CSV_HEADER = "date,time,text,source,flagged"

    fun render(
        title: String,
        lines: List<LineView>,
        query: ExportQuery,
        flaggedIds: Set<Long> = emptySet(),
        exportedAt: Long = System.currentTimeMillis(),
        zone: TimeZone = TimeZone.getDefault(),
    ): String = when (query.format) {
        ExportDefaults.FORMAT_CSV -> csv(lines, query, flaggedIds, zone)
        ExportDefaults.FORMAT_JSONL -> jsonl(lines, query, flaggedIds, zone)
        else -> prose(title, lines, query, exportedAt, zone)
    }

    /**
     * A rough byte count for the live estimate, without building the file.
     *
     * Deliberately an estimate: rendering the whole export on every keystroke to show its size
     * would cost more than the export. It is the text plus a per-line allowance for the
     * timestamp and punctuation each format adds, which lands within a few percent.
     */
    fun estimateBytes(lines: List<LineView>, query: ExportQuery): Long {
        val perLine = when (query.format) {
            ExportDefaults.FORMAT_CSV -> 34L
            ExportDefaults.FORMAT_JSONL -> 96L
            ExportDefaults.FORMAT_MARKDOWN -> 18L
            else -> 14L
        }
        val both = query.content == ExportDefaults.CONTENT_BOTH
        return lines.sumOf { line ->
            val primary = primary(line, query.content).length.toLong()
            val second = if (both && line.changed) line.original.length.toLong() + perLine else 0L
            primary + second + perLine
        } + HEADER_ALLOWANCE
    }

    /** `recorder_2026-09-01_to_2026-09-25.md` — ISO dates, so files sort by name. */
    fun fileName(lines: List<LineView>, query: ExportQuery, zone: TimeZone = TimeZone.getDefault()): String {
        val iso = fmt("yyyy-MM-dd", zone)
        val first = lines.minByOrNull { it.segment.startTs }?.segment?.startTs
        val last = lines.maxByOrNull { it.segment.startTs }?.segment?.startTs
        val from = first?.let { iso.format(Date(it)) } ?: iso.format(Date())
        val to = last?.let { iso.format(Date(it)) } ?: from
        return "recorder_${from}_to_$to.${extension(query.format)}"
    }

    fun extension(format: String): String = when (format) {
        ExportDefaults.FORMAT_MARKDOWN -> "md"
        ExportDefaults.FORMAT_CSV -> "csv"
        ExportDefaults.FORMAT_JSONL -> "jsonl"
        else -> "txt"
    }

    fun mimeType(format: String): String = when (format) {
        ExportDefaults.FORMAT_MARKDOWN -> "text/markdown"
        ExportDefaults.FORMAT_CSV -> "text/csv"
        ExportDefaults.FORMAT_JSONL -> "application/x-ndjson"
        else -> "text/plain"
    }

    // --- readable formats ----------------------------------------------------------------

    private fun prose(
        title: String,
        lines: List<LineView>,
        query: ExportQuery,
        exportedAt: Long,
        zone: TimeZone,
    ): String {
        val markdown = query.format == ExportDefaults.FORMAT_MARKDOWN
        val day = fmt("EEEE d MMMM yyyy", zone)
        val twelve = !Clocks.use24Hour.value
        val hour = fmt(if (twelve) "h:00 a" else "HH:00", zone)
        val clock = fmt(if (twelve) "h:mm:ss a" else "HH:mm:ss", zone)
        val stamp = fmt(if (twelve) "yyyy-MM-dd h:mm a" else "yyyy-MM-dd HH:mm", zone)

        val byDay = query.grouping == ExportGrouping.DAY || query.grouping == ExportGrouping.HOUR
        val byHour = query.grouping == ExportGrouping.HOUR

        return buildString {
            if (markdown) append("# ").append(title).append("\n\n") else append(title).append("\n\n")
            val note = buildString {
                append("Exported ").append(stamp.format(Date(exportedAt)))
                append(" · ").append(lines.size).append(" lines · ").append(describe(query.content, lines))
                query.timeWindow?.let { append(" · ").append(it.describe()) }
                if (query.keywords.include.isNotEmpty()) {
                    append(" · ")
                    append(if (query.keywords.includeAll) "all of: " else "any of: ")
                    append(query.keywords.include.joinToString(", "))
                }
                if (query.keywords.exclude.isNotEmpty()) {
                    append(" · excluding: ").append(query.keywords.exclude.joinToString(", "))
                }
            }
            if (markdown) append('_').append(note).append("_\n") else append(note).append('\n')

            var lastDay: String? = null
            var lastHour: String? = null
            for (line in lines) {
                val date = Date(line.segment.startTs)
                val d = day.format(date)
                val h = hour.format(date)
                if (byDay && d != lastDay) {
                    append('\n').append(if (markdown) "## $d" else "== $d ==").append('\n')
                    lastHour = null
                }
                if (byHour && (h != lastHour || d != lastDay)) {
                    append('\n').append(if (markdown) "### $h" else "-- $h --").append('\n')
                }
                lastDay = d
                lastHour = h

                val time = clock.format(date)
                val text = primary(line, query.content)
                if (markdown) append("- **").append(time).append("** ").append(text).append('\n')
                else append('[').append(time).append("] ").append(text).append('\n')

                if (query.content == ExportDefaults.CONTENT_BOTH && line.changed) {
                    if (markdown) append("  - _original:_ ").append(line.original).append('\n')
                    else append("           (original) ").append(line.original).append('\n')
                }
            }
        }
    }

    // --- machine formats -----------------------------------------------------------------

    private fun csv(lines: List<LineView>, query: ExportQuery, flagged: Set<Long>, zone: TimeZone): String {
        val date = fmt("yyyy-MM-dd", zone)
        val clock = fmt("HH:mm:ss", zone)
        return buildString {
            append(CSV_HEADER).append('\n')
            for (line in lines) {
                val at = Date(line.segment.startTs)
                rows(line, query.content).forEach { (text, source) ->
                    append(csvField(date.format(at))).append(',')
                    append(csvField(clock.format(at))).append(',')
                    append(csvField(text)).append(',')
                    append(csvField(source)).append(',')
                    append(if (line.segment.id in flagged) "true" else "false").append('\n')
                }
            }
        }
    }

    private fun jsonl(lines: List<LineView>, query: ExportQuery, flagged: Set<Long>, zone: TimeZone): String {
        val iso = fmt("yyyy-MM-dd'T'HH:mm:ssXXX", zone)
        return buildString {
            for (line in lines) {
                rows(line, query.content).forEach { (text, source) ->
                    append('{')
                    append("\"at\":").append(jsonString(iso.format(Date(line.segment.startTs)))).append(',')
                    append("\"epochMs\":").append(line.segment.startTs).append(',')
                    append("\"text\":").append(jsonString(text)).append(',')
                    append("\"source\":").append(jsonString(source)).append(',')
                    append("\"flagged\":").append(line.segment.id in flagged)
                    append("}\n")
                }
            }
        }
    }

    /**
     * One line becomes one row, or two when "both" is asked for and the correction changed
     * something. Two rows rather than two columns, because a CSV with a fixed header is what
     * a spreadsheet can actually sort and filter.
     */
    private fun rows(line: LineView, content: String): List<Pair<String, String>> = when {
        content == ExportDefaults.CONTENT_ORIGINAL -> listOf(line.original to "original")
        content == ExportDefaults.CONTENT_BOTH && line.changed ->
            listOf(line.corrected to "corrected", line.original to "original")

        else -> listOf(line.corrected to if (line.changed) "corrected" else "original")
    }

    private fun primary(line: LineView, content: String): String =
        if (content == ExportDefaults.CONTENT_ORIGINAL) line.original else line.corrected

    /** RFC 4180: quote when it could be misread, and double any quote inside. */
    private fun csvField(value: String): String {
        val needsQuotes = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        val escaped = value.replace("\"", "\"\"")
        return if (needsQuotes) "\"$escaped\"" else escaped
    }

    private fun jsonString(value: String): String = buildString {
        append('"')
        for (c in value) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
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

    /** The title and the provenance line, roughly. */
    private const val HEADER_ALLOWANCE = 220L
}
