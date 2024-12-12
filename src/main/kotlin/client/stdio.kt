package client

import JSONRPCMessage
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import shared.ReadBuffer
import shared.Transport
import shared.serializeMessage
import kotlin.coroutines.CoroutineContext
import kotlin.text.Charsets.UTF_8

data class StdioServerParameters(
    val command: String,
    val args: List<String> = emptyList(),
    val env: Map<String, String>? = null,
    val stderr: Any? = "inherit" // TODO
)

private fun getDefaultEnvironment(): Map<String, String> {
    val safeKeys = if (System.getProperty("os.name").lowercase().contains("win")) {
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
    for (key in safeKeys) {
        val value = System.getenv(key) ?: continue
        if (!value.startsWith("()")) {
            env[key] = value
        }
    }
    return env
}


class StdioClientTransport(
    private val serverParams: StdioServerParameters,
) : Transport {
    private val jsonRpcContext: CoroutineContext = Dispatchers.IO
    override var onclose: (() -> Unit)? = null
    override var onerror: ((Throwable) -> Unit)? = null
    override var onmessage: (CoroutineScope.(JSONRPCMessage) -> Unit)? = null

    private var process: Process? = null
    private val scope = CoroutineScope(jsonRpcContext + SupervisorJob())
    private var job: Job? = null
    private var started = false
    private val sendChannel = Channel<JSONRPCMessage>(Channel.UNLIMITED)

    private val readBuffer = ReadBuffer()

    override fun start(): Deferred<Unit> {
        val deferred = CompletableDeferred<Unit>()

        if (started) {
            deferred.completeExceptionally(IllegalStateException("StdioClientTransport already started!"))
            return deferred
        }
        started = true

        val envMap = serverParams.env ?: getDefaultEnvironment()
        val pb = ProcessBuilder(listOf(serverParams.command) + serverParams.args)
        val environment = pb.environment()
        environment.clear()
        environment.putAll(envMap)

        if (serverParams.stderr == "inherit") {
            pb.redirectError(ProcessBuilder.Redirect.INHERIT)
        }

        val p = try {
            pb.start()
        } catch (e: Throwable) {
            deferred.completeExceptionally(RuntimeException("Failed to start process", e))
            return deferred
        }
        process = p

        if (p.inputStream == null || p.outputStream == null) {
            p.destroy()
            deferred.completeExceptionally(RuntimeException("Process input or output stream is null"))
            return deferred
        }

        val inputStream = p.inputStream.bufferedReader(UTF_8)
        val outputStream = p.outputStream.bufferedWriter(UTF_8)

        job = scope.launch {
            val readJob = launch {
                try {
                    while (isActive) {
                        val line = inputStream.readLine() ?: break
                        readBuffer.append(line.toByteArray())
                        processReadBuffer()
                    }
                } catch (e: Throwable) {
                    if (isActive) {
                        onerror?.invoke(e)
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
                    if (isActive) onerror?.invoke(e)
                } finally {
                    outputStream.close()
                }
            }

            val exitJob = launch {
                p.waitFor()
                onclose?.invoke()
            }

            deferred.complete(Unit)

            readJob.join()
            writeJob.cancelAndJoin()
            exitJob.join()
        }

        return deferred
    }

    override fun send(message: JSONRPCMessage): Deferred<Unit> {
        val deferred = CompletableDeferred<Unit>()
        if (process == null) {
            deferred.completeExceptionally(IllegalStateException("Not connected"))
            return deferred
        }
        scope.launch {
            try {
                sendChannel.send(message)
                deferred.complete(Unit)
            } catch (e: Throwable) {
                deferred.completeExceptionally(e)
            }
        }
        return deferred
    }

    override fun close(): Deferred<Unit> {
        val deferred = CompletableDeferred<Unit>()
        scope.launch {
            try {
                process?.destroy()
                process = null
                readBuffer.clear()
                sendChannel.close()
                job?.cancelAndJoin()
                onclose?.invoke()
                deferred.complete(Unit)
            } catch (e: Throwable) {
                deferred.completeExceptionally(e)
            }
        }
        return deferred
    }

    private fun processReadBuffer() {
        while (true) {
            val msg = readBuffer.readMessage() ?: break
            try {
                onmessage?.invoke(scope, msg)
            } catch (e: Throwable) {
                onerror?.invoke(e)
            }
        }
    }
}