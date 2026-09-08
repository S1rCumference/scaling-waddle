package com.recorder.core.connectors

import android.util.Log
import com.recorder.core.llm.ToolCall
import com.recorder.core.llm.ToolSpec
import com.recorder.core.storage.PendingAction
import com.recorder.core.storage.PendingActionDao
import com.recorder.core.storage.PendingActionStatus
import org.json.JSONObject

/**
 * The bridge between the model's tool calls and the connectors, and the place the
 * draft-only rule is enforced.
 *
 * Read-only tools run immediately. Outbound tools never execute here at all: they are
 * written to `pending_actions` as a draft and the model is told a draft was queued. The
 * only path to actually sending is [approve], which is called from the approval screen
 * after a human taps it.
 */
class ConnectorToolGateway(
    private val registry: ConnectorRegistry,
    private val pendingActions: PendingActionDao,
) {

    fun toolSpecs(): List<ToolSpec> = registry.configured().flatMap { connector ->
        connector.tools().map { tool ->
            ToolSpec(
                name = registry.qualify(connector, tool),
                description = if (tool.outbound) {
                    "${tool.description} (Creates a draft for the user to approve; does not send.)"
                } else {
                    tool.description
                },
                parametersJsonSchema = tool.parametersJsonSchema,
            )
        }
    }

    suspend fun dispatch(call: ToolCall): String {
        val resolved = registry.resolve(call.name)
            ?: return "Unknown tool: ${call.name}"
        val (connector, tool) = resolved
        val arguments = runCatching { JSONObject(call.argumentsJson) }.getOrElse { JSONObject() }

        return if (tool.outbound) {
            queueDraft(connector, tool, arguments)
        } else {
            runCatching { connector.call(tool.name, arguments) }
                .getOrElse { "Tool failed: ${it.message}" }
        }
    }

    private suspend fun queueDraft(
        connector: Connector,
        tool: ConnectorTool,
        arguments: JSONObject,
    ): String {
        val preview = runCatching { tool.preview(arguments) }.getOrElse { arguments.toString(2) }
        val id = pendingActions.insert(
            PendingAction(
                connector = connector.id,
                tool = tool.name,
                title = "${connector.displayName}: ${tool.name}",
                preview = preview,
                payloadJson = arguments.toString(),
            ),
        )
        return "Draft #$id queued for approval. Nothing was sent."
    }

    /** Runs a draft the user approved. Returns the connector's result, or an error string. */
    suspend fun approve(actionId: Long): Result<String> {
        val action = pendingActions.byId(actionId)
            ?: return Result.failure(IllegalArgumentException("No pending action $actionId"))
        if (action.status != PendingActionStatus.DRAFT) {
            return Result.failure(IllegalStateException("Action $actionId is already ${action.status}"))
        }
        val connector = registry.byId(action.connector)
            ?: return Result.failure(IllegalStateException("Connector ${action.connector} not registered"))

        val arguments = runCatching { JSONObject(action.payloadJson) }.getOrElse { JSONObject() }
        return runCatching {
            connector.executeApproved(action.tool, arguments)
        }.onSuccess {
            pendingActions.update(
                action.copy(
                    status = PendingActionStatus.SENT,
                    resolvedTs = System.currentTimeMillis(),
                ),
            )
        }.onFailure { failure ->
            Log.w(TAG, "approved action $actionId failed", failure)
            pendingActions.update(
                action.copy(
                    status = PendingActionStatus.FAILED,
                    resolvedTs = System.currentTimeMillis(),
                    error = failure.message,
                ),
            )
        }
    }

    suspend fun reject(actionId: Long) {
        pendingActions.byId(actionId)?.let {
            pendingActions.update(
                it.copy(
                    status = PendingActionStatus.REJECTED,
                    resolvedTs = System.currentTimeMillis(),
                ),
            )
        }
    }

    private companion object {
        const val TAG = "ConnectorToolGateway"
    }
}
