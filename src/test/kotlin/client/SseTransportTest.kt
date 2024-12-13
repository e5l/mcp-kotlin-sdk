package client

import InitializedNotification
import JSONRPCMessage
import PingRequest
import io.ktor.client.*
import io.ktor.client.plugins.sse.*
import io.ktor.server.application.*
import io.ktor.server.cio.CIO
import io.ktor.server.engine.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import server.mcpSse
import toJSON
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

private const val PORT = 8080

class SseTransportTest {
    @Test
    fun `should start then close cleanly`() {
        runTest {
            val server = embeddedServer(CIO, port = PORT) {
                install(io.ktor.server.sse.SSE)
                routing {
                    mcpSse()
                }
            }.start(wait = false)

            val client = HttpClient {
                install(SSE)
            }.mcpSseTransport {
                url {
                    host = "localhost"
                    port = PORT
                }
            }

            client.onError = { error ->
                fail("Unexpected error: $error")
            }

            var didClose = false
            client.onClose = { didClose = true }

            client.start()
            assertFalse(didClose, "Transport should not be closed immediately after start")

            client.close()
            assertTrue(didClose, "Transport should be closed after close() call")

            server.stop()
        }
    }

    @Test
    fun `should read messages`() = runTest {
        val server = embeddedServer(CIO, port = PORT) {
            install(io.ktor.server.sse.SSE)
            routing {
                mcpSse()
            }
        }.start(wait = false)

        val client = HttpClient {
            install(SSE)
        }.mcpSseTransport {
            url {
                host = "localhost"
                port = PORT
            }
        }

        client.onError = { error ->
            error.printStackTrace()
            fail("Unexpected error: $error")
        }

        val messages = listOf<JSONRPCMessage>(
            PingRequest().toJSON(),
            InitializedNotification().toJSON()
        )

        val readMessages = mutableListOf<JSONRPCMessage>()
        val finished = CompletableDeferred<Unit>()

        client.onMessage = { message ->
            readMessages.add(message)
            if (message == messages.last()) {
                finished.complete(Unit)
            }
        }

        client.start()

        for (message in messages) {
            client.send(message)
        }

        finished.await()

        assertEquals(messages, readMessages, "Assert messages received")

        client.close()
        server.stop()
    }
}
