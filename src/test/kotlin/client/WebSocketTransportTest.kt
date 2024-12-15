package client

import io.ktor.server.testing.*
import io.ktor.server.websocket.*
import kotlinx.coroutines.CompletableDeferred
import org.jetbrains.kotlinx.mcp.client.mcpWebSocketTransport
import org.junit.jupiter.api.Test
import org.jetbrains.kotlinx.mcp.server.mcpWebSocket
import org.jetbrains.kotlinx.mcp.server.mcpWebSocketTransport

class WebSocketTransportTest : BaseTransportTest() {
    @Test
    fun `should start then close cleanly`() = testApplication {
        install(WebSockets)
        routing {
            mcpWebSocket()
        }

        val client = createClient {
            install(io.ktor.client.plugins.websocket.WebSockets)
        }.mcpWebSocketTransport()

        testClientOpenClose(client)
    }

    @Test
    fun `should read messages`() = testApplication {
        val clientFinished = CompletableDeferred<Unit>()

        install(WebSockets)
        routing {
            mcpWebSocketTransport {
                onMessage = {
                    send(it)
                }

                clientFinished.await()
            }
        }

        val client = createClient {
            install(io.ktor.client.plugins.websocket.WebSockets)
        }.mcpWebSocketTransport()

        testClientRead(client)

        clientFinished.complete(Unit)
    }
}
