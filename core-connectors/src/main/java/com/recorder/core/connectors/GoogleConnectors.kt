package com.recorder.core.connectors

import android.util.Base64
import com.recorder.core.llm.http.HttpJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/** Shared REST plumbing for the Google connectors. */
internal abstract class GoogleConnector(protected val auth: GoogleAuth) : Connector {

    override val isConfigured: Boolean get() = auth.isConfigured

    protected suspend fun get(url: String, query: Map<String, String> = emptyMap()): JSONObject =
        withContext(Dispatchers.IO) {
            val token = auth.accessToken() ?: error("Google account not connected")
            val built = url.toHttpUrl().newBuilder()
                .apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }
                .build()
            val request = Request.Builder()
                .url(built)
                .addHeader("Authorization", "Bearer $token")
                .build()
            HttpJson.client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) error("HTTP ${response.code}: ${text.take(300)}")
                if (text.isBlank()) JSONObject() else JSONObject(text)
            }
        }

    protected suspend fun post(url: String, body: JSONObject): JSONObject =
        withContext(Dispatchers.IO) {
            val token = auth.accessToken() ?: error("Google account not connected")
            HttpJson.post(
                url,
                body,
                mapOf("Content-Type" to "application/json", "Authorization" to "Bearer $token"),
            )
        }

    protected fun JSONArray?.joinStrings(transform: (JSONObject) -> String): String =
        if (this == null || length() == 0) {
            "(no results)"
        } else {
            (0 until length()).mapNotNull { optJSONObject(it) }.joinToString("\n", transform = transform)
        }
}

internal class GmailConnector(auth: GoogleAuth) : GoogleConnector(auth) {

    override val id = "gmail"
    override val displayName = "Gmail"

    override fun tools() = listOf(
        ConnectorTool(
            name = "search_messages",
            description = "Search the user's Gmail. Returns subject, sender and snippet for each hit.",
            parametersJsonSchema = """
                {"type":"object","properties":{
                  "query":{"type":"string","description":"Gmail search syntax, e.g. from:bob newer_than:7d"},
                  "limit":{"type":"integer","description":"Max messages to return (default 10)"}
                },"required":["query"]}
            """.trimIndent(),
            outbound = false,
        ),
        ConnectorTool(
            name = "send_email",
            description = "Draft an email to the user for approval. It is NOT sent until approved.",
            parametersJsonSchema = """
                {"type":"object","properties":{
                  "to":{"type":"string","description":"Recipient address"},
                  "subject":{"type":"string"},
                  "body":{"type":"string","description":"Plain text body"}
                },"required":["to","subject","body"]}
            """.trimIndent(),
            outbound = true,
            preview = { args ->
                buildString {
                    append("To: ").append(args.optString("to")).append('\n')
                    append("Subject: ").append(args.optString("subject")).append("\n\n")
                    append(args.optString("body"))
                }
            },
        ),
    )

    override suspend fun call(tool: String, arguments: JSONObject): String = when (tool) {
        "search_messages" -> searchMessages(
            arguments.optString("query"),
            arguments.optInt("limit", 10).coerceIn(1, 25),
        )

        // send_email is outbound: it only reaches the connector through executeApproved.
        else -> error("Unsupported Gmail tool: $tool")
    }

    override suspend fun executeApproved(tool: String, arguments: JSONObject): String =
        when (tool) {
            "send_email" -> sendEmail(
                to = arguments.optString("to"),
                subject = arguments.optString("subject"),
                body = arguments.optString("body"),
            )

            else -> call(tool, arguments)
        }

    private suspend fun searchMessages(query: String, limit: Int): String {
        val list = get(
            "$BASE/messages",
            mapOf("q" to query, "maxResults" to limit.toString()),
        )
        val ids = list.optJSONArray("messages") ?: return "(no results)"

        // A plain loop rather than joinToString: each iteration suspends on another fetch.
        val out = StringBuilder()
        for (i in 0 until minOf(ids.length(), limit)) {
            val messageId = ids.optJSONObject(i)?.optString("id").orEmpty()
            val message = get("$BASE/messages/$messageId", mapOf("format" to "metadata"))
            val headers = message.optJSONObject("payload")?.optJSONArray("headers")

            fun header(name: String): String =
                (0 until (headers?.length() ?: 0))
                    .mapNotNull { headers?.optJSONObject(it) }
                    .firstOrNull { it.optString("name").equals(name, ignoreCase = true) }
                    ?.optString("value").orEmpty()

            if (out.isNotEmpty()) out.append("\n\n")
            out.append("From: ").append(header("From")).append('\n')
                .append("Subject: ").append(header("Subject")).append('\n')
                .append(message.optString("snippet"))
        }
        return if (out.isEmpty()) "(no results)" else out.toString()
    }

    private suspend fun sendEmail(to: String, subject: String, body: String): String {
        val mime = buildString {
            append("To: ").append(to).append("\r\n")
            append("Subject: ").append(subject).append("\r\n")
            append("Content-Type: text/plain; charset=UTF-8\r\n\r\n")
            append(body)
        }
        val encoded = Base64.encodeToString(
            mime.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        val response = post("$BASE/messages/send", JSONObject().put("raw", encoded))
        return "Sent (id ${response.optString("id", "unknown")})"
    }

    private companion object {
        const val BASE = "https://gmail.googleapis.com/gmail/v1/users/me"
    }
}

internal class CalendarConnector(auth: GoogleAuth) : GoogleConnector(auth) {

    override val id = "calendar"
    override val displayName = "Google Calendar"

