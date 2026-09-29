package com.recorder.core.storage

/**
 * The backup file format: one JSON object per line, one file per day.
 *
 * The phone has no Google account and backups are switched off in the manifest, so the
 * database is otherwise the only copy of everything ever recorded. These files are the second
 * copy, in Download/Recorder/Backup where a file manager, a USB cable or a new phone can reach
 * them. JSON Lines because it is readable by anything, appends cleanly, and a damaged line
 * costs one line rather than the file.
 *
 * Written and read here, by hand, so the round trip is a plain unit test and does not depend
 * on Android's org.json.
 */
object BackupCodec {

    const val VERSION = 1

    fun fileName(dayKey: Int): String {
        val y = dayKey / 10_000
        val m = dayKey / 100 % 100
        val d = dayKey % 100
        return "recorder-%04d-%02d-%02d.jsonl".format(y, m, d)
    }

    fun encode(segments: List<TranscriptSegment>): String = buildString {
        for (s in segments) {
            append("{\"v\":").append(VERSION)
            append(",\"start\":").append(s.startTs)
            append(",\"end\":").append(s.endTs)
            append(",\"day\":").append(s.dayKey)
            append(",\"source\":").append(quote(s.source))
            append(",\"db\":").append(s.levelDb)
            append(",\"zcr\":").append(s.zeroCrossingRate)
            append(",\"text\":").append(quote(s.text))
            append("}\n")
        }
    }

    /**
     * Every readable line in [text], as new rows (id 0). Lines that are damaged, or are not
     * this format, are skipped and counted, never guessed at.
     */
    fun decode(text: String): Decoded {
        val rows = mutableListOf<TranscriptSegment>()
        var skipped = 0
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val row = runCatching { parseFlat(line) }.getOrNull()?.let(::toSegment)
            if (row == null) skipped++ else rows += row
        }
        return Decoded(rows, skipped)
    }

    data class Decoded(val segments: List<TranscriptSegment>, val skipped: Int)

    private fun toSegment(f: Map<String, String>): TranscriptSegment? {
        val start = f["start"]?.toLongOrNull() ?: return null
        val text = f["text"] ?: return null
        val end = f["end"]?.toLongOrNull() ?: start
        return TranscriptSegment(
            startTs = start,
            endTs = end,
            text = text,
            source = f["source"] ?: SegmentSource.MIC,
            dayKey = f["day"]?.toIntOrNull() ?: DayKey.of(start),
            levelDb = f["db"]?.toFloatOrNull() ?: 0f,
            zeroCrossingRate = f["zcr"]?.toFloatOrNull() ?: 0f,
        )
    }

    internal fun quote(value: String): String = buildString {
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

    /** A flat JSON object of strings, numbers and booleans — the only shape [encode] writes. */
    internal fun parseFlat(line: String): Map<String, String> {
        val out = HashMap<String, String>()
        var i = 0
        fun ws() { while (i < line.length && line[i].isWhitespace()) i++ }
        fun expect(c: Char) { ws(); require(i < line.length && line[i] == c) { "expected $c at $i" }; i++ }
        fun string(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                require(i < line.length) { "unterminated string" }
                val c = line[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        require(i < line.length) { "dangling escape" }
                        when (val e = line[i++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000c')
                            'u' -> {
                                require(i + 4 <= line.length) { "short \\u escape" }
                                sb.append(line.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> throw IllegalArgumentException("bad escape \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }
        fun bare(): String {
            val from = i
            while (i < line.length && line[i] != ',' && line[i] != '}' && !line[i].isWhitespace()) i++
            require(i > from) { "empty value at $from" }
            return line.substring(from, i)
        }
        expect('{')
        ws()
        if (i < line.length && line[i] == '}') return out
        while (true) {
            val key = string()
            expect(':')
            ws()
            out[key] = if (i < line.length && line[i] == '"') string() else bare()
            ws()
            require(i < line.length) { "unterminated object" }
            if (line[i] == ',') { i++; continue }
            expect('}')
            break
        }
        ws()
        require(i == line.length) { "trailing characters" }
        return out
    }
}
