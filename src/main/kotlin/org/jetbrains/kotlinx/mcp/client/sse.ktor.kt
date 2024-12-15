package org.jetbrains.kotlinx.mcp.client

import org.jetbrains.kotlinx.mcp.Implementation
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import org.jetbrains.kotlinx.mcp.shared.IMPLEMENTATION_NAME
import shared.LIB_VERSION
import kotlin.time.Duration

fun HttpClient.mcpSseTransport(
    urlString: String? = null,
    reconnectionTime: Duration? = null,
    requestBuilder: HttpRequestBuilder.() -> Unit = {},
) = SSEClientTransport(this, urlString, reconnectionTime, requestBuilder)

suspend fun HttpClient.mcpSse(
    urlString: String? = null,
    reconnectionTime: Duration? = null,
    requestBuilder: HttpRequestBuilder.() -> Unit = {},
): Client {
    val transport = mcpSseTransport(urlString, reconnectionTime, requestBuilder)
    val client = Client(
        Implementation(
            name = IMPLEMENTATION_NAME,
            version = LIB_VERSION,
        )
    )
    client.connect(transport)
    return client
}