    override fun tools() = listOf(
        ConnectorTool(
            name = "list_events",
            description = "List calendar events in an RFC3339 time window.",
            parametersJsonSchema = """
                {"type":"object","properties":{
                  "time_min":{"type":"string","description":"RFC3339 start, e.g. 2026-09-08T00:00:00Z"},
                  "time_max":{"type":"string","description":"RFC3339 end"}
                },"required":["time_min","time_max"]}
            """.trimIndent(),
            outbound = false,
        ),
        ConnectorTool(
            name = "create_event",
            description = "Draft a calendar event for approval. It is NOT created until approved.",
            parametersJsonSchema = """
                {"type":"object","properties":{
                  "summary":{"type":"string"},
                  "start":{"type":"string","description":"RFC3339 start time"},
                  "end":{"type":"string","description":"RFC3339 end time"},
                  "attendees":{"type":"array","items":{"type":"string"},"description":"Email addresses"}
                },"required":["summary","start","end"]}
            """.trimIndent(),
            outbound = true,
            preview = { args ->
                buildString {
                    append(args.optString("summary")).append('\n')
                    append(args.optString("start")).append(" → ").append(args.optString("end"))
                    args.optJSONArray("attendees")?.let { append("\nWith: ").append(it.join(", ")) }
                }
            },
        ),
    )

    override suspend fun call(tool: String, arguments: JSONObject): String = when (tool) {
        "list_events" -> {
            val response = get(
                "$BASE/calendars/primary/events",
                mapOf(
                    "timeMin" to arguments.optString("time_min"),
                    "timeMax" to arguments.optString("time_max"),
                    "singleEvents" to "true",
                    "orderBy" to "startTime",
                ),
            )
            response.optJSONArray("items").joinStrings { event ->
                val start = event.optJSONObject("start")
                val whenStr = start?.optString("dateTime")?.ifBlank { start.optString("date") }.orEmpty()
                "$whenStr  ${event.optString("summary", "(no title)")}"
            }
        }

        else -> error("Unsupported Calendar tool: $tool")
    }

    override suspend fun executeApproved(tool: String, arguments: JSONObject): String =
        when (tool) {
            "create_event" -> {
                val body = JSONObject()
                    .put("summary", arguments.optString("summary"))
                    .put("start", JSONObject().put("dateTime", arguments.optString("start")))
                    .put("end", JSONObject().put("dateTime", arguments.optString("end")))
                arguments.optJSONArray("attendees")?.let { list ->
                    val attendees = JSONArray()
                    for (i in 0 until list.length()) {
                        attendees.put(JSONObject().put("email", list.optString(i)))
                    }
                    body.put("attendees", attendees)
                }
                val response = post("$BASE/calendars/primary/events", body)
                "Created (${response.optString("htmlLink", response.optString("id"))})"
            }

            else -> call(tool, arguments)
        }

    private companion object {
        const val BASE = "https://www.googleapis.com/calendar/v3"
    }
}

internal class DriveConnector(auth: GoogleAuth) : GoogleConnector(auth) {

    override val id = "drive"
    override val displayName = "Google Drive"

    // Read-only by design: Drive is context for the heavy tier, not somewhere it writes.
    override fun tools() = listOf(
        ConnectorTool(
            name = "search_files",
            description = "Search Drive file names and contents. Returns names, ids and types.",
            parametersJsonSchema = """
                {"type":"object","properties":{
                  "query":{"type":"string","description":"Free text, matched against name and full text"}
                },"required":["query"]}
            """.trimIndent(),
            outbound = false,
        ),
        ConnectorTool(
            name = "read_file",
            description = "Read a Google Doc or plain text file as text, by file id.",
            parametersJsonSchema = """
                {"type":"object","properties":{
                  "file_id":{"type":"string"}
                },"required":["file_id"]}
            """.trimIndent(),
            outbound = false,
        ),
    )

    override suspend fun call(tool: String, arguments: JSONObject): String = when (tool) {
        "search_files" -> {
            val escaped = arguments.optString("query").replace("'", "\\'")
            val response = get(
                "$BASE/files",
                mapOf(
                    "q" to "fullText contains '$escaped' and trashed = false",
                    "fields" to "files(id,name,mimeType,modifiedTime)",
                    "pageSize" to "10",
                ),
            )
            response.optJSONArray("files").joinStrings { file ->
                "${file.optString("name")}  [${file.optString("id")}]  ${file.optString("mimeType")}"
            }
        }

        "read_file" -> readFile(arguments.optString("file_id"))

        else -> error("Unsupported Drive tool: $tool")
    }

    private suspend fun readFile(fileId: String): String = withContext(Dispatchers.IO) {
        val token = auth.accessToken() ?: error("Google account not connected")
        val meta = get("$BASE/files/$fileId", mapOf("fields" to "mimeType,name"))
        val isGoogleDoc = meta.optString("mimeType").startsWith("application/vnd.google-apps")
        val url = if (isGoogleDoc) {
            "$BASE/files/$fileId/export?mimeType=text/plain"
        } else {
            "$BASE/files/$fileId?alt=media"
        }
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .build()
        HttpJson.client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("HTTP ${response.code}: ${text.take(200)}")
            text.take(MAX_CHARS)
        }
    }

    private companion object {
        const val BASE = "https://www.googleapis.com/drive/v3"
        const val MAX_CHARS = 20_000
    }
}

/** Builds the standard connector set. Custom connectors are registered on top of this. */
object GoogleConnectors {
    fun registerAll(registry: ConnectorRegistry, auth: GoogleAuth) {
        registry.register(GmailConnector(auth))
        registry.register(CalendarConnector(auth))
        registry.register(DriveConnector(auth))
    }
}
