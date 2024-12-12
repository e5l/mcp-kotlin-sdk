package shared

import CancelledNotificationSchema
import JSONRPCNotification
import JSONRPCRequest
import JSONRPCResponse
import Notification
import Request
import RequestId
import ResultSchema
import kotlinx.coroutines.Job
import kotlinx.serialization.json.*
import java.util.concurrent.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Callback for progress notifications.
 */
typealias ProgressCallback = (Progress) -> Unit

typealias AbortSignal = Job
/**
 * Additional initialization options.
 */
data class ProtocolOptions(
    /**
     * Whether to restrict emitted requests to only those that the remote side has indicated
     * that they can handle, through their advertised capabilities.
     *
     * Note that this DOES NOT affect checking of _local_ side capabilities, as it is
     * considered a logic error to mis-specify those.
     *
     * Currently this defaults to false, for backwards compatibility with SDK versions
     * that did not advertise capabilities correctly. In future, this will default to true.
     */
    val enforceStrictCapabilities: Boolean = false
)

/**
 * The default request timeout.
 */
val DEFAULT_REQUEST_TIMEOUT: Duration = 60000.milliseconds

/**
 * Options that can be given per request.
 */
data class RequestOptions(
    /**
     * If set, requests progress notifications from the remote end (if supported).
     * When progress notifications are received, this callback will be invoked.
     */
    val onProgress: ProgressCallback? = null,

    /**
     * Can be used to cancel an in-flight request.
     * This will cause an AbortError to be raised from request().
     */
    val signal: AbortSignal,

    /**
     * A timeout for this request. If exceeded, an McpError with code `RequestTimeout`
     * will be raised from request().
     *
     * If not specified, `DEFAULT_REQUEST_TIMEOUT` will be used as the timeout.
     */
    val timeout: Duration = DEFAULT_REQUEST_TIMEOUT
)

/**
 * Extra data given to request handlers.
 */
data class RequestHandlerExtra(
    /**
     * A cancellation token used to communicate if the request was cancelled from the sender's side.
     */
    val signal: AbortSignal
)

interface AbortController {
    val signal: AbortSignal
    fun abort(reason: String? = null)
}

/**
 * Implements MCP protocol framing on top of a pluggable transport, including
 * features like request/response linking, notifications, and progress.
 */
