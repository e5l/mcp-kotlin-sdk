package client

import InitializedNotification
import JSONRPCMessage
import PingRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail


class StdioClientTransportTest {
    private val serverParameters = StdioServerParameters("/usr/bin/tee")

    @Test
    fun `should start then close cleanly`() = runTest {
        val client = StdioClientTransport(serverParameters)
        client.onerror = { error ->
            fail("Unexpected error: $error")
        }

        var didClose = false
        client.onclose = { didClose = true }

        client.start().await()
        assertFalse(didClose, "Transport should not be closed immediately after start")

        client.close().await()
        assertTrue(didClose, "Transport should be closed after close() call")
    }

    @Test
    fun `should read messages`() = runTest {
        val client = StdioClientTransport(serverParameters)
        client.onerror = { error -> fail("Unexpected error: $error") }

        val messages = listOf<JSONRPCMessage>(
            PingRequest(id = "1", params = null),
            InitializedNotification()
        )

        val readMessages = mutableListOf<JSONRPCMessage>()
        val finished = CompletableDeferred<Unit>()

        client.onmessage = { message ->
            readMessages.add(message)

            if (message == messages[1]) {
                finished.complete(Unit)
            }
        }

        client.start().await()
        client.send(messages[0]).await()
        client.send(messages[1]).await()

        finished.await()
        assertEquals(messages, readMessages, "Assert messages received")

        client.close().await()
    }
}
