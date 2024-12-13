package server

import JSONRPCMessage
import io.ktor.client.request.HttpRequest
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLPath
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.sse.ServerSSESession
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.job
import kotlinx.serialization.encodeToString
import shared.McpJson
import shared.Transport
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private const val MAXIMUM_MESSAGE_SIZE = "4mb";

/**
 * Server transport for SSE: this will send messages over an SSE connection and receive messages from HTTP POST requests.
 *
 * Creates a new SSE server transport, which will direct the client to POST messages to the relative or absolute URL identified by `_endpoint`.
 */
class SSEServerTransport(
    private val endpoint: String,
    private val session: ServerSSESession
) : Transport {
    private val initialized = atomic(false)

    @OptIn(ExperimentalUuidApi::class)
    val sessionId: String = Uuid.random().toString()

    override var onClose: (() -> Unit)? = null
    override var onError: ((Throwable) -> Unit)? = null
    override var onMessage: (suspend ((JSONRPCMessage) -> Unit))? = null

    /**
     * Handles the initial SSE connection request.
     *
     * This should be called when a GET request is made to establish the SSE stream.
     */
    override suspend fun start() {
        if (!initialized.compareAndSet(expect = false, update = true)) {
            throw error("SSEServerTransport already started! If using Server class, note that connect() calls start() automatically.")
        }

        // Send the endpoint event
        session.send(
            event = "endpoint",
            data = "${endpoint.encodeURLPath()}?sessionId=${sessionId}",
        )

        @OptIn(InternalCoroutinesApi::class)
        session.call.coroutineContext.job.invokeOnCompletion(onCancelling = true) {
            onClose?.invoke()
        }
    }

    /**
     * Handles incoming POST messages.
     *
     * This should be called when a POST request is made to send a message to the server.
     */
    suspend fun handlePostMessage(call: ApplicationCall) {
        if (!initialized.value) {
            val message = "SSE connection not established";
            call.respondText(message, status = HttpStatusCode.InternalServerError)
            onError?.invoke(IllegalStateException(message))
        }

        val body = try {
            val ct = call.request.contentType()
            if (ct != ContentType.Application.Json) {
                error("Unsupported content-type: $ct")
            }

            call.receiveText()
        } catch (e: Exception) {
            call.respondText("Invalid message: ${e.message}", status = HttpStatusCode.BadRequest)
            onError?.invoke(e)
            return
        }

        try {
            handleMessage(body)
        } catch (_ : Exception) {
            call.respondText("Invalid message: $body", status = HttpStatusCode.BadRequest)
            return
        }

        call.respondText("Accepted", status = HttpStatusCode.Accepted)
    }

    /**
     * Handle a client message, regardless of how it arrived.
     * This can be used to inform the server of messages that arrive via a means different from HTTP POST.
     */
    suspend fun handleMessage(message: String) {
        try {
            val parsedMessage = McpJson.decodeFromString<JSONRPCMessage>(message)
            onMessage?.invoke(parsedMessage)
        } catch (e : Exception) {
            onError?.invoke(e)
            throw e
        }
    }

    override suspend fun close() {
        session.close()
        onClose?.invoke()
    }

    override suspend fun send(message: JSONRPCMessage) {
        if (!initialized.value) {
            throw error("Not connected")
        }

        session.send(
            event = "message",
            data = McpJson.encodeToString(message),
        )
    }
}
