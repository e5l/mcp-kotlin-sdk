package org.jetbrains.kotlinx.mcp.client

import org.jetbrains.kotlinx.mcp.Implementation
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import org.jetbrains.kotlinx.mcp.shared.IMPLEMENTATION_NAME
import shared.LIB_VERSION

/**
 * Returns a new WebSocket transport for the Model Context Protocol using the provided HttpClient.
 *
 * @param urlString Optional URL of the MCP server.
 * @param requestBuilder Optional lambda to configure the HTTP request.
 * @return A [WebSocketClientTransport] configured for MCP communication.
 */
fun HttpClient.mcpWebSocketTransport(
    urlString: String? = null,
    requestBuilder: HttpRequestBuilder.() -> Unit = {},
): WebSocketClientTransport = WebSocketClientTransport(this, urlString, requestBuilder)

/**
 * Creates and connects an MCP client over WebSocket using the provided HttpClient.
 *
 * @param urlString Optional URL of the MCP server.
 * @param requestBuilder Optional lambda to configure the HTTP request.
 * @return A connected [Client] ready for MCP communication.
 */
suspend fun HttpClient.mcpWebSocket(
    urlString: String? = null,
    requestBuilder: HttpRequestBuilder.() -> Unit = {},
): Client {
    val transport = mcpWebSocketTransport(urlString, requestBuilder)
    val client = Client(
        Implementation(
            name = IMPLEMENTATION_NAME,
            version = LIB_VERSION,
        )
    )
    client.connect(transport)
    return client
}
