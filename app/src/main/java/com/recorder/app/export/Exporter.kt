package com.recorder.app.export

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.recorder.app.ServiceLocator
import com.recorder.app.ui.LineView
import com.recorder.core.storage.ExportDefaults
import com.recorder.core.storage.ExportDestinations
import com.recorder.core.storage.latestBySegment
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext


/**
 * Writes exports out of the app: to the Android share sheet, or to Download/Recorder/ where
 * any file manager can see them. Nothing is sent anywhere by the app itself — sharing hands
 * the file to whichever app the user picks.
 */
object Exporter {

    const val FOLDER = "Recorder"

    /** Lines for a time range, each with its newest correction. */
    suspend fun rawLinesInRange(fromTs: Long, toTs: Long): List<LineView> = withContext(Dispatchers.IO) {
        val db = ServiceLocator.database
        val segments = db.transcripts().inRange(fromTs, toTs)
        attachCorrections(segments.map { it.id }).let { latest -> segments.map { LineView(it, latest[it.id]) } }
    }

    suspend fun rawLinesForIds(ids: Collection<Long>): List<LineView> = withContext(Dispatchers.IO) {
        val segments = ids.chunked(SQL_VARS).flatMap { ServiceLocator.database.transcripts().byIds(it) }
            .sortedBy { it.startTs }
        attachCorrections(segments.map { it.id }).let { latest -> segments.map { LineView(it, latest[it.id]) } }
    }

    private suspend fun attachCorrections(ids: List<Long>) =
        ids.chunked(SQL_VARS)
            .flatMap { ServiceLocator.database.corrections().forSegments(it) }
            .sortedWith(compareBy({ it.createdTs }, { it.id }))
            .latestBySegment()

    /**
     * Every line the query selects, with its newest correction.
     *
     * The range comes from the query and is done in SQL; the time-of-day window and the
     * keywords are applied here, in memory. That is a scan, and deliberately: the FTS index
     * matches tokens, and these filters are substring, whole-line and case-insensitive with an
     * all-of/any-of choice and an exclusion list. Getting those right in one FTS expression is
     * how an export quietly ships the wrong lines. FTS still earns its place narrowing an
     * unbounded range before the scan, below.
     */
    suspend fun linesFor(query: ExportQuery, now: Long = System.currentTimeMillis()): List<LineView> =
        withContext(Dispatchers.IO) {
            val base = when {
                query.isSelection -> rawLinesForIds(query.onlyIds)
                else -> {
                    val bounds = query.bounds(now)
                    rawLinesInRange(bounds.first, bounds.last + 1)
                }
            }
            base.filter { query.accepts(it.segment.startTs, primaryText(it, query.content)) }
        }

    /** The count and rough size for the live estimate, without building the file. */
    suspend fun preview(query: ExportQuery, now: Long = System.currentTimeMillis()): Preview {
        val lines = linesFor(query, now)
        return Preview(lines.size, ExportFormatter.estimateBytes(lines, query))
    }

    data class Preview(val lines: Int, val bytes: Long) {
        /** "1.2 MB", "840 bytes" — the figure beside the match count. */
        val size: String
            get() = when {
                bytes < 1024 -> "$bytes bytes"
                bytes < 1024 * 1024 -> "${bytes / 1024} KB"
                else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
            }
    }

    /** Renders and delivers. Returns a message for the user. */
    suspend fun export(
        context: Context,
        title: String,
        query: ExportQuery,
        destination: String,
        now: Long = System.currentTimeMillis(),
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val lines = linesFor(query, now)
            require(lines.isNotEmpty()) { "Nothing matches those filters." }
            val flagged = flaggedAmong(lines.map { it.segment.id })
            val text = ExportFormatter.render(title, lines, query, flagged, now)
            val name = ExportFormatter.fileName(lines, query)
            val mime = ExportFormatter.mimeType(query.format)
            when (destination) {
                ExportDestinations.DOWNLOADS -> saveToDownloads(context, name, text, mime)
                ExportDestinations.CLIPBOARD -> copyToClipboard(context, text, lines.size)
                else -> share(context, name, text, mime)
            }
        }
    }

    private suspend fun copyToClipboard(context: Context, text: String, lines: Int): String {
        withContext(Dispatchers.Main) {
            context.getSystemService(android.content.ClipboardManager::class.java)
                ?.setPrimaryClip(android.content.ClipData.newPlainText("Recorder export", text))
        }
        return "Copied $lines line(s) to the clipboard"
    }

    private suspend fun flaggedAmong(ids: List<Long>): Set<Long> =
        ids.chunked(SQL_VARS)
            .flatMap { ServiceLocator.database.flagged().flaggedAmong(it) }
            .toSet()

    private fun primaryText(line: LineView, content: String): String =
        if (content == ExportDefaults.CONTENT_ORIGINAL) line.original else line.corrected

    private suspend fun share(context: Context, name: String, text: String, mime: String): String {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        // Old exports are only ever needed until the share completes.
        dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86_400_000L }?.forEach { it.delete() }
        val file = File(dir, name).apply { writeText(text) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            // text/plain is what most apps accept; the .md name still tells them it is Markdown.
            type = if (mime == "text/markdown") "text/plain" else mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, name)
            if (text.length < INLINE_TEXT_LIMIT) putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        withContext(Dispatchers.Main) {
            context.startActivity(
                Intent.createChooser(send, "Share $name").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        return "Choose where to send $name"
    }

    private fun saveToDownloads(context: Context, name: String, text: String, mime: String): String {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "Saving to Downloads needs Android 10 or newer. Use Share instead."
        }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Android refused to create the file in Downloads.")
        resolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } ?: error("Could not write the file.")
        resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        return "Saved to Download/$FOLDER/$name"
    }

    private const val SQL_VARS = 900
    private const val INLINE_TEXT_LIMIT = 60_000
}
