package org.jetbrains.kotlinx.mcp.server

import org.jetbrains.kotlinx.mcp.Implementation
import org.jetbrains.kotlinx.mcp.ServerCapabilities
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import org.jetbrains.kotlinx.mcp.shared.IMPLEMENTATION_NAME
import shared.LIB_VERSION

fun Route.mcpWebSocket(
    options: ServerOptions? = null,
    handler: suspend Server.() -> Unit = {},
) {
    webSocket {
        createMcpServer(this, options, handler)
    }
}

fun Route.mcpWebSocket(
    path: String,
    options: ServerOptions? = null,
    handler: suspend Server.() -> Unit = {},
) {
    webSocket(path) {
        createMcpServer(this, options, handler)
    }
}

fun Route.mcpWebSocketTransport(
    handler: suspend WebSocketMcpServerTransport.() -> Unit = {},
) {
    webSocket {
        val transport = createMcpTransport(this)
        transport.start()
        handler(transport)
        transport.close()
    }
}

fun Route.mcpWebSocketTransport(
    path: String,
    handler: suspend WebSocketMcpServerTransport.() -> Unit = {},
) {
    webSocket(path) {
        val transport = createMcpTransport(this)
        transport.start()
        handler(transport)
        transport.close()
    }
}

private suspend fun Route.createMcpServer(
    session: WebSocketServerSession,
    options: ServerOptions?,
    handler: suspend Server.() -> Unit,
) {
    val transport = createMcpTransport(session)

    val server = Server(
        serverInfo = Implementation(
            name = IMPLEMENTATION_NAME,
            version = LIB_VERSION,
        ),
        options = options ?: ServerOptions(
            capabilities = ServerCapabilities(
                prompts = ServerCapabilities.Prompts(listChanged = null),
                resources = ServerCapabilities.Resources(subscribe = null, listChanged = null),
                tools = ServerCapabilities.Tools(listChanged = null),
            )
        ),
    )

    server.connect(transport)
    handler(server)
    server.close()
}

private fun createMcpTransport(
    session: WebSocketServerSession,
): WebSocketMcpServerTransport {
    return WebSocketMcpServerTransport(session)
}
