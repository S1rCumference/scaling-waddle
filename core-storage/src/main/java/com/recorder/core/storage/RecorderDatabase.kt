package com.recorder.core.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        TranscriptSegment::class,
        TranscriptSegmentFts::class,
        Folder::class,
        FlaggedItem::class,
        PendingAction::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class RecorderDatabase : RoomDatabase() {
    abstract fun transcripts(): TranscriptDao
    abstract fun flagged(): FlaggedItemDao
    abstract fun folders(): FolderDao
    abstract fun pendingActions(): PendingActionDao

    companion object {
        private const val NAME = "recorder.db"

        @Volatile
        private var instance: RecorderDatabase? = null

        fun get(context: Context): RecorderDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    RecorderDatabase::class.java,
                    NAME,
                ).build().also { instance = it }
            }
    }
}

/** Escapes user text into a safe SQLite FTS MATCH expression. */
object FtsQuery {
    private val tokenPattern = Regex("[\\p{L}\\p{N}']+")

    fun sanitize(raw: String): String =
        tokenPattern.findAll(raw)
            .map { it.value }
            .filter { it.length > 1 }
            .joinToString(" OR ") { "\"$it\"" }
}
