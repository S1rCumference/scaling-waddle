package com.recorder.app.data

import androidx.room.withTransaction
import com.recorder.app.ServiceLocator
import com.recorder.core.storage.Diagnostics
import com.recorder.core.storage.FlaggedItem
import com.recorder.core.storage.SegmentCorrection
import com.recorder.core.storage.TranscriptSegment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Deleting transcript lines, from either screen, with one undo.
 *
 * The app over-captures on purpose — that is the whole design — so the way out of a recording
 * you did not want is to throw the line away afterwards. A line is deleted for real: there is
 * no bin, no hidden flag, no "deleted" column that a later query forgets to filter on. That is
 * the only version of "delete it" worth offering for something recorded in a room you were in.
 *
 * Three tables have to agree, because there are no foreign keys between them: the segment, its
 * corrections, and any keyword flags pointing at it. Doing that in one transaction in one place
 * is why this exists rather than a DAO call at each call site.
 *
 * Undo holds the removed rows in memory only. It survives a fold (this is process-wide, like
 * the rest of the shared UI state) and does not survive the process being killed, which is the
 * honest bound: a swipe you want back, you want back within seconds, and promising more would
 * mean not really deleting anything.
 */
object Deletions {

    private const val TAG = "Deletions"

    /** Everything one delete removed, held so it can be put back exactly as it was. */
    data class Undo(
        val segments: List<TranscriptSegment>,
        val corrections: List<SegmentCorrection>,
        val flags: List<FlaggedItem>,
    ) {
        val size: Int get() = segments.size
    }

    private val lock = Mutex()
    private val _undo = MutableStateFlow<Undo?>(null)

    /** The last delete, while it can still be undone, or null. */
    val undo: StateFlow<Undo?> = _undo.asStateFlow()

    private val db get() = ServiceLocator.database

    /**
     * Deletes [ids] and returns how many lines went.
     *
     * Reads the rows first so the undo buffer has something to put back, and only then
     * deletes. The whole thing runs in one Room transaction: a half-done delete would leave
     * corrections orphaned, and an orphaned correction is text on screen with no line under it.
     */
    suspend fun delete(ids: Collection<Long>): Int = lock.withLock {
        val wanted = ids.distinct()
        if (wanted.isEmpty()) return@withLock 0

        // One transaction over the read and all three deletes. withTransaction is the suspend
        // form, so the DAO calls inside it stay suspend functions rather than blocking calls
        // smuggled onto whatever thread this was called from.
        val undone = db.withTransaction {
            val segments = db.transcripts().byIds(wanted)
            if (segments.isEmpty()) return@withTransaction null
            val present = segments.map { it.id }
            val corrections = db.corrections().forSegments(present)
            val flags = db.flagged().forSegments(present)

            db.corrections().deleteForSegments(present)
            db.flagged().deleteForSegments(present)
            db.transcripts().deleteByIds(present)
            Undo(segments, corrections, flags)
        }
        if (undone == null) {
            _undo.value = null
            return@withLock 0
        }

        // A summary that covered deleted lines now describes text that is not there. Dropping
        // it is better than leaving it: it can be regenerated in one tap, and a stale summary
        // of a line you deliberately deleted is the one thing this feature must not do.
        undone.segments.map { it.startTs }.distinct().forEach { ts ->
            runCatching { db.review().clearCovering(ts) }
                .onFailure { Diagnostics.w(TAG, "could not clear the summary covering $ts", it) }
        }

        _undo.value = undone
        Diagnostics.i(
            TAG,
            "deleted ${undone.segments.size} line(s), ${undone.corrections.size} correction(s), " +
                "${undone.flags.size} flag(s)",
        )
        undone.segments.size
    }

    /**
     * Deletes every line in a span — an hour, a day, a month — and returns how many went.
     *
     * The whole point of grouping Logs by hour and day is that a stretch of time is the unit
     * people think in, and "it recorded something it should not have" is almost always about a
     * stretch rather than a line. Deleting one line at a time was never the right size of
     * gesture for it.
     *
     * Reads the ids first and then goes through [delete], so a range delete is undoable on
     * exactly the same terms as a swipe rather than being a second, quieter code path.
     */
    suspend fun deleteRange(fromTs: Long, toTs: Long): Int {
        val ids = db.transcripts().inRange(fromTs, toTs).map { it.id }
        if (ids.isEmpty()) return 0
        return delete(ids)
    }

    /** How many lines a span holds, so a confirmation can say the number before it is gone. */
    suspend fun countIn(fromTs: Long, toTs: Long): Int =
        db.transcripts().inRange(fromTs, toTs).size

    /** Puts the last delete back, ids and all. Returns how many lines returned. */
    suspend fun undoLast(): Int = lock.withLock {
        val held = _undo.value ?: return@withLock 0
        db.withTransaction {
            db.transcripts().restore(held.segments)
            db.corrections().restore(held.corrections)
            db.flagged().restore(held.flags)
        }
        _undo.value = null
        Diagnostics.i(TAG, "restored ${held.segments.size} line(s)")
        held.segments.size
    }

    /** Forgets the undo buffer — after it has been offered and the moment has passed. */
    fun forget() {
        _undo.value = null
    }
}
