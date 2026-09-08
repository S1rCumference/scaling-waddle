# Connectors

A connector is a named bundle of tools the heavy tier can call. Three ship with the app —
Gmail, Google Calendar, Google Drive — and adding your own is one interface and one
registration call.

## The safety rule, and where it is enforced

Every tool declares `outbound: Boolean`.

- `outbound = false` — read-only. Runs immediately when the model calls it.
- `outbound = true` — would change something in the outside world. **The model cannot
  execute it at all.** `ConnectorToolGateway.dispatch()` writes the arguments to
  `pending_actions` and replies "draft queued". The only code path that actually calls
  `Connector.executeApproved()` is `ConnectorToolGateway.approve()`, which runs when you
  tap Approve in the Drafts tab.

This is a property of the gateway, not of the prompt. A model that decides to send an
email anyway simply produces another draft.

## Writing one

```kotlin
class InvoiceConnector(private val client: MyBillingClient) : Connector {

    override val id = "invoices"
    override val displayName = "Invoicing"
    override val isConfigured get() = client.hasCredentials

    override fun tools() = listOf(
        ConnectorTool(
            name = "find_unpaid",
            description = "List unpaid invoices, newest first.",
            parametersJsonSchema = """
                {"type":"object","properties":{
                  "customer":{"type":"string","description":"Customer name, optional"}
                }}
            """.trimIndent(),
            outbound = false,
        ),
        ConnectorTool(
            name = "send_reminder",
            description = "Draft a payment reminder for an invoice.",
            parametersJsonSchema = """
                {"type":"object","properties":{
                  "invoice_id":{"type":"string"},
                  "message":{"type":"string"}
                },"required":["invoice_id","message"]}
            """.trimIndent(),
            outbound = true,
            preview = { args -> "Reminder for ${args.optString("invoice_id")}:\n${args.optString("message")}" },
        ),
    )

    override suspend fun call(tool: String, arguments: JSONObject): String = when (tool) {
        "find_unpaid" -> client.unpaid(arguments.optString("customer")).joinToString("\n")
        else -> error("Unsupported tool: $tool")
    }

    override suspend fun executeApproved(tool: String, arguments: JSONObject): String =
        when (tool) {
            "send_reminder" -> client.sendReminder(
                arguments.optString("invoice_id"),
                arguments.optString("message"),
            )
            else -> call(tool, arguments)
        }
}
```

Register it in `ServiceLocator.connectors`:

```kotlin
val connectors: ConnectorRegistry by lazy {
    ConnectorRegistry().also { registry ->
        GoogleConnectors.registerAll(registry, RefreshTokenGoogleAuth(apiKeys))
        registry.register(InvoiceConnector(myBillingClient))
    }
}
```

That is the whole integration. The gateway namespaces the tool as `invoices__find_unpaid`,
publishes its schema to whichever provider is active, routes calls back to you, and forces
`send_reminder` through the approval queue.

Notes:

- `isConfigured` gates the connector. Return false when credentials are missing and its
  tools are never offered to the model, rather than failing mid-conversation.
- `preview` is what a human reads before approving. Make it the actual content — the
  recipient and the body — not a JSON dump.
- Anything that costs money, sends a message, or cannot be undone is `outbound = true`.
  When unsure, mark it outbound; the cost is one tap.

## Google credentials

The phone holds only a refresh token, exchanged for short-lived access tokens as needed.
The one-time consent happens on a computer, so no browser or Google sign-in library is
needed on a device whose Google stack `provision.sh` has largely disabled.

```bash
./scripts/google_oauth.sh          # walks through it, prints the refresh token
```

Then enter the client id, client secret and refresh token in Settings → Connectors. They
are stored in Keystore-backed encrypted preferences.

Scopes requested: `gmail.modify`, `calendar.events`, `drive.readonly`.

## MCP

`ConnectorTool` deliberately uses the MCP tool shape — a name, a description, and a JSON
Schema for arguments. That is the same shape Claude, OpenAI and Gemini tool calling all
accept, which is why one `ToolSpec` list serves every provider. Pointing this at a real
MCP server later means implementing `Connector` over an MCP session and mapping its tool
list through; nothing above this layer changes.
