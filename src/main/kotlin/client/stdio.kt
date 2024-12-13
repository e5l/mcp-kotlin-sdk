package client

import JSONRPCMessage
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.asInputStream
import kotlinx.io.asOutputStream
import shared.ReadBuffer
import shared.Transport
import shared.serializeMessage
import kotlin.coroutines.CoroutineContext
import kotlin.text.Charsets.UTF_8

enum class StdioOption {
    INHERIT,
    PIPE,
    IGNORE
}


/**
 * Represents the parameters required to start a standard input/output server process.
 *
 * @property command The executable to run to start the server.
 * @property args Command line arguments to pass to the executable.
 * @property env The environment to use when spawning the process.
 * If not specified, the result, of [getDefaultEnvironment] will be used.
 */
data class StdioServerParameters(
    val command: String,
    val args: List<String> = emptyList(),
    val env: Map<String, String>? = null,
    val stderr: StdioOption? = StdioOption.INHERIT
)

/**
 * Returns a default environment object including only environment variables deemed safe to inherit.
 */
private fun getDefaultEnvironment(): Map<String, String> {
    val defaultEnvKeys = if (System.getProperty("os.name").lowercase().contains("win")) {
        // Environment variables to inherit by default if an environment is not explicitly given.
        listOf(
            "APPDATA",
            "HOMEDRIVE",
            "HOMEPATH",
            "LOCALAPPDATA",
            "PATH",
            "PROCESSOR_ARCHITECTURE",
            "SYSTEMDRIVE",
            "SYSTEMROOT",
            "TEMP",
            "USERNAME",
            "USERPROFILE"
        )
    } else {
        listOf("HOME", "LOGNAME", "PATH", "SHELL", "TERM", "USER")
    }

    val env = mutableMapOf<String, String>()
    for (key in defaultEnvKeys) {
        val value = System.getenv(key) ?: continue
        if (!value.startsWith("()")) {
            env[key] = value
        }
    }
    return env
}

/**
 * Client transport for stdio:
 * this will connect to a server by spawning a process and communicating with it over stdin/stdout.
 */
class StdioClientTransport(
//    private val serverParams: StdioServerParameters,
    private val input: Source,
    private val output: Sink
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

//        val envMap = serverParams.env ?: getDefaultEnvironment()
//        val pb = ProcessBuilder(listOf(serverParams.command) + serverParams.args)
//        val environment = pb.environment()
//        environment.clear()
//        environment.putAll(envMap)

//        when (serverParams.stderr) {
//            StdioOption.INHERIT -> pb.redirectError(ProcessBuilder.Redirect.INHERIT)
//            StdioOption.PIPE -> pb.redirectError(ProcessBuilder.Redirect.PIPE)
//            StdioOption.IGNORE, null -> pb.redirectError(ProcessBuilder.Redirect.DISCARD)
//        }

//        val p = try {
//            pb.start()
//        } catch (e: Throwable) {
//            throw RuntimeException("Failed to start process", e)
//        }
//        process = p

//        if (p.inputStream == null || p.outputStream == null) {
//            p.destroy()
//            throw RuntimeException("Process input or output stream is null")
//        }

//        val inputStream = p.inputStream.buffered()
//        val outputStream = p.outputStream.bufferedWriter(UTF_8)

        val inputStream = input.asInputStream()
        val outputStream = output.asOutputStream().bufferedWriter(UTF_8)

        job = scope.launch {
            val readJob = launch {
                try {
                    val buffer = ByteArray(8192)
                    while (isActive) {
                        val bytesRead = inputStream.read(buffer)
                        if (bytesRead == -1) break
                        if (bytesRead > 0) {
                            readBuffer.append(buffer.copyOf(bytesRead))
                            processReadBuffer()
                        }
//                        val line = inputStream.readLine() ?: break
//                        readBuffer.append(line.toByteArray())
//                        processReadBuffer()
                    }
                } catch (e: Throwable) {
                    if (isActive) {
                        onError?.invoke(e)
                    }
                } finally {
                    inputStream.close()
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
                    if (isActive) onError?.invoke(e)
                } finally {
                    outputStream.close()
                }
            }

//            val exitJob = launch {
//                p.waitFor()
//                onClose?.invoke()
//            }

            readJob.join()
            writeJob.cancelAndJoin()
            onClose?.invoke()
//            exitJob.join()
        }
    }

    override suspend fun send(message: JSONRPCMessage) {
//        CompletableDeferred<Unit>()
//        if (process == null) {
//            throw IllegalStateException("Not connected")
//        }
        if (!started) {
            throw IllegalStateException("Transport not started")
        }

        sendChannel.send(message)
    }

    override suspend fun close() {
//        process?.destroy()
//        process = null
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