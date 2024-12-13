package server

import Implementation
import ServerCapabilities
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import shared.IMPLEMENTATION_NAME
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

private suspend fun Route.createMcpServer(
    session: WebSocketServerSession,
    options: ServerOptions?,
    handler: suspend Server.() -> Unit,
) {
    val transport = WebSocketMcpServerTransport(session)

    val server = Server(
        _serverInfo = Implementation(
            name = IMPLEMENTATION_NAME,
            version = LIB_VERSION,
        ),
        options = options ?: ServerOptions(
            capabilities = ServerCapabilities(
                prompts = ServerCapabilities.Prompts(listChanged = null),
                resources = ServerCapabilities.Resources(subscribe = null, listChanged = null),
                tools = ServerCapabilities.Tools(listChanged = null),
                logging = null  // or a valid empty JSON object if required
            )
        ),
    )

    server.connect(transport)
    handler(server)
    server.close()
}
