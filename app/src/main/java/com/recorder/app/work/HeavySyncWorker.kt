package com.recorder.app.work

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.recorder.app.ServiceLocator
import com.recorder.core.llm.ChatMessage
import com.recorder.core.llm.LlmProvider
import com.recorder.core.llm.Role
import com.recorder.core.storage.TranscriptSegment
import com.recorder.core.storage.ensure
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import org.json.JSONObject

/**
 * The heavy tier: a few times a day, hand the day's transcripts to whichever big model is
 * configured and let it reorganise folders and draft actions.
 *
 * Only text is ever sent. Audio never leaves the device, and the connectors it can reach
 * queue drafts rather than sending anything.
 */
class HeavySyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val settings = ServiceLocator.settings
        if (!settings.heavyTierEnabled.first()) {
            Log.i(TAG, "heavy tier disabled; nothing to do")
            return Result.success()
        }

        val provider = ServiceLocator.providers.activeProvider() ?: run {
            Log.i(TAG, "no provider configured")
            return Result.success()
        }

        val transcripts = ServiceLocator.database.transcripts()
        val batch = transcripts.unprocessedByHeavyTier(BATCH_SIZE)
        if (batch.isEmpty()) return Result.success()

        return runCatching { processBatch(provider, batch) }
            .fold(
                onSuccess = { Result.success() },
                onFailure = { error ->
                    Log.w(TAG, "heavy sync failed", error)
                    // Network blips and rate limits are worth another attempt later.
                    if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
                },
            )
    }

    private suspend fun processBatch(provider: LlmProvider, batch: List<TranscriptSegment>) {
        val gateway = ServiceLocator.connectorGateway
        val folders = ServiceLocator.database.folders()
        val existingFolders = folders.allOnce().map { it.name }

        val messages = mutableListOf(
            ChatMessage(Role.SYSTEM, SYSTEM_PROMPT),
            ChatMessage(Role.USER, buildUserPrompt(batch, existingFolders)),
        )

        var response = provider.complete(messages, gateway.toolSpecs())
        var rounds = 0
        // Tool calls here only ever read context or queue drafts, so looping is safe.
        while (response.toolCalls.isNotEmpty() && rounds++ < MAX_TOOL_ROUNDS) {
            messages += ChatMessage(Role.ASSISTANT, response.text)
            response.toolCalls.forEach { call ->
                val result = gateway.dispatch(call)
                messages += ChatMessage(
                    role = Role.TOOL,
                    content = result,
                    toolCallId = call.id,
                    toolName = call.name,
                )
            }
            response = provider.complete(messages, gateway.toolSpecs())
        }

        if (response.isError) error(response.error ?: "provider error")

        applyFolderAssignments(response.text, batch)
        ServiceLocator.database.transcripts().markHeavyProcessed(batch.map { it.id })
    }

    /** Reads the `{"assignments":[{"id":1,"folder":"Suppliers"}]}` block out of the reply. */
    private suspend fun applyFolderAssignments(text: String, batch: List<TranscriptSegment>) {
        val json = text.substringAfter('{', "").let { if (it.isBlank()) return else "{$it" }
        val parsed = runCatching { JSONObject(json.substringBeforeLast('}') + "}") }.getOrNull() ?: return
        val assignments = parsed.optJSONArray("assignments") ?: return

        val transcripts = ServiceLocator.database.transcripts()
        val folders = ServiceLocator.database.folders()
        val validIds = batch.map { it.id }.toSet()

        for (i in 0 until assignments.length()) {
            val entry = assignments.optJSONObject(i) ?: continue
            val id = entry.optLong("id", -1)
            val folderName = entry.optString("folder").trim()
            if (id !in validIds || folderName.isBlank()) continue
            transcripts.assignFolder(id, folders.ensure(folderName))
        }
    }

    private fun buildUserPrompt(batch: List<TranscriptSegment>, folders: List<String>): String =
        buildString {
            append("Existing folders: ")
            append(if (folders.isEmpty()) "(none yet)" else folders.joinToString(", "))
            append("\n\nTranscript segments:\n")
            batch.forEach { segment ->
                append(segment.id).append(" [")
                append(TIMESTAMP.format(Date(segment.startTs)))
                append("] ").append(segment.text).append('\n')
            }
        }

    companion object {
        private const val TAG = "HeavySyncWorker"
        const val UNIQUE_PERIODIC = "heavy-sync-periodic"
        const val UNIQUE_ONE_SHOT = "heavy-sync-now"

        private const val BATCH_SIZE = 200
        private const val MAX_TOOL_ROUNDS = 4
        private const val MAX_ATTEMPTS = 3

        private val TIMESTAMP = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

        private const val SYSTEM_PROMPT =
            "You are organising a running transcript of the user's day, recorded on their " +
                "phone. Two jobs.\n" +
                "1. Sort each segment into a folder. Reuse existing folder names wherever " +
                "they fit. End your reply with a JSON object of the form " +
                "{\"assignments\":[{\"id\":123,\"folder\":\"Suppliers\"}]} covering every " +
                "segment worth filing.\n" +
                "2. If a segment clearly calls for an email or a calendar event, use the " +
                "available tools to draft it. Drafts are queued for the user's approval and " +
                "are never sent automatically, so drafting is safe — but do not draft " +
                "anything the user did not actually ask for."
    }
}

object HeavySyncScheduler {

    /** Charging + idle, so the heavy tier never competes with recording for CPU or battery. */
    fun ensureScheduled(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresCharging(true)
            .setRequiresDeviceIdle(true)
            .setRequiresBatteryNotLow(true)
            .build()

        val request = PeriodicWorkRequestBuilder<HeavySyncWorker>(6, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            HeavySyncWorker.UNIQUE_PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /** The "sync now" button: same worker, no charging or idle requirement. */
    fun runNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<HeavySyncWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            HeavySyncWorker.UNIQUE_ONE_SHOT,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }
}
