package org.jetbrains.kotlinx.mcp.server

import org.jetbrains.kotlinx.mcp.JSONRPCMessage
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.jetbrains.kotlinx.mcp.shared.ReadBuffer
import org.jetbrains.kotlinx.mcp.shared.Transport
import org.jetbrains.kotlinx.mcp.shared.serializeMessage
import java.io.BufferedInputStream
import java.io.PrintStream
import kotlin.coroutines.CoroutineContext

/**
 * A server transport that communicates with a client via standard I/O.
 *
 * Reads from System.in and writes to System.out.
 */
class StdioServerTransport(
    private val inputStream: BufferedInputStream = BufferedInputStream(System.`in`),
    private val outputStream: PrintStream = System.out
) : Transport {
    override var onClose: (() -> Unit)? = null
    override var onError: ((Throwable) -> Unit)? = null
    override var onMessage: (suspend (JSONRPCMessage) -> Unit)? = null

    private val readBuffer = ReadBuffer()
    private var started = false
    private var readingJob: Job? = null

    private val coroutineContext: CoroutineContext = Dispatchers.IO + SupervisorJob()
    private val scope = CoroutineScope(coroutineContext)
    private val readChannel = Channel<ByteArray>(Channel.UNLIMITED)

    override suspend fun start() {
        if (started) {
            throw IllegalStateException(
                "StdioServerTransport already started!"
            )
        }

        started = true

        // Launch a coroutine to read from stdin
        readingJob = scope.launch {
            val buf = ByteArray(8192)
            try {
                while (isActive) {
                    val bytesRead = inputStream.read(buf)
                    if (bytesRead == -1) {
                        // EOF reached
                        break
                    }
                    if (bytesRead > 0) {
                        val chunk = buf.copyOf(bytesRead)
                        readChannel.send(chunk)
                    }
                }
            } catch (e: Throwable) {
                onError?.invoke(e)
            } finally {
                // Reached EOF or error, close connection
                close()
            }
        }

        // Launch a coroutine to process messages from readChannel
        scope.launch {
            try {
                for (chunk in readChannel) {
                    readBuffer.append(chunk)
                    processReadBuffer()
                }
            } catch (e: Throwable) {
                onError?.invoke(e)
            }
        }
    }

    private suspend fun processReadBuffer() {
        while (true) {
            val message = try {
                readBuffer.readMessage()
            } catch (e: Throwable) {
                onError?.invoke(e)
                null
            }

            if (message == null) break
            // Async invocation broke delivery order
            try {
                onMessage?.invoke(message)
            } catch (e: Throwable) {
                onError?.invoke(e)
            }
        }
    }

    override suspend fun close() {
        if (!started) return
        started = false

        // Cancel reading job and close channel
        readingJob?.cancel() // ToDO("was cancel and join")
        readChannel.close()
        readBuffer.clear()

        onClose?.invoke()
    }

    override suspend fun send(message: JSONRPCMessage) {
        val json = serializeMessage(message)
        synchronized(outputStream) {
            // You may need to add Content-Length headers before the message if using the LSP framing protocol
            outputStream.print(json)
            outputStream.flush()
        }
    }
}