package com.recorder.core.storage

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * One thing the AI understood, as a discrete reviewable item.
 *
 * This is what replaced "here is your corrected transcript" as the output people actually
 * read. A wall of lightly-fixed text is not reviewable — nobody proofreads an hour of their
 * own speech — but a dozen statements each of which can be marked wrong, edited or shared
 * is. The uncertainty the draft pass admitted to travels with the item, so the ones worth
 * looking at first announce themselves.
 */
@Entity(
    tableName = "summary_items",
    indices = [Index("from_ts"), Index("created_ts")],
)
data class SummaryItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** The group this came from, so a group can find its own items. */
    @androidx.room.ColumnInfo(name = "from_ts") val fromTs: Long,
    @androidx.room.ColumnInfo(name = "to_ts") val toTs: Long,
    /** What the AI wrote. Never overwritten by an edit; [edited] holds that. */
    val text: String,
    /** The user's replacement, when they corrected it in place. */
    val edited: String? = null,
    /** Marked wrong by the user. Kept rather than deleted: it is evidence for later passes. */
    @androidx.room.ColumnInfo(name = "flagged_wrong") val flaggedWrong: Boolean = false,
    /** Phrases the model never resolved, comma-joined. Empty when it was confident. */
    val uncertain: String = "",
    @androidx.room.ColumnInfo(name = "created_ts") val createdTs: Long = System.currentTimeMillis(),
) {
    /** What to show and share: the user's version when there is one. */
    val display: String get() = edited?.takeIf { it.isNotBlank() } ?: text

    val uncertainPhrases: List<String>
        get() = uncertain.split('\u001f').filter { it.isNotBlank() }
}

/**
 * Something the AI got wrong and the user fixed, kept so it is not got wrong again.
 *
 * Deliberately a list of pairs rather than anything cleverer. It is fed back into later
 * prompts as "you have been corrected on these before", which is the cheapest thing that
 * works on device and needs no training of any kind. Capped, because a prompt that grows
 * without limit eventually costs more than the mistakes it prevents.
 */
@Entity(tableName = "user_corrections", indices = [Index("created_ts")])
data class UserCorrection(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** What the AI said. */
    val wrong: String,
    /**
     * What it should have said, or empty when the user only marked it wrong.
     *
     * Named "corrected" rather than "right" because RIGHT is a SQL keyword — SQLite has
     * understood RIGHT JOIN since 3.39, and an unquoted column of that name is a parse
     * error waiting for a query that does not quote it.
     */
    val corrected: String,
    @androidx.room.ColumnInfo(name = "created_ts") val createdTs: Long = System.currentTimeMillis(),
)

@Dao
interface ReviewDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItems(items: List<SummaryItem>)

    @Query("SELECT * FROM summary_items WHERE from_ts >= :fromTs AND to_ts <= :toTs ORDER BY created_ts ASC, id ASC")
    fun itemsIn(fromTs: Long, toTs: Long): Flow<List<SummaryItem>>

    @Query("DELETE FROM summary_items WHERE from_ts >= :fromTs AND to_ts <= :toTs")
    suspend fun clearIn(fromTs: Long, toTs: Long)

    /**
     * Drops every summary whose span contains [ts] — what a deleted transcript line needs,
     * because a summary of text that no longer exists is worse than no summary.
     */
    @Query("DELETE FROM summary_items WHERE from_ts <= :ts AND to_ts > :ts")
    suspend fun clearCovering(ts: Long)

    @Query("UPDATE summary_items SET edited = :text WHERE id = :id")
    suspend fun edit(id: Long, text: String?)

    @Query("UPDATE summary_items SET flagged_wrong = :wrong WHERE id = :id")
    suspend fun flag(id: Long, wrong: Boolean)

    @Query("SELECT * FROM summary_items WHERE id = :id")
    suspend fun item(id: Long): SummaryItem?

    // --- what the user taught it ---------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun remember(correction: UserCorrection)

    @Query("SELECT * FROM user_corrections ORDER BY created_ts DESC")
    fun corrections(): Flow<List<UserCorrection>>

    /** Newest first, for the prompt. The cap is applied by the caller. */
    @Query("SELECT * FROM user_corrections ORDER BY created_ts DESC LIMIT :limit")
    suspend fun recentCorrections(limit: Int): List<UserCorrection>

    @Query("DELETE FROM user_corrections WHERE id = :id")
    suspend fun forget(id: Long)

    @Query("DELETE FROM user_corrections")
    suspend fun forgetAll()

    /** Keeps the newest [keep] and drops the rest, so the list cannot grow without limit. */
    @Query("DELETE FROM user_corrections WHERE id NOT IN (SELECT id FROM user_corrections ORDER BY created_ts DESC LIMIT :keep)")
    suspend fun trimTo(keep: Int)
}
