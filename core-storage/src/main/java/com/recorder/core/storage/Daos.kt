package com.recorder.core.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** One calendar day of transcript, as the Logs list shows it. */
data class DaySummary(
    val dayKey: Int,
    val count: Int,
    val firstTs: Long,
    val lastTs: Long,
)

/**
 * One bucket of an hourly grouping. [bucket] is hours since the epoch in the zone offset the
 * query was run with; callers use [firstTs] for labels rather than doing arithmetic on it.
 */
data class HourSummary(
    val bucket: Long,
    val count: Int,
    val firstTs: Long,
    val lastTs: Long,
)

@Dao
interface TranscriptDao {
    @Insert
    suspend fun insert(segment: TranscriptSegment): Long

    @Query("SELECT * FROM transcript_segments ORDER BY start_ts DESC LIMIT :limit")
    fun recent(limit: Int = 200): Flow<List<TranscriptSegment>>

    @Query("SELECT * FROM transcript_segments WHERE start_ts >= :sinceTs ORDER BY start_ts ASC")
    suspend fun since(sinceTs: Long): List<TranscriptSegment>

    @Query("SELECT * FROM transcript_segments WHERE id = :id")
    suspend fun byId(id: Long): TranscriptSegment?

    @Query("SELECT * FROM transcript_segments WHERE folder_id = :folderId ORDER BY start_ts DESC")
    fun byFolder(folderId: Long): Flow<List<TranscriptSegment>>

    @Query("SELECT * FROM transcript_segments WHERE heavy_processed = 0 ORDER BY start_ts ASC LIMIT :limit")
    suspend fun unprocessedByHeavyTier(limit: Int): List<TranscriptSegment>

    @Query("UPDATE transcript_segments SET heavy_processed = 1 WHERE id IN (:ids)")
    suspend fun markHeavyProcessed(ids: List<Long>)

    @Query("UPDATE transcript_segments SET folder_id = :folderId WHERE id = :id")
    suspend fun assignFolder(id: Long, folderId: Long?)

    /**
     * Segments no folder has claimed yet. Filing these is a batch job: running a language
     * model once per segment, inline with recording, was costing a model call for every
     * sentence spoken.
     */
    @Query("SELECT * FROM transcript_segments WHERE folder_id IS NULL ORDER BY start_ts ASC LIMIT :limit")
    suspend fun unfiled(limit: Int): List<TranscriptSegment>

    @Query("SELECT COUNT(*) FROM transcript_segments WHERE folder_id IS NULL")
    suspend fun unfiledCount(): Int

    /**
     * FTS match against the transcript text. [query] is an SQLite FTS MATCH expression,
     * so callers must sanitise user input through [FtsQuery.sanitize] first.
     */
    @Query(
        """
        SELECT s.* FROM transcript_segments AS s
        JOIN transcript_segments_fts AS f ON f.rowid = s.id
        WHERE transcript_segments_fts MATCH :query
        ORDER BY s.start_ts DESC
        LIMIT :limit
        """
    )
    suspend fun search(query: String, limit: Int = 40): List<TranscriptSegment>

    @Query("SELECT COUNT(*) FROM transcript_segments")
    suspend fun count(): Int

    @Query(
        """
        SELECT day_key AS dayKey, COUNT(*) AS count, MIN(start_ts) AS firstTs, MAX(end_ts) AS lastTs
        FROM transcript_segments
        GROUP BY day_key
        ORDER BY day_key DESC
        """
    )
    fun daySummaries(): Flow<List<DaySummary>>

    /**
     * Hour buckets between [fromTs] and [toTs]. [offsetMs] is the local zone offset, so
     * buckets fall on local hour boundaries even in half-hour timezones.
     */
    @Query(
        """
        SELECT (start_ts + :offsetMs) / 3600000 AS bucket, COUNT(*) AS count,
               MIN(start_ts) AS firstTs, MAX(end_ts) AS lastTs
        FROM transcript_segments
        WHERE start_ts >= :fromTs AND start_ts < :toTs
        GROUP BY bucket
        ORDER BY bucket DESC
        """
    )
    fun hourSummaries(fromTs: Long, toTs: Long, offsetMs: Long): Flow<List<HourSummary>>

