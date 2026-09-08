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
    indices = [Index("start_ts"), Index("folder_id"), Index("heavy_processed")],
)
data class TranscriptSegment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "start_ts") val startTs: Long,
    @ColumnInfo(name = "end_ts") val endTs: Long,
    @ColumnInfo(name = "text") val text: String,
    @ColumnInfo(name = "source") val source: String = SegmentSource.MIC,
    @ColumnInfo(name = "folder_id") val folderId: Long? = null,
    /** False until the Phase 4 heavy tier has looked at this row. */
    @ColumnInfo(name = "heavy_processed") val heavyProcessed: Boolean = false,
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
