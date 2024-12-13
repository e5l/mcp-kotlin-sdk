package client

import JSONRPCMessage
import io.ktor.client.HttpClient
import io.ktor.client.engine.apache.Apache
import io.ktor.client.plugins.sse.ClientSSESession
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.sse.sseSession
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Url
import io.ktor.http.isSuccess
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import shared.AbortController
import shared.McpJson
import shared.Transport
import kotlin.coroutines.CoroutineContext
import kotlin.properties.Delegates
import kotlin.time.Duration

/**
 * Client transport for SSE: this will connect to a server using Server-Sent Events for receiving
 * messages and make separate POST requests for sending messages.
 *
 * This uses the EventSource API in browsers. You can install the `eventsource` package for Node.js.
 */
class SSEClientTransport(
    private val url: Url,
    private val reconnectionTime: Duration? = null,
    private val requestBuilder: HttpRequestBuilder.() -> Unit = {},
) : Transport {
    private val jsonRpcContext: CoroutineContext = Dispatchers.IO
    private val scope = CoroutineScope(jsonRpcContext + SupervisorJob())

    private val client by lazy {
        HttpClient(Apache) {
            install(SSE)
        }
    }

    private val initialized = atomic(false)
    private var session: ClientSSESession by Delegates.notNull()
    private val endpoint = CompletableDeferred<Url>()
    private var _abortController: AbortController? = null

    override var onClose: (() -> Unit)? = null
    override var onError: ((Throwable) -> Unit)? = null
    override var onMessage: (suspend ((JSONRPCMessage) -> Unit))? = null

    override suspend fun start() {
        if (!initialized.compareAndSet(expect = false, update = true)) {
            error(
                "SSEClientTransport already started! If using Client class, note that connect() calls start() automatically.",
            )
        }

        this._abortController = AbortController()

        session = client.sseSession(
            urlString = url.toString(),
            reconnectionTime = reconnectionTime,
            block = requestBuilder,
        )

        scope.launch {
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

                            val maybeEndpoint = Url("$url/$eventData")
                            if (maybeEndpoint.origin !== url.origin) {
                                error("Endpoint origin does not match connection origin: ${maybeEndpoint.origin}")
                            }

                            endpoint.complete(maybeEndpoint)
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

    override suspend fun close() {
        if (!initialized.value) {
            error("SSEClientTransport is not initialized!")
        }

        _abortController?.abort()
        session.call.cancel()
        onClose?.invoke()
    }

    override suspend fun send(message: JSONRPCMessage) {
        if (!endpoint.isCompleted) {
            error("Not connected")
        }

        try {
            val response = client.post {
                headers.append("Content-Type", "application/json")
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
}

private val Url.origin get(): String = "$protocol://$host:$port"
