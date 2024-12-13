package client

import JSONRPCMessage
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.ClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import shared.McpJson
import shared.Transport
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.properties.Delegates

const val MCP_SUBPROTOCOL = "mcp";

/**
 * Client transport for WebSocket: this will connect to a server over the WebSocket protocol.
 */
class WebSocketMcpClientTransport(
    private val client: HttpClient,
    private val urlString: String?,
    private val requestBuilder: HttpRequestBuilder.() -> Unit = {},
) : Transport {
    private val scope by lazy {
        CoroutineScope(session.coroutineContext + SupervisorJob())
    }

    private val initialized = AtomicBoolean(false)
    private var session: ClientWebSocketSession by Delegates.notNull()

    override var onClose: (() -> Unit)? = null
    override var onError: ((Throwable) -> Unit)? = null
    override var onMessage: (suspend ((JSONRPCMessage) -> Unit))? = null

    override suspend fun start() {
        if (!initialized.compareAndSet(false, true)) {
            error(
                "WebSocketClientTransport already started! " +
                        "If using Client class, note that connect() calls start() automatically.",
            )
        }

        session = urlString?.let {
            client.webSocketSession(it) {
                requestBuilder()

                header(HttpHeaders.SecWebSocketProtocol, MCP_SUBPROTOCOL)
            }
        } ?: client.webSocketSession {
            requestBuilder()

            header(HttpHeaders.SecWebSocketProtocol, MCP_SUBPROTOCOL)
        }

        scope.launch(CoroutineName("WebSocketMcpClientTransport.collect#${hashCode()}")) {
            while (true) {
                val message = session.incoming.receive()
                if (message !is Frame.Text) {
                    val e = IllegalArgumentException("Expected text frame, got ${message::class.simpleName}: $message")
                    onError?.invoke(e)
                    throw e
                }


                try {
                    val message = McpJson.decodeFromString<JSONRPCMessage>(message.readText())
                    onMessage?.invoke(message)
                } catch (e: Exception) {
                    onError?.invoke(e)
                    throw e
                }
            }
        }

        @OptIn(InternalCoroutinesApi::class)
        session.coroutineContext.job.invokeOnCompletion(onCancelling = true) {
            if (it != null) {
                onError?.invoke(it)
            } else {
                onClose?.invoke()
            }
        }
    }

    override suspend fun send(message: JSONRPCMessage) {
        if (!initialized.get()) {
            error("Not connected")
        }

        session.outgoing.send(Frame.Text(McpJson.encodeToString(message)))
    }

    override suspend fun close() {
        if (!initialized.get()) {
            error("Not connected")
        }

        session.close()
    }
}