    @Query("SELECT * FROM transcript_segments WHERE start_ts >= :fromTs AND start_ts < :toTs ORDER BY start_ts ASC")
    fun inRangeFlow(fromTs: Long, toTs: Long): Flow<List<TranscriptSegment>>

    @Query("SELECT * FROM transcript_segments WHERE start_ts >= :fromTs AND start_ts < :toTs ORDER BY start_ts ASC")
    suspend fun inRange(fromTs: Long, toTs: Long): List<TranscriptSegment>

    @Query("SELECT * FROM transcript_segments WHERE id IN (:ids) ORDER BY start_ts ASC")
    suspend fun byIds(ids: List<Long>): List<TranscriptSegment>

    /** The [limit] segments just before [ts], newest first — context for a correction window. */
    @Query("SELECT * FROM transcript_segments WHERE start_ts < :ts ORDER BY start_ts DESC LIMIT :limit")
    suspend fun before(ts: Long, limit: Int): List<TranscriptSegment>

    @Query("SELECT * FROM transcript_segments WHERE start_ts > :ts ORDER BY start_ts ASC LIMIT :limit")
    suspend fun after(ts: Long, limit: Int): List<TranscriptSegment>

    /** FTS match restricted to a time range, for questions scoped to one group. */
    @Query(
        """
        SELECT s.* FROM transcript_segments AS s
        JOIN transcript_segments_fts AS f ON f.rowid = s.id
        WHERE transcript_segments_fts MATCH :query AND s.start_ts >= :fromTs AND s.start_ts < :toTs
        ORDER BY s.start_ts ASC
        LIMIT :limit
        """
    )
    suspend fun searchInRange(query: String, fromTs: Long, toTs: Long, limit: Int = 40): List<TranscriptSegment>

    /** Recent segments no correction pass has looked at yet, oldest first. */
    @Query(
        """
        SELECT * FROM transcript_segments
        WHERE start_ts >= :sinceTs
          AND id NOT IN (SELECT segment_id FROM segment_corrections)
        ORDER BY start_ts ASC
        LIMIT :limit
        """
    )
    suspend fun uncorrected(sinceTs: Long, limit: Int): List<TranscriptSegment>

    /** How much work is waiting, for the backlog figure and the "process now" control. */
    @Query(
        """
        SELECT COUNT(*) FROM transcript_segments
        WHERE start_ts >= :sinceTs
          AND id NOT IN (SELECT segment_id FROM segment_corrections)
        """
    )
    fun uncorrectedCount(sinceTs: Long): Flow<Int>

    @Query("SELECT MAX(start_ts) FROM transcript_segments WHERE day_key = :dayKey")
    suspend fun lastStartOnDay(dayKey: Int): Long?

    @Query("SELECT * FROM transcript_segments WHERE day_key = :dayKey ORDER BY start_ts ASC")
    suspend fun onDay(dayKey: Int): List<TranscriptSegment>

    @Query("DELETE FROM transcript_segments WHERE start_ts < :beforeTs")
    suspend fun deleteOlderThan(beforeTs: Long): Int

