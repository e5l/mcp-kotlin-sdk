package org.jetbrains.kotlinx.mcp.client

import org.jetbrains.kotlinx.mcp.JSONRPCMessage
import io.ktor.client.*
import io.ktor.client.plugins.sse.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import org.jetbrains.kotlinx.mcp.shared.McpJson
import org.jetbrains.kotlinx.mcp.shared.Transport
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.properties.Delegates
import kotlin.time.Duration

/**
 * Client transport for SSE: this will connect to a server using Server-Sent Events for receiving
 * messages and make separate POST requests for sending messages.
 */
class SSEClientTransport(
    private val client: HttpClient,
    private val urlString: String?,
    private val reconnectionTime: Duration? = null,
    private val requestBuilder: HttpRequestBuilder.() -> Unit = {},
) : Transport {
    private val scope by lazy {
        CoroutineScope(session.coroutineContext + SupervisorJob())
    }

    private val initialized = AtomicBoolean(false)
    private var session: ClientSSESession by Delegates.notNull()
    private val endpoint = CompletableDeferred<String>()

    override var onClose: (() -> Unit)? = null
    override var onError: ((Throwable) -> Unit)? = null
    override var onMessage: (suspend ((JSONRPCMessage) -> Unit))? = null

    var job: Job? = null

    val baseUrl by lazy {
        session.call.request.url.toString().removeSuffix("/")
    }

    override suspend fun start() {
        if (!initialized.compareAndSet(false, true)) {
            error(
                "SSEClientTransport already started! " +
                        "If using Client class, note that connect() calls start() automatically.",
            )
        }

        session = urlString?.let {
            client.sseSession(
                urlString = it,
                reconnectionTime = reconnectionTime,
                block = requestBuilder,
            )
        } ?: client.sseSession(
            reconnectionTime = reconnectionTime,
            block = requestBuilder,
        )

        job = scope.launch(CoroutineName("SseMcpClientTransport.collect#${hashCode()}")) {
            session.incoming.collect { event ->
                when (event.event) {
                    "error" -> {
                        val e = IllegalStateException("SSE error: ${event.data}")
                        onError?.invoke(e)
                        throw e
                    }

                    "open" -> {
                        // The connection is open, but we need to wait for the endpoint to be received.
                    }

                    "endpoint" -> {
                        try {
                            val eventData = event.data ?: ""

                            // check url correctness
                            val maybeEndpoint = Url("$baseUrl/$eventData")

                            endpoint.complete(maybeEndpoint.toString())
                        } catch (e: Exception) {
                            onError?.invoke(e)
                            close()
                            error(e)
                        }
                    }

                    else -> {
                        try {
                            val message = McpJson.decodeFromString<JSONRPCMessage>(event.data ?: "")
                            onMessage?.invoke(message)
                        } catch (e: Exception) {
                            onError?.invoke(e)
                        }
                    }
                }
            }
        }

        endpoint.await()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override suspend fun send(message: JSONRPCMessage) {
        if (!endpoint.isCompleted) {
            error("Not connected")
        }

        try {
            val response = client.post(endpoint.getCompleted()) {
                headers.append(HttpHeaders.ContentType, ContentType.Application.Json)
                setBody(McpJson.encodeToString(message))
            }

            if (!response.status.isSuccess()) {
                val text = response.bodyAsText()
                error("Error POSTing to endpoint (HTTP ${response.status}): $text")
            }
        } catch (e: Exception) {
            onError?.invoke(e)
            throw e
        }
    }

    override suspend fun close() {
        if (!initialized.get()) {
            error("SSEClientTransport is not initialized!")
        }

        session.cancel()
        onClose?.invoke()
        job?.cancelAndJoin()
    }
}
