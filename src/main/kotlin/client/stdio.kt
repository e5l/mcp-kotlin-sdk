package client

import JSONRPCMessage
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.io.IOException
import shared.ReadBuffer
import shared.Transport
import shared.serializeMessage
import java.io.InputStream
import java.io.OutputStream
import kotlin.coroutines.CoroutineContext
import kotlin.text.Charsets.UTF_8

/**
 * A transport implementation for JSON-RPC communication that leverages standard input and output streams.
 *
 * This class reads from an input stream to process incoming JSON-RPC messages and writes JSON-RPC messages
 * to an output stream.
 *
 * @param input The input stream where messages are received.
 * @param output The output stream where messages are sent.
 */
class StdioClientTransport(
    private val input: InputStream,
    private val output: OutputStream
) : Transport {
    private val jsonRpcContext: CoroutineContext = Dispatchers.IO
    private val scope = CoroutineScope(jsonRpcContext + SupervisorJob())
    private var job: Job? = null
    private var started = false
    private val sendChannel = Channel<JSONRPCMessage>(Channel.UNLIMITED)
    private val readBuffer = ReadBuffer()

    override var onClose: (() -> Unit)? = null
    override var onError: ((Throwable) -> Unit)? = null
    override var onMessage: (suspend ((JSONRPCMessage) -> Unit))? = null

    override suspend fun start() {
        if (started) {
            throw IllegalStateException("StdioClientTransport already started!")
        }
        started = true

        val outputStream = output.bufferedWriter(UTF_8)

        job = scope.launch {
            val readJob = launch {
                try {
                    val buffer = ByteArray(8192)
                    while (isActive) {
                        val bytesRead = input.read(buffer)
                        if (bytesRead == -1) break
                        if (bytesRead > 0) {
                            readBuffer.append(buffer.copyOf(bytesRead))
                            processReadBuffer()
                        }
                    }
                } catch (e: Throwable) {
                    when (e) { // TODO
                        is IOException -> {}
                        is CancellationException -> {}
                        else -> {
                            onError?.invoke(e)
                            System.err.println(e) // todo()
                        }
                    }
                } finally {
                    input.close()
                }
            }

            val writeJob = launch {
                try {
                    sendChannel.consumeEach { message ->
                        val json = serializeMessage(message)
                        outputStream.write(json)
                        outputStream.flush()
                    }
                } catch (e: Throwable) {
                    if (isActive) {
                        onError?.invoke(e)
                        System.err.println(e) // todo()
                    }
                } finally {
                    outputStream.close()
                }
            }

            readJob.join()
            writeJob.cancelAndJoin()
            onClose?.invoke()
        }
    }

    override suspend fun send(message: JSONRPCMessage) {
        if (!started) {
            throw IllegalStateException("Transport not started")
        }

        sendChannel.send(message)
    }

    override suspend fun close() {
        input.close()
        output.close()
        readBuffer.clear()
        sendChannel.close()
        job?.cancelAndJoin()
        onClose?.invoke()
    }

    private suspend fun processReadBuffer() {
        while (true) {
            val msg = readBuffer.readMessage() ?: break
            try {
                onMessage?.invoke(msg)
            } catch (e: Throwable) {
                onError?.invoke(e)
            }
        }
    }
}