    /**
     * Removes chosen lines outright. Returns how many rows went, which is not always
     * [ids].size: a line can be deleted twice if two screens are looking at the same group.
     *
     * The FTS index follows on its own — `transcript_segments_fts` is an external-content
     * table, so Room's generated sync triggers delete the matching row. The corrections and
     * flags that point at these segments do not: there is no foreign key, so a caller has to
     * clear those too, which is what [com.recorder.app.data.Deletions] is for.
     */
    @Query("DELETE FROM transcript_segments WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>): Int

    /**
     * Puts back rows that were just deleted, ids and all.
     *
     * REPLACE rather than ABORT so an undo pressed twice is harmless. Keeping the original id
     * is the whole point: the corrections and flags restored alongside still point at it.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restore(segments: List<TranscriptSegment>)
}

@Dao
interface FlaggedItemDao {
    @Insert
    suspend fun insert(item: FlaggedItem): Long

    @Query(
        """
        SELECT * FROM flagged_items
        WHERE dismissed = 0
        ORDER BY created_ts DESC
        LIMIT :limit
        """
    )
    fun active(limit: Int = 100): Flow<List<FlaggedItem>>

    @Query("UPDATE flagged_items SET dismissed = 1 WHERE id = :id")
    suspend fun dismiss(id: Long)

    /** Which of [segmentIds] were ever flagged, for the export's flagged column. */
    @Query("SELECT DISTINCT segment_id FROM flagged_items WHERE segment_id IN (:segmentIds)")
    suspend fun flaggedAmong(segmentIds: List<Long>): List<Long>

    @Insert
    suspend fun insertAll(items: List<FlaggedItem>): List<Long>

    @Query("SELECT * FROM flagged_items WHERE segment_id IN (:segmentIds)")
    suspend fun forSegments(segmentIds: List<Long>): List<FlaggedItem>

    /** Drops the flags of deleted lines, so Flags cannot point at text that is gone. */
    @Query("DELETE FROM flagged_items WHERE segment_id IN (:segmentIds)")
    suspend fun deleteForSegments(segmentIds: List<Long>): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restore(items: List<FlaggedItem>)
}

@Dao
interface CorrectionDao {
    @Insert
    suspend fun insertAll(corrections: List<SegmentCorrection>)

    /** Every correction row for segments in a time range, all passes, oldest first. */
    @Query(
        """
        SELECT c.* FROM segment_corrections AS c
        JOIN transcript_segments AS s ON s.id = c.segment_id
        WHERE s.start_ts >= :fromTs AND s.start_ts < :toTs
        ORDER BY c.created_ts ASC, c.id ASC
        """
    )
    fun inRangeFlow(fromTs: Long, toTs: Long): Flow<List<SegmentCorrection>>

    @Query("SELECT * FROM segment_corrections WHERE segment_id IN (:segmentIds) ORDER BY created_ts ASC, id ASC")
    suspend fun forSegments(segmentIds: List<Long>): List<SegmentCorrection>

    /** Drops the corrections of deleted lines. Nothing can reach them once the line is gone. */
    @Query("DELETE FROM segment_corrections WHERE segment_id IN (:segmentIds)")
    suspend fun deleteForSegments(segmentIds: List<Long>): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restore(corrections: List<SegmentCorrection>)
}

/** Keeps only the newest correction per segment. Input must be oldest first. */
fun List<SegmentCorrection>.latestBySegment(): Map<Long, SegmentCorrection> =
    associateBy { it.segmentId }

@Dao
interface DayPassDao {
    @Query("SELECT * FROM day_passes WHERE day_key = :dayKey")
    suspend fun get(dayKey: Int): DayPass?

    @Upsert
    suspend fun upsert(pass: DayPass)
}

@Dao
interface FolderDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(folder: Folder): Long

    @Query("SELECT * FROM folders ORDER BY name ASC")
    fun all(): Flow<List<Folder>>

    @Query("SELECT * FROM folders ORDER BY name ASC")
    suspend fun allOnce(): List<Folder>

    @Query("SELECT * FROM folders WHERE name = :name LIMIT 1")
    suspend fun byName(name: String): Folder?
}

/** Returns the id of an existing folder with this name, creating it if absent. */
suspend fun FolderDao.ensure(name: String, userCreated: Boolean = false): Long {
    byName(name)?.let { return it.id }
    val id = insert(Folder(name = name, userCreated = userCreated))
    return if (id > 0) id else byName(name)?.id ?: 0L
}

@Dao
interface PendingActionDao {
    @Insert
    suspend fun insert(action: PendingAction): Long

    @Update
    suspend fun update(action: PendingAction)

    @Query("SELECT * FROM pending_actions WHERE status = :status ORDER BY created_ts DESC")
    fun byStatus(status: String = PendingActionStatus.DRAFT): Flow<List<PendingAction>>

    @Query("SELECT * FROM pending_actions WHERE id = :id")
    suspend fun byId(id: Long): PendingAction?

    @Query("SELECT * FROM pending_actions WHERE status = :status ORDER BY created_ts ASC")
    suspend fun byStatusOnce(status: String): List<PendingAction>
}
