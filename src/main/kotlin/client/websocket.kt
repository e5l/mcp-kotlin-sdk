package client

import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.websocket.*
import shared.MCP_SUBPROTOCOL
import shared.WebSocketMcpTransport
import kotlin.properties.Delegates

/**
 * Client transport for WebSocket: this will connect to a server over the WebSocket protocol.
 */
class WebSocketMcpClientTransport(
    private val client: HttpClient,
    private val urlString: String?,
    private val requestBuilder: HttpRequestBuilder.() -> Unit = {},
) : WebSocketMcpTransport() {
    override var session: WebSocketSession by Delegates.notNull()

    override suspend fun initializeSession() {
        session = urlString?.let {
            client.webSocketSession(it) {
                requestBuilder()

                header(HttpHeaders.SecWebSocketProtocol, MCP_SUBPROTOCOL)
            }
        } ?: client.webSocketSession {
            requestBuilder()

            header(HttpHeaders.SecWebSocketProtocol, MCP_SUBPROTOCOL)
        }
    }
}