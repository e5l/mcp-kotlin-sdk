package org.jetbrains.kotlinx.mcp.server

import io.ktor.http.*
import io.ktor.server.websocket.*
import org.jetbrains.kotlinx.mcp.shared.MCP_SUBPROTOCOL
import org.jetbrains.kotlinx.mcp.shared.WebSocketMcpTransport

class WebSocketMcpServerTransport(
    override val session: WebSocketServerSession,
) : WebSocketMcpTransport() {
    override suspend fun initializeSession() {
        val subprotocol = session.call.request.headers[HttpHeaders.SecWebSocketProtocol]
        if (subprotocol != MCP_SUBPROTOCOL) {
            error("Invalid subprotocol: $subprotocol, expected $MCP_SUBPROTOCOL")
        }
    }
}