abstract class Protocol<SendRequestT : Request, SendNotificationT : Notification, SendResultT : ResultSchema>(
    private val options: ProtocolOptions? = null
) {
    @PublishedApi
    internal val notificationHandlers = mutableMapOf<String, suspend (JSONRPCNotification) -> Unit>()
    @PublishedApi
    internal val requestHandlers = mutableMapOf<String, suspend (JSONRPCRequest, RequestHandlerExtra) -> SendResultT>()

    private var transport: Transport? = null
    private var requestMessageId = 0
    private val requestHandlerCancellationTokens = mutableMapOf<RequestId, AbortController>()
    private val responseHandlers = mutableMapOf<Int, (JSONRPCResponse?) -> Unit>()
    private val progressHandlers = mutableMapOf<Int, ProgressCallback>()

    /**
     * Callback for when the connection is closed for any reason.
     */
    var onClose: (() -> Unit)? = null

    /**
     * Callback for when an error occurs.
     */
    var onError: ((Throwable) -> Unit)? = null

    /**
     * A handler to invoke for any request types that do not have their own handler installed.
     */
    var fallbackRequestHandler: (suspend (Request) -> SendResultT)? = null

    /**
     * A handler to invoke for any notification types that do not have their own handler installed.
     */
    var fallbackNotificationHandler: (suspend (Notification) -> Unit)? = null

    init {
        setNotificationHandler<CancelledNotificationSchema> { notification ->
            val tokenSource = requestHandlerCancellationTokens[notification.params.requestId]
            tokenSource?.cancel(notification.params.reason)
        }

        setNotificationHandler(ProgressNotificationSchema) { notification ->
            onProgress(notification as ProgressNotification)
        }

        setRequestHandler(PingRequestSchema) { _ ->
            @Suppress("UNCHECKED_CAST")
            (JsonObject(emptyMap()) as SendResultT)
        }
    }

    /**
     * Attaches to the given transport, starts it, and starts listening for messages.
     *
     * The Protocol object assumes ownership of the Transport, replacing any callbacks
     * that have already been set, and expects that it is the only user of the Transport
     * instance going forward.
     */
    suspend fun connect(newTransport: Transport) {
        transport = newTransport
        transport?.apply {
            onClose = { handleClose() }
            onError = { error -> handleError(error) }
            onMessage = { message ->
                when {
                    !message.containsKey("method") -> handleResponse(message as JSONRPCResponse)
                    message.containsKey("id") -> handleRequest(message as JSONRPCRequest)
                    else -> handleNotification(message as JSONRPCNotification)
                }
            }
        }
        transport?.start()
    }

    private fun handleClose() {
        val handlers = responseHandlers.toMap()
        responseHandlers.clear()
        progressHandlers.clear()
        transport = null
        onClose?.invoke()

        val error = McpError(ErrorCode.ConnectionClosed, "Connection closed")
        handlers.values.forEach { handler ->
            handler(null) // null indicates error
        }
    }

    private fun handleError(error: Throwable) {
        onError?.invoke(error)
    }

    private suspend fun handleNotification(notification: JSONRPCNotification) {
        val handler = notificationHandlers[notification.method] ?: fallbackNotificationHandler

        // Ignore notifications not being subscribed to
        handler ?: return

        try {
            handler(notification)
        } catch (e: Exception) {
            handleError(Error("Uncaught error in notification handler: ${e.message}"))
        }
    }

    private suspend fun handleRequest(request: JSONRPCRequest) {
        val handler = requestHandlers[request.method] ?: fallbackRequestHandler

        if (handler == null) {
            transport?.send(JSONRPCError(
                id = request.id,
                error = ErrorResponse(
                    code = ErrorCode.MethodNotFound,
                    message = "Method not found"
                )
            ))
            return
        }

        val cancellationSource = CancellationTokenSource()
        requestHandlerCancellationTokens[request.id] = cancellationSource

        try {
            val result = handler(request, RequestHandlerExtra(cancellationSource.token))
            if (!cancellationSource.token.isCancelled) {
                transport?.send(JSONRPCResponse(
                    jsonrpc = "2.0",
                    id = request.id,
                    result = result
                ))
            }
        } catch (e: Exception) {
            if (!cancellationSource.token.isCancelled) {
                transport?.send(JSONRPCError(
                    jsonrpc = "2.0",
                    id = request.id,
                    error = ErrorResponse(
                        code = (e as? McpError)?.code ?: ErrorCode.InternalError,
                        message = e.message ?: "Internal error"
                    )
                ))
            }
        } finally {
            requestHandlerCancellationTokens.remove(request.id)
        }
    }

    private fun handleProgress(notification: ProgressNotification) {
        val (progress, total, progressToken) = notification.params
        val handler = progressHandlers[progressToken.toInt()]

        if (handler == null) {
            handleError(Error("Received a progress notification for unknown token: $notification"))
            return
        }

        handler(Progress(progress, total))
    }

    private fun handleResponse(response: JSONRPCResponse) {
        val messageId = response.id.toInt()
        val handler = responseHandlers.remove(messageId)

        if (handler == null) {
            handleError(Error("Received a response for unknown message ID: $response"))
            return
        }

        progressHandlers.remove(messageId)
        handler(response)
    }

    /**
     * A method to check if a capability is supported by the remote side, for the given method to be called.
     */
    protected abstract fun assertCapabilityForMethod(method: String)

    /**
     * A method to check if a notification is supported by the local side, for the given method to be sent.
     */
    protected abstract fun assertNotificationCapability(method: String)

    /**
     * A method to check if a request handler is supported by the local side, for the given method to be handled.
     */
    abstract fun assertRequestHandlerCapability(method: String)

    /**
     * Sends a request and waits for a response.
     *
     * Do not use this method to emit notifications! Use notification() instead.
     */
    suspend fun <T> request(
        request: SendRequestT,
        resultSchema: JsonSchema<T>,
        options: RequestOptions? = null
    ): T {
        val transport = transport ?: throw IllegalStateException("Not connected")

        if (options?.enforceStrictCapabilities == true) {
            assertCapabilityForMethod(request.method)
        }

        options?.signal?.throwIfCancelled()

        val messageId = requestMessageId++
        val jsonrpcRequest = JSONRPCRequest(
            jsonrpc = "2.0",
            id = messageId,
            method = request.method,
            params = if (options?.onProgress != null) {
                request.params.copy(_meta = MetaData(progressToken = messageId))
            } else {
                request.params
            }
        )

        return suspendCancellableCoroutine { continuation ->
            val timeoutJob = if (options?.timeout != null) {
                CoroutineScope(Dispatchers.Default).launch {
                    delay(options.timeout)
                    if (continuation.isActive) {
                        continuation.resumeWithException(
                            McpError(
                                ErrorCode.RequestTimeout,
                                "Request timed out",
                                mapOf("timeout" to options.timeout.inWholeMilliseconds)
                            )
                        )
                    }
                }
            } else null

            options?.onProgress?.let { callback ->
                progressHandlers[messageId] = callback
            }

            responseHandlers[messageId] = { response ->
                timeoutJob?.cancel()
                when {
                    response == null -> {
                        continuation.resumeWithException(
                            McpError(ErrorCode.ConnectionClosed, "Connection closed")
                        )
                    }
                    response is JSONRPCError -> {
                        continuation.resumeWithException(
                            McpError(response.error.code, response.error.message, response.error.data)
                        )
                    }
                    else -> try {
                        val result = resultSchema.parse(response.result)
                        continuation.resume(result)
                    } catch (e: Exception) {
                        continuation.resumeWithException(e)
                    }
                }
            }

            options?.signal?.invokeOnCancellation {
                responseHandlers.remove(messageId)
                progressHandlers.remove(messageId)
                transport.send(JSONRPCNotification(
                    jsonrpc = "2.0",
                    method = "notifications/cancelled",
                    params = CancelledParams(
                        requestId = messageId,
                        reason = it?.message ?: "Cancelled"
                    )
                ))
            }

            launch {
                try {
                    transport.send(jsonrpcRequest)
                } catch (e: Exception) {
                    timeoutJob?.cancel()
                    continuation.resumeWithException(e)
                }
            }
        }
    }

    /**
     * Emits a notification, which is a one-way message that does not expect a response.
     */
    suspend fun notification(notification: SendNotificationT) {
        val transport = transport ?: throw IllegalStateException("Not connected")
        assertNotificationCapability(notification.method)

        val jsonrpcNotification = JSONRPCNotification(
            jsonrpc = "2.0",
            method = notification.method,
            params = notification.params
        )

        transport.send(jsonrpcNotification)
    }

    /**
     * Registers a handler to invoke when this protocol object receives a request with the given method.
     */
    inline fun <reified T> setRequestHandler(
        noinline handler: suspend (T, RequestHandlerExtra) -> SendResultT
    ) {
        val method = T::class.qualifiedName.toString()
        assertRequestHandlerCapability(method)
        requestHandlers[method] = { request, extra ->
            handler(Json.decodeFromString(request), extra)
        }
    }

    /**
     * Removes the request handler for the given method.
     */
    fun removeRequestHandler(method: String) {
        requestHandlers.remove(method)
    }

    /**
     * Registers a handler to invoke when this protocol object receives a notification with the given method.
     */
    inline fun <reified T> setNotificationHandler(
        noinline handler: suspend (T) -> Unit
    ) {
        val name = T::class.qualifiedName.toString()
        notificationHandlers[name] = { notification ->
            val value = Json.decodeFromString<T>(notification)
            handler(notification)
        }
    }

    /**
     * Removes the notification handler for the given method.
     */
    fun removeNotificationHandler(method: String) {
        notificationHandlers.remove(method)
    }

    /**
     * Closes the connection.
     */
    suspend fun close() {
        transport?.close()
    }
}

