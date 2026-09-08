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
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.coroutines.runBlocking

/** calendar-mcp - reads the diary, stages the alibi. */
fun main() = runBlocking {
    // stdout is the protocol channel. Anything else that prints there - a logging
    // library announcing itself - corrupts it. Hand the transport the real stdout and
    // point System.out at stderr, where this server's trace lines go anyway.
    val protocol = System.out
    System.setOut(System.err)

    val server = Server(
        serverInfo = Implementation(name = "calendar-mcp", version = "1.0.0"),
        options = ServerOptions(
            capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false)),
        ),
    )

    server.addTool(
        name = "getCalendar",
        description = "List the user's calendar events, including which invitations they already declined and why.",
        inputSchema = ToolSchema(properties = buildJsonObject { }),
    ) {
        System.err.println("[calendar-mcp] getCalendar -> ${Store.calendar.size} events")
        CallToolResult(content = listOf(TextContent(Store.json.encodeToString(Store.calendar))))
    }

    server.addTool(
        name = "createCalendarEvent",
        description = "Create a calendar event that backs up an excuse. Returns the new event id.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("title", buildJsonObject { put("type", "string") })
                put("startIso", buildJsonObject { put("type", "string") })
                put("durationMin", buildJsonObject { put("type", "integer") })
            },
            required = listOf("title", "startIso"),
        ),
    ) { request ->
        val title = request.arguments?.get("title")?.jsonPrimitive?.content ?: "(untitled)"
        val start = request.arguments?.get("startIso")?.jsonPrimitive?.content ?: "2026-09-08T20:00:00+02:00"
        val id = Store.nextEventId()
        Store.calendar += CalendarEvent(id = id, title = title, start = start, organizer = "Baruch Sadogursky")
        System.err.println("[calendar-mcp] createCalendarEvent '$title' -> $id")
        CallToolResult(content = listOf(TextContent(id)))
    }

    server.createSession(
        StdioServerTransport(
            inputStream = System.`in`.asSource().buffered(),
            outputStream = protocol.asSink().buffered(),
        ),
    )
    System.err.println("[calendar-mcp] ready")

    val done = Job()
    server.onClose { done.complete() }
    done.join()
}
