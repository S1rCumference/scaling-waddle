package com.recorder.app.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.room.withTransaction
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.recorder.app.ServiceLocator
import com.recorder.core.storage.BackupCodec
import com.recorder.core.storage.Diagnostics
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * A second copy of every transcript, as one file per day in Download/Recorder/Backup.
 *
 * The phone has no Google account and Android's own backup is off, so without this the app's
 * database is the only copy of everything it has ever heard, and uninstalling or a corrupted
 * database would take all of it. Files in Downloads survive an uninstall and can be copied off
 * with a cable or a file manager, and Restore reads them back.
 *
 * Only days that changed are rewritten: days with new lines since the last run, and days where
 * something was deleted — so a deleted line does not live on in the backup. A day with nothing
 * left has its file removed. One small write a day; nothing here runs while recording.
 */
object Backups {

    private const val TAG = "Backups"
    const val FOLDER = "Recorder/Backup"
    private val lock = Mutex()

    data class Outcome(val daysWritten: Int, val message: String)

    /** Writes whatever changed. [force] runs it even with the daily backup switched off. */
    suspend fun run(context: Context, force: Boolean = false): Outcome = lock.withLock {
        val settings = ServiceLocator.settings
        if (!force && !settings.backupEnabled.first()) return@withLock Outcome(0, "Backup is off")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val why = "Backups to Downloads need Android 10 or newer"
            settings.setBackupProblem(why)
            return@withLock Outcome(0, why)
        }
        val dao = ServiceLocator.database.transcripts()
        runCatching {
            withContext(Dispatchers.IO) {
                val top = dao.maxId()
                val days = (dao.dayKeysAfterId(settings.backupWatermark.first()) +
                    settings.backupDirtyDays.first()).toSortedSet()
                for (day in days) {
                    val rows = dao.forDay(day)
                    val name = BackupCodec.fileName(day)
                    if (rows.isEmpty()) delete(context, name) else write(context, name, BackupCodec.encode(rows))
                }
                settings.recordBackup(top, days, problem = "")
                days.size
            }
        }.fold(
            onSuccess = { n ->
                if (n > 0) Diagnostics.i(TAG, "backed up $n day(s)")
                Outcome(n, if (n == 0) "Backup is up to date" else "Backed up $n day(s) to Download/$FOLDER")
            },
            onFailure = { e ->
                Diagnostics.w(TAG, "backup failed", e)
                val why = e.message ?: e.javaClass.simpleName
                settings.setBackupProblem(why)
                Outcome(0, "Backup failed: $why")
            },
        )
    }

    data class Restored(val added: Int, val alreadyThere: Int, val unreadable: Int)

    /**
     * Reads backup files the user picked and adds any line not already present. Matching is on
     * start time and text, so restoring the same file twice, or into a phone that still has
     * those lines, adds nothing.
     */
    suspend fun restore(context: Context, uris: List<Uri>): Restored = withContext(Dispatchers.IO) {
        val db = ServiceLocator.database
        var added = 0
        var present = 0
        var unreadable = 0
        val days = mutableSetOf<Int>()
        for (uri in uris) {
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
            }.getOrNull()
            if (text == null) {
                unreadable++
                continue
            }
            val decoded = BackupCodec.decode(text)
            unreadable += decoded.skipped
            db.withTransaction {
                for (row in decoded.segments) {
                    if (db.transcripts().countMatching(row.startTs, row.text) > 0) {
                        present++
                    } else {
                        db.transcripts().insert(row)
                        days += row.dayKey
                        added++
                    }
                }
            }
        }
        ServiceLocator.settings.markBackupDirty(days)
        Diagnostics.i(TAG, "restore: $added added, $present already there, $unreadable unreadable")
        Restored(added, present, unreadable)
    }

    private val relativePath get() = "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER/"

    /**
     * This app's own earlier file of that name, if MediaStore still credits it to us. After a
     * reinstall it will not, and a new file is created beside the old one instead — Restore
     * matches lines rather than files, so duplicates cost nothing.
     */
    private fun find(context: Context, name: String): Uri? {
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.Downloads._ID),
            "${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.Downloads.RELATIVE_PATH} = ?",
            arrayOf(name, relativePath),
            null,
        )?.use { c ->
            if (c.moveToFirst()) return ContentUris.withAppendedId(collection, c.getLong(0))
        }
        return null
    }

    private fun write(context: Context, name: String, text: String) {
        val resolver = context.contentResolver
        find(context, name)?.let { existing ->
            resolver.openOutputStream(existing, "wt")?.use { it.write(text.toByteArray()) }
                ?: error("could not open $name")
            return
        }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/x-ndjson")
            put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Android refused to create $name in Downloads")
        resolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } ?: error("could not write $name")
        resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
    }

    private fun delete(context: Context, name: String) {
        find(context, name)?.let { context.contentResolver.delete(it, null, null) }
    }
}

/** Once a day, whatever changed. Tiny: a few files of text. */
class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Backups.run(applicationContext)
        return Result.success()
    }

    companion object {
        private const val UNIQUE = "daily-backup"

        fun ensureScheduled(context: Context) {
            val request = PeriodicWorkRequestBuilder<BackupWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiresStorageNotLow(true).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
