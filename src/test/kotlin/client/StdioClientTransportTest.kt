package client

import InitializedNotification
import JSONRPCMessage
import PingRequest
import io.ktor.utils.io.streams.asInput
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import toJSON
import kotlin.test.*


class StdioClientTransportTest {
    @Test
    fun `should start then close cleanly`() = runTest {
        // Run process "/usr/bin/tee"
        val processBuilder = ProcessBuilder("/usr/bin/tee")
        val process = processBuilder.start()

        val input = process.inputStream
        val output = process.outputStream

        val client = StdioClientTransport(
            input = input,
            output = output
        )

        client.onError = { error ->
            fail("Unexpected error: $error")
        }

        var didClose = false
        client.onClose = { didClose = true }

        client.start()
        assertFalse(didClose, "Transport should not be closed immediately after start")

        client.close()
        assertTrue(didClose, "Transport should be closed after close() call")

        process.destroy()
    }

    @Test
    fun `should read messages`() = runTest {
        val processBuilder = ProcessBuilder("/usr/bin/tee")
        val process = processBuilder.start()

        val input = process.inputStream
        val output = process.outputStream

        val client = StdioClientTransport(
            input = input,
            output = output
        )
        client.onError = { error -> fail("Unexpected error: $error") }

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
        process.waitFor()
        process.destroy()
    }
}
