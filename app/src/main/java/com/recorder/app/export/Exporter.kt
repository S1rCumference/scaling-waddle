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
import com.recorder.core.storage.latestBySegment
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Where an export goes. */
enum class ExportTarget { SHARE, DOWNLOADS }

/**
 * Writes exports out of the app: to the Android share sheet, or to Download/Recorder/ where
 * any file manager can see them. Nothing is sent anywhere by the app itself — sharing hands
 * the file to whichever app the user picks.
 */
object Exporter {

    const val FOLDER = "Recorder"

    /** Lines for a time range, each with its newest correction. */
    suspend fun linesFor(fromTs: Long, toTs: Long): List<LineView> = withContext(Dispatchers.IO) {
        val db = ServiceLocator.database
        val segments = db.transcripts().inRange(fromTs, toTs)
        attachCorrections(segments.map { it.id }).let { latest -> segments.map { LineView(it, latest[it.id]) } }
    }

    suspend fun linesForIds(ids: Collection<Long>): List<LineView> = withContext(Dispatchers.IO) {
        val segments = ids.chunked(SQL_VARS).flatMap { ServiceLocator.database.transcripts().byIds(it) }
            .sortedBy { it.startTs }
        attachCorrections(segments.map { it.id }).let { latest -> segments.map { LineView(it, latest[it.id]) } }
    }

    private suspend fun attachCorrections(ids: List<Long>) =
        ids.chunked(SQL_VARS)
            .flatMap { ServiceLocator.database.corrections().forSegments(it) }
            .sortedWith(compareBy({ it.createdTs }, { it.id }))
            .latestBySegment()

    /** Renders and delivers. Returns a message for the user. */
    suspend fun export(
        context: Context,
        title: String,
        lines: List<LineView>,
        content: String,
        format: String,
        target: ExportTarget,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(lines.isNotEmpty()) { "Nothing to export in that selection." }
            val text = ExportFormatter.render(title, lines, content, format)
            val name = ExportFormatter.fileName(title, format)
            val mime = if (format == ExportDefaults.FORMAT_MARKDOWN) "text/markdown" else "text/plain"
            when (target) {
                ExportTarget.SHARE -> share(context, name, text, mime)
                ExportTarget.DOWNLOADS -> saveToDownloads(context, name, text, mime)
            }
        }
    }

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
