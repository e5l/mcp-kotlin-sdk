package org.jetbrains.kotlinx.mcp.client

import org.jetbrains.kotlinx.mcp.Implementation
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import org.jetbrains.kotlinx.mcp.shared.IMPLEMENTATION_NAME
import shared.LIB_VERSION

fun HttpClient.mcpWebSocketTransport(
    urlString: String? = null,
    requestBuilder: HttpRequestBuilder.() -> Unit = {},
) = WebSocketClientTransport(this, urlString, requestBuilder)

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
