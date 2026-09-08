package com.recorder.core.connectors

import org.json.JSONObject

/**
 * One tool a connector exposes, in the MCP tool shape: a name, a description, and a JSON
 * Schema for its arguments. [outbound] is the safety-critical bit — anything that would
 * send, create or change something in the outside world must declare it, because those
 * calls are routed to a draft for approval instead of being executed.
 */
data class ConnectorTool(
    val name: String,
    val description: String,
    val parametersJsonSchema: String,
    val outbound: Boolean,
    /** Short human-readable summary of what this call would do, shown on the approval card. */
    val preview: (JSONObject) -> String = { it.toString(2) },
)

/**
 * A source of tools. To add your own connector: implement this interface, return your
 * tools from [tools], handle them in [call], and register the instance with
 * [ConnectorRegistry.register]. Nothing else in the app needs to change — see
 * `docs/CONNECTORS.md`.
 */
interface Connector {
    val id: String
    val displayName: String

    /** False when the connector has no credentials yet; its tools are then not offered. */
    val isConfigured: Boolean

    fun tools(): List<ConnectorTool>

    /**
     * Executes a read-only tool. Outbound tools never reach this method directly: they go
     * through the approval queue first, and are executed by [executeApproved] afterwards.
     */
    suspend fun call(tool: String, arguments: JSONObject): String

    /** Executes an outbound tool that a human has approved. */
    suspend fun executeApproved(tool: String, arguments: JSONObject): String =
        call(tool, arguments)
}

class ConnectorRegistry {
    private val connectors = linkedMapOf<String, Connector>()

    fun register(connector: Connector) {
        connectors[connector.id] = connector
    }

    fun all(): List<Connector> = connectors.values.toList()

    fun configured(): List<Connector> = connectors.values.filter { it.isConfigured }

    fun byId(id: String): Connector? = connectors[id]

    /** Finds the connector owning a tool name, which is namespaced as `connector__tool`. */
    fun resolve(qualifiedTool: String): Pair<Connector, ConnectorTool>? {
        val (connectorId, toolName) = qualifiedTool.split(SEPARATOR, limit = 2)
            .takeIf { it.size == 2 } ?: return null
        val connector = connectors[connectorId] ?: return null
        val tool = connector.tools().firstOrNull { it.name == toolName } ?: return null
        return connector to tool
    }

    fun qualify(connector: Connector, tool: ConnectorTool): String =
        "${connector.id}$SEPARATOR${tool.name}"

    companion object {
        const val SEPARATOR = "__"
    }
}
