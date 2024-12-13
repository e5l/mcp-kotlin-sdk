package shared

import CancelledNotification
import ErrorCode
import JSONRPCError
import JSONRPCNotification
import JSONRPCRequest
import JSONRPCResponse
import McpError
import Method
import Notification
import PingRequest
import Progress
import ProgressNotification
import Request
import RequestId
import RequestResult
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import toJSON
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Callback for progress notifications.
 */
typealias ProgressCallback = (Progress) -> Unit

class AbortSignal {

    fun throwIfAborted() {
        TODO("Not yet implemented")
    }

    fun addEventListener(event: String, block: suspend () -> Unit) {}

    var reason: Throwable? = null
    var aborted: Boolean = false
}

internal val McpJson by lazy {
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }
}

/**
 * Additional initialization options.
 */
open class ProtocolOptions(
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
    var enforceStrictCapabilities: Boolean = false,

    var signal: AbortSignal? = null,

    var timeout: Duration = DEFAULT_REQUEST_TIMEOUT
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
    val signal: AbortSignal = AbortSignal(),

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


class AbortController {
    val signal = AbortSignal()

    fun abort(reason: String? = null) {}
}

val COMPLETED = CompletableDeferred(Unit).also { it.complete(Unit) }

/**
 * Implements MCP protocol framing on top of a pluggable transport, including
 * features like request/response linking, notifications, and progress.
 */
abstract class Protocol<SendRequestT : Request, SendNotificationT : Notification, SendResultT : RequestResult>(
    @PublishedApi internal val _options: ProtocolOptions?
) {
    var transport: Transport? = null
        private set

    private val requestHandlers: MutableMap<String, suspend (request: JSONRPCRequest, extra: RequestHandlerExtra) -> SendResultT?> =
        mutableMapOf()
    private val requestHandlerAbortControllers: MutableMap<RequestId, AbortController> = mutableMapOf()
    val notificationHandlers: MutableMap<String, suspend (notification: JSONRPCNotification) -> Unit> =
        mutableMapOf()

    @PublishedApi
    internal val responseHandlers: MutableMap<Long, (response: JSONRPCResponse?, error: Exception?) -> Unit> =
        mutableMapOf()

    @PublishedApi
    internal val progressHandlers: MutableMap<Long, ProgressCallback> = mutableMapOf()

    /**
     * Callback for when the connection is closed for any reason.
     *
     * This is invoked when close() is called as well.
     */
    open fun onclose() {}

    /**
     * Callback for when an error occurs.
     *
     * Note that errors are not necessarily fatal they are used for reporting any kind of exceptional condition out of band.
     */
    open fun onerror(error: Throwable) {}

    /**
     * A handler to invoke for any request types that do not have their own handler installed.
     */
    var fallbackRequestHandler: (suspend (request: JSONRPCRequest, extra: RequestHandlerExtra) -> SendResultT?)? = null

    /**
     * A handler to invoke for any notification types that do not have their own handler installed.
     */
    var fallbackNotificationHandler: (suspend (notification: JSONRPCNotification) -> Unit)? = null

    init {
        setNotificationHandler<CancelledNotification>(Method.Defined.NotificationsCancelled) { notification ->
            val controller: AbortController? = this.requestHandlerAbortControllers.get(
                notification.params.requestId,
            )
            controller?.abort(notification.params.reason)
            COMPLETED
        }

        setNotificationHandler<ProgressNotification>(Method.Defined.NotificationsProgress) { notification ->
            this.onProgress(notification)
            COMPLETED
        }

        setRequestHandler<PingRequest>(Method.Defined.Ping) { request, _ ->
            null
        }
    }

    /**
     * Attaches to the given transport, starts it, and starts listening for messages.
     *
     * The Protocol object assumes ownership of the Transport, replacing any callbacks that have already been set, and expects that it is the only user of the Transport instance going forward.
     */
    open suspend fun connect(transport: Transport) {
        this.transport = transport
        transport.onClose = {
            this.onClose()
        }

        transport.onError = {
            this.onError(it)
        }

        transport.onMessage = { message ->
            when (message) {
                is JSONRPCResponse -> onResponse(message, null)
                is JSONRPCRequest -> onRequest(message)
                is JSONRPCNotification -> onNotification(message)
            }
        }

        return transport.start()
    }

    private fun onClose() {
        val error = McpError(ErrorCode.Defined.ConnectionClosed.code, "Connection closed")
        for (handler in responseHandlers.values) {
            handler(null, error)
        }

        responseHandlers.clear()
        progressHandlers.clear()
        transport = null
        onclose()
    }

    private fun onError(error: Throwable) {
        onerror(error)
    }

    private suspend fun onNotification(notification: JSONRPCNotification) {
        val function = notificationHandlers[notification.method]
        val property = fallbackNotificationHandler
        val handler = function ?: property

        if (handler == null) return
        handler(notification)

//        // Starting with Deferred.resolve() puts any synchronous errors into the monad as well.
//        Deferred.resolve().then(() -> handler(notification))
//        .catch((error) ->
//        this._onerror(
//            Error("Uncaught error in notification handler: ${error}"),
//        )
//        )
    }

    private fun onRequest(request: JSONRPCRequest) {
        val handler = requestHandlers[request.method] ?: this.fallbackRequestHandler

        if (handler === null) {
//            this._transport?.send({
//                jsonrpc: "2.0",
//                id: request.id,
//                error: {
//                code: ErrorCode.MethodNotFound,
//                message: "Method not found",
//            },
//            }).catch((error) ->
//            this._onerror(
//                Error("Failed to send an error response: ${error}"),
//            ),
//            )
            return
        }

        val abortController = AbortController()
        this.requestHandlerAbortControllers[request.id] = abortController

        // Starting with Deferred.resolve() puts any synchronous errors into the monad as well.
        GlobalScope.launch {
            try {
                val result = handler(request, RequestHandlerExtra(signal = abortController.signal))
                if (abortController.signal.aborted) {
                    return@launch
                }

//                val sendResult = _transport?.send({
//                    result,
//                    jsonrpc: "2.0",
//                    id: request.id,
//                })
            } catch (cause: Throwable) {
                if (abortController.signal.aborted) {
                    return@launch
                }

//                val sendResult = _transport?.send({
//                    result,
//                    jsonrpc: "2.0",
//                    id: request.id,
//                })
            } finally {
                requestHandlerAbortControllers.remove(request.id)
            }
        }
    }

    private fun onProgress(notification: ProgressNotification) {
        val params = notification.params
        val progress = params.progress
        val total = params.total
        val progressToken = params.progressToken

        val handler = this.progressHandlers[progressToken]
        if (handler == null) {
            this.onError(
                Error(
                    "Received a progress notification for an unknown token: ${McpJson.encodeToString(notification)}",
                ),
            )
            return
        }

        handler.invoke(Progress(progress, total))
    }

    private fun onResponse(response: JSONRPCResponse?, error: JSONRPCError?) {
        val messageId = response?.id
        val handler = this.responseHandlers[messageId]
        if (handler == null) {
            this.onError(Error("Received a response for an unknown message ID: ${McpJson.encodeToString(response)}"))
            return
        }

        this.responseHandlers.remove(messageId)
        this.progressHandlers.remove(messageId)
        if (response != null) {
            handler(response, null)
        } else {
            check(error != null)
            val error = McpError(
                error.code.code,
                error.message,
                error.data,
            )

            handler(null, error)
        }
    }

    /**
     * Closes the connection.
     */
    suspend fun close() {
        transport?.close()
    }

    /**
     * A method to check if a capability is supported by the remote side, for the given method to be called.
     *
     * This should be implemented by subclasses.
     */
    abstract fun assertCapabilityForMethod(method: Method)

    /**
     * A method to check if a notification is supported by the local side, for the given method to be sent.
     *
     * This should be implemented by subclasses.
     */
    protected abstract fun assertNotificationCapability(method: Method)

    /**
     * A method to check if a request handler is supported by the local side, for the given method to be handled.
     *
     * This should be implemented by subclasses.
     */
    protected abstract fun assertRequestHandlerCapability(method: Method)

    fun clearTimeout(id: Any?) {
        TODO()
    }

    fun setTimeout(block: suspend () -> Unit, timeout: Duration): Any {
        return Unit
    }

    /**
     * Sends a request and wait for a response.
     *
     * Do not use this method to emit notifications! Use notification() instead.
     */
    suspend fun <T : RequestResult> request(
        request: SendRequestT,
        options: RequestOptions? = null,
    ): T {
        val result = CompletableDeferred<T>()
        val transport = this@Protocol.transport ?: throw Error("Not connected")

        if (_options?.enforceStrictCapabilities == true) {
            assertCapabilityForMethod(request.method)
        }

        options?.signal?.throwIfAborted()

        val message = request.toJSON()
        val messageId = message.id

        if (options?.onProgress != null) {
            progressHandlers[messageId] = options.onProgress
//            request.progressToken = messageId
//                jsonrpcRequest.params = {
//                    ...request.params,
//                    _meta: { progressToken: messageId }, TODO!!!
//                }
        }

        responseHandlers.set(messageId, { response, error ->
            if (options?.signal?.aborted == true) {
                return@set
            }

            if (error != null) {
                result.completeExceptionally(error)
                return@set
            }

            try {
                result.complete(response!!.result as T)
            } catch (error: Throwable) {
                result.completeExceptionally(error)
            }
        })

        val cancel: suspend (Throwable) -> Unit = { reason: Throwable ->
            responseHandlers.remove(messageId)
            progressHandlers.remove(messageId)

            val notification = CancelledNotification(
                params = CancelledNotification.Params(requestId = messageId, reason = reason.message ?: "Unknown")
            )

            val serialized = JSONRPCNotification(
                notification.method.value,
                params = McpJson.encodeToJsonElement(notification.params) as JsonObject
            )
            transport.send(serialized)

            result.completeExceptionally(reason)
            Unit
        }

        val timeout = options?.timeout ?: DEFAULT_REQUEST_TIMEOUT
        try {
            withTimeout(timeout) {
                this@Protocol.transport!!.send(message)
            }
            return result.await() as T
        } catch (cause: TimeoutCancellationException) {
            cancel(
                McpError(
                    ErrorCode.Defined.RequestTimeout.code,
                    "Request timed out",
                    JsonObject(mutableMapOf("timeout" to JsonPrimitive(timeout.inWholeMilliseconds)))
                ),
            )
            result.cancel(cause)
            throw cause
        }
    }

    /**
     * Emits a notification, which is a one-way message that does not expect a response.
     */
    suspend fun notification(notification: SendNotificationT) {
        val transport = this.transport ?: error("Not connected")
        assertNotificationCapability(notification.method)

        val message = JSONRPCNotification(
            notification.method.value,
            params = McpJson.encodeToJsonElement<Notification>(notification) as JsonObject,
        )
        transport.send(message)
    }

    /**
     * Registers a handler to invoke when this protocol object receives a request with the given method.
     *
     * Note that this will replace any previous request handler for the same method.
     */
    fun <T> setRequestHandler(
        method: Method,
        block: suspend (T, RequestHandlerExtra) -> SendResultT?
    ) {
        assertRequestHandlerCapability(method)

        requestHandlers[method.value] = { a, b ->
            block(a as T, b)
        }
    }

    /**
     * Removes the request handler for the given method.
     */
    fun removeRequestHandler(method: Method) {
        requestHandlers.remove(method.value)
    }

    /**
     * Registers a handler to invoke when this protocol object receives a notification with the given method.
     *
     * Note that this will replace any previous notification handler for the same method.
     */
    fun <T : Notification> setNotificationHandler(method: Method, handler: (notification: T) -> Deferred<Unit>) {
        this.notificationHandlers[method.value] = {
            handler(it as T)
        }
    }

    /**
     * Removes the notification handler for the given method.
     */
    fun removeNotificationHandler(method: Method) {
        this.notificationHandlers.remove(method.value)
    }
}
