package jclaw.mocks

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** organizer-mcp - knows who takes a decline badly, and delivers it anyway. */
fun main() = runBlocking {
    // stdout is the protocol channel. Anything else that prints there - a logging
    // library announcing itself - corrupts it. Hand the transport the real stdout and
    // point System.out at stderr, where this server's trace lines go anyway.
    val protocol = System.out
    System.setOut(System.err)

    val server = Server(
        serverInfo = Implementation(name = "organizer-mcp", version = "1.0.0"),
        options = ServerOptions(
            capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false)),
        ),
    )

    server.addTool(
        name = "getOrganizerSensitivity",
        description = "How badly this organizer takes a decline: EASYGOING, NORMAL, or TOUCHY.",
        inputSchema = ToolSchema(
            properties = buildJsonObject { put("name", buildJsonObject { put("type", "string") }) },
            required = listOf("name"),
        ),
    ) { request ->
        val name = request.arguments?.get("name")?.jsonPrimitive?.content ?: ""
        val s = Store.sensitivity(name)
        System.err.println("[organizer-mcp] getOrganizerSensitivity('$name') -> $s")
        CallToolResult(content = listOf(TextContent(s)))
    }

    server.addTool(
        name = "sendDecline",
        description = "Actually deliver the decline to the organizer. This has real consequences.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("eventId", buildJsonObject { put("type", "string") })
                put("message", buildJsonObject { put("type", "string") })
            },
            required = listOf("eventId", "message"),
        ),
    ) { request ->
        val eventId = request.arguments?.get("eventId")?.jsonPrimitive?.content ?: "(none)"
        System.err.println("[organizer-mcp] sendDecline($eventId) -> delivered")
        CallToolResult(
            content = listOf(TextContent("""{"delivered":true,"deliveredAt":"2026-09-08T15:30:00+02:00","eventId":"$eventId"}""")),
        )
    }

    server.createSession(
        StdioServerTransport(
            inputStream = System.`in`.asSource().buffered(),
            outputStream = protocol.asSink().buffered(),
        ),
    )
    System.err.println("[organizer-mcp] ready")

    val done = Job()
    server.onClose { done.complete() }
    done.join()
}
