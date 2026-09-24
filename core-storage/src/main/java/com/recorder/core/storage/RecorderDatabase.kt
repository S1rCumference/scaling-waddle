package com.recorder.core.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        TranscriptSegment::class,
        TranscriptSegmentFts::class,
        Folder::class,
        FlaggedItem::class,
        PendingAction::class,
        SegmentCorrection::class,
        DayPass::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class RecorderDatabase : RoomDatabase() {
    abstract fun transcripts(): TranscriptDao
    abstract fun flagged(): FlaggedItemDao
    abstract fun folders(): FolderDao
    abstract fun pendingActions(): PendingActionDao
    abstract fun corrections(): CorrectionDao
    abstract fun dayPasses(): DayPassDao

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
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }

        /**
         * 1 → 2 adds the day column and the correction tables. Existing rows get their day
         * from SQLite's own localtime, which is what the app would have stamped at the time
         * for anyone who has not changed timezone since. The original text is not touched.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transcript_segments ADD COLUMN day_key INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "UPDATE transcript_segments SET day_key = CAST(strftime('%Y%m%d', start_ts / 1000, " +
                        "'unixepoch', 'localtime') AS INTEGER)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_transcript_segments_day_key ON transcript_segments (day_key)")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS segment_corrections (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "segment_id INTEGER NOT NULL, " +
                        "text TEXT NOT NULL, " +
                        "pass TEXT NOT NULL, " +
                        "engine TEXT NOT NULL, " +
                        "created_ts INTEGER NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_segment_corrections_segment_id ON segment_corrections (segment_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_segment_corrections_created_ts ON segment_corrections (created_ts)")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS day_passes (" +
                        "day_key INTEGER NOT NULL, " +
                        "covered_until_ts INTEGER NOT NULL, " +
                        "completed_ts INTEGER NOT NULL, " +
                        "engine TEXT NOT NULL, " +
                        "PRIMARY KEY(day_key))",
                )
            }
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
