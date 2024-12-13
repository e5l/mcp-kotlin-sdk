package server

import InitializedNotification
import JSONRPCMessage
import PingRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import shared.ReadBuffer
import shared.serializeMessage
import toJSON
import java.io.*

class StdioServerTransportTest {
    private lateinit var inputPipe: PipedInputStream
    private lateinit var outputPipe: PipedOutputStream
    private lateinit var input: BufferedInputStream
    private lateinit var output: PrintStream
    private lateinit var outputBuffer: ReadBuffer

    @BeforeEach
    fun setUp() {
        // Piped streams simulate a readable/writable pair like Node's Readable/Writable.
        val pipedOutput = PipedOutputStream()
        inputPipe = PipedInputStream(pipedOutput)

        val pipedInput = PipedOutputStream()
        val pipedInputForRead = PipedInputStream(pipedInput)
        outputPipe = pipedInput
        input = BufferedInputStream(inputPipe)

        // We'll capture output in a buffer for inspection if needed.
        val outputBaos = ByteArrayOutputStream()
        output = PrintStream(BufferedOutputStream(pipedInput), true)
        outputBuffer = ReadBuffer()
    }

    @Test
    fun `should start then close cleanly`() {
        runBlocking {
            val server = StdioServerTransport(input, output)
            server.onError = { error ->
                throw error
            }

            var didClose = false
            server.onClose = {
                didClose = true
            }

            server.start()
            assertFalse(didClose, "Should not have closed yet")

            server.close()
            assertTrue(didClose, "Should have closed after calling close()")
        }
    }

    @Test
    fun `should not read until started`() {
        runBlocking {
            val server = StdioServerTransport(input, output)
            server.onError = { error ->
                throw error
            }

            var didRead = false
            val readMessage = CompletableDeferred<JSONRPCMessage>()

            server.onMessage = { message ->
                didRead = true
                readMessage.complete(message)
            }

            val message = PingRequest().toJSON()

            // Push message before server started
            val serialized = serializeMessage(message)
            outputPipe.write(serialized)
            outputPipe.flush()

            assertFalse(didRead, "Should not have read message before start")

            server.start()
            val received = readMessage.await()
            assertEquals(message, received)
        }
    }

    @Test
    fun `should read multiple messages`() {
        runBlocking {
            val server = StdioServerTransport(input, output)
            server.onError = { error ->
                throw error
            }

            val messages = listOf(
                PingRequest().toJSON(),
                InitializedNotification().toJSON(),
            )

            val readMessages = mutableListOf<JSONRPCMessage>()
            val finished = CompletableDeferred<Unit>()

            server.onMessage = { message ->
                readMessages.add(message)
                if (message == messages[1]) {
                    finished.complete(Unit)
                }
            }

            // Push both messages before starting the server
            for (m in messages) {
                outputPipe.write(serializeMessage(m))
            }
            outputPipe.flush()

            server.start()
            finished.await()

            assertEquals(messages, readMessages)
        }
    }
}

fun PipedOutputStream.write(s: String) {
    write(s.toByteArray())
}