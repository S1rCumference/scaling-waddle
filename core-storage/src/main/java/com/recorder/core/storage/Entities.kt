package com.recorder.core.storage

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey

/** Where a segment's text came from. Kept as a string so new sources don't force a migration. */
object SegmentSource {
    const val MIC = "mic"
    const val IMPORT = "import"
    const val MANUAL = "manual"
}

@Entity(
    tableName = "transcript_segments",
    indices = [Index("start_ts"), Index("folder_id"), Index("heavy_processed"), Index("day_key")],
)
data class TranscriptSegment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "start_ts") val startTs: Long,
    @ColumnInfo(name = "end_ts") val endTs: Long,
    /** Exactly what speech recognition produced. Never rewritten; corrections live beside it. */
    @ColumnInfo(name = "text") val text: String,
    @ColumnInfo(name = "source") val source: String = SegmentSource.MIC,
    @ColumnInfo(name = "folder_id") val folderId: Long? = null,
    /** False until the Phase 4 heavy tier has looked at this row. */
    @ColumnInfo(name = "heavy_processed") val heavyProcessed: Boolean = false,
    /**
     * Local calendar day the segment was spoken on, as yyyymmdd. Stamped at write time so
     * grouping by day is an indexed GROUP BY rather than timezone arithmetic in SQL, and so
     * a day stays the day it was spoken even if the phone later changes timezone.
     */
    @ColumnInfo(name = "day_key", defaultValue = "0") val dayKey: Int = DayKey.of(startTs),
)

/** Which pass produced a correction. Strings, so new passes need no migration. */
object CorrectionPass {
    /** The small rolling batches that run every few minutes behind recording. */
    const val BATCH = "batch"

    /** The overnight re-run over a whole day, with the whole day as context. */
    const val END_OF_DAY = "end_of_day"

    /** Asked for by hand on one group. */
    const val MANUAL = "manual"

    fun label(pass: String): String = when (pass) {
        BATCH -> "live batch"
        END_OF_DAY -> "end-of-day pass"
        MANUAL -> "re-run by hand"
        else -> pass
    }
}

/**
 * One corrected version of one segment. Append-only: a later pass adds a row rather than
 * editing an earlier one, and the original text in [TranscriptSegment] is never touched.
 * The newest row for a segment is the one shown.
 */
@Entity(
    tableName = "segment_corrections",
    indices = [Index("segment_id"), Index("created_ts")],
)
data class SegmentCorrection(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "segment_id") val segmentId: Long,
    @ColumnInfo(name = "text") val text: String,
    @ColumnInfo(name = "pass") val pass: String,
    /** Human-readable name of the model that produced it, e.g. "Qwen 3 4B (on this phone)". */
    @ColumnInfo(name = "engine") val engine: String,
    @ColumnInfo(name = "created_ts") val createdTs: Long = System.currentTimeMillis(),
    /**
     * Phrases the draft pass marked and the repair pass could not settle, unit-separated.
     *
     * Stored rather than stripped and forgotten. [text] is the clean version, because that
     * is what gets read and exported, but knowing which words were guessed at is what lets
     * the review surface point at the lines worth checking instead of all of them.
     */
    @ColumnInfo(name = "uncertain", defaultValue = "") val uncertain: String = "",
) {
    /** True when the pass looked at the segment and left it as it was. */
    fun unchangedFrom(original: String): Boolean = text.trim() == original.trim()

    val uncertainPhrases: List<String>
        get() = uncertain.split('\u001f').filter { it.isNotBlank() }
}

/** Bookkeeping for the end-of-day pass, so a day is only re-run when it has new speech. */
@Entity(tableName = "day_passes")
data class DayPass(
    @PrimaryKey @ColumnInfo(name = "day_key") val dayKey: Int,
    /** Start of the newest segment the last run covered. */
    @ColumnInfo(name = "covered_until_ts") val coveredUntilTs: Long,
    @ColumnInfo(name = "completed_ts") val completedTs: Long,
    @ColumnInfo(name = "engine") val engine: String,
)

/**
 * Content-backed FTS mirror of [TranscriptSegment.text]. Room keeps it in sync with the
 * content table, so the RAG search in core-llm can query it without a second write path.
 */
@Fts4(contentEntity = TranscriptSegment::class)
@Entity(tableName = "transcript_segments_fts")
data class TranscriptSegmentFts(
    @ColumnInfo(name = "text") val text: String,
)

@Entity(tableName = "folders", indices = [Index(value = ["name"], unique = true)])
data class Folder(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "created_ts") val createdTs: Long = System.currentTimeMillis(),
    /** True when a human made this folder, so the heavy tier won't rename or merge it away. */
    @ColumnInfo(name = "user_created") val userCreated: Boolean = false,
)

@Entity(tableName = "flagged_items", indices = [Index("segment_id"), Index("created_ts")])
data class FlaggedItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "segment_id") val segmentId: Long,
    @ColumnInfo(name = "keyword") val keyword: String,
    @ColumnInfo(name = "created_ts") val createdTs: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "dismissed") val dismissed: Boolean = false,
)

object PendingActionStatus {
    const val DRAFT = "draft"
    const val APPROVED = "approved"
    const val SENT = "sent"
    const val REJECTED = "rejected"
    const val FAILED = "failed"
}

/**
 * Anything the heavy tier wants to do in the outside world lands here first.
 * Nothing in this app sends without a row moving to [PendingActionStatus.APPROVED] by hand.
 */
@Entity(tableName = "pending_actions", indices = [Index("status"), Index("created_ts")])
data class PendingAction(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "connector") val connector: String,
    @ColumnInfo(name = "tool") val tool: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "preview") val preview: String,
    @ColumnInfo(name = "payload_json") val payloadJson: String,
    @ColumnInfo(name = "status") val status: String = PendingActionStatus.DRAFT,
    @ColumnInfo(name = "created_ts") val createdTs: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "resolved_ts") val resolvedTs: Long? = null,
    @ColumnInfo(name = "error") val error: String? = null,
)
