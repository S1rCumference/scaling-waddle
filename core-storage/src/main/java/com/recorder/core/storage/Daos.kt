package com.recorder.core.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

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

    @Query("DELETE FROM transcript_segments WHERE start_ts < :beforeTs")
    suspend fun deleteOlderThan(beforeTs: Long): Int
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
