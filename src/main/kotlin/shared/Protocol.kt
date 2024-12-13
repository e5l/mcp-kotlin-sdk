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

    fun addEventListener(event: String, callback: Any) {}

    var reason: Throwable? = null
    var aborted: Boolean = false
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

    var onprogress: ProgressCallback? = null,

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
//    @PublishedApi
    internal var _transport: Transport? = null

    @PublishedApi
    internal var _requestMessageId = 0
    private var _requestHandlers: MutableMap<Method, (request: JSONRPCRequest, extra: RequestHandlerExtra) -> Deferred<SendResultT>> =
        mutableMapOf()
    private var _requestHandlerAbortControllers: MutableMap<RequestId, AbortController> = mutableMapOf()
    var _notificationHandlers: MutableMap<Method, (notification: JSONRPCNotification) -> Deferred<Unit>> =
        mutableMapOf()

    @PublishedApi
    internal var _responseHandlers: MutableMap<Int, (response: JSONRPCResponse?, error: Exception?) -> Unit> =
        mutableMapOf()

    @PublishedApi
    internal var _progressHandlers: MutableMap<Int, ProgressCallback> = mutableMapOf()

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
    var fallbackRequestHandler: ((request: JSONRPCRequest, extra: RequestHandlerExtra) -> Deferred<SendResultT>)? = null

    /**
     * A handler to invoke for any notification types that do not have their own handler installed.
     */
    var fallbackNotificationHandler: ((notification: Notification) -> Deferred<Unit>)? = null

    init {
        setNotificationHandler<CancelledNotification>(Method.Defined.NotificationsCancelled) { notification ->
            val controller: AbortController? = this._requestHandlerAbortControllers.get(
                notification.params.requestId,
            )
            controller?.abort(notification.params.reason)
            COMPLETED
        }

        setNotificationHandler<ProgressNotification>(Method.Defined.NotificationsProgress) { notification ->
            this._onprogress(notification)
            COMPLETED
        }

        setRequestHandler<PingRequest>(Method.Defined.Ping) { _request ->
            // Automatic pong by default.
            // TODO
            COMPLETED
        }
    }

    /**
     * Attaches to the given transport, starts it, and starts listening for messages.
     *
     * The Protocol object assumes ownership of the Transport, replacing any callbacks that have already been set, and expects that it is the only user of the Transport instance going forward.
     */
    open fun connect(transport: Transport): Deferred<Unit> {
        transport.onClose = {
            this._onclose()
        }

        transport.onError = {
            this._onerror(it)
        }

        transport.onMessage = { message ->
            when (message) {
                is JSONRPCResponse -> _onresponse(message, null)
                is JSONRPCRequest -> _onrequest(message)
                is JSONRPCNotification -> _onnotification(message)
                is JSONRPCError -> error(message.error.message)
            }
        }

        return transport.start()
    }

    private fun _onclose() {
        val responseHandlers: MutableMap<Int, (JSONRPCResponse?, Exception?) -> Unit> = this._responseHandlers
        this._responseHandlers = mutableMapOf()
        this._progressHandlers.clear()
        this._transport = null
        this.onclose()

        val error = McpError(ErrorCode.Defined.ConnectionClosed.code, "Connection closed")
        for (handler in responseHandlers.values) {
            handler(null, error)
        }
    }

    private fun _onerror(error: Throwable) {
        onerror(error)
    }

    private fun _onnotification(notification: JSONRPCNotification) {
        val function = _notificationHandlers[notification.method]
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

    private fun _onrequest(request: JSONRPCRequest) {
        val handler = _requestHandlers[request.method] ?: this.fallbackRequestHandler

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
        this._requestHandlerAbortControllers[request.id] = abortController

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
                _requestHandlerAbortControllers.remove(request.id)
            }
        }
    }

    private fun _onprogress(notification: ProgressNotification) {
        val params = notification.params
        val progress = params.progress
        val total = params.total
        val progressToken = params.progressToken

        val handler = this._progressHandlers[progressToken.toInt()]
        if (handler == null) {
            this._onerror(
                Error(
                    "Received a progress notification for an unknown token: ${Json.encodeToString(notification)}",
                ),
            )
            return
        }

        handler.invoke(Progress(progress, total))
    }

    private fun _onresponse(response: JSONRPCResponse?, error: JSONRPCError?) {
        val messageId = response?.id?.toInt()
        val handler = this._responseHandlers[messageId]
        if (handler == null) {
            this._onerror(Error("Received a response for an unknown message ID: ${Json.encodeToString(response)}"))
            return
        }

        this._responseHandlers.remove(messageId)
        this._progressHandlers.remove(messageId)
        if (response != null) {
            handler(response, null)
        } else {
            check(error != null)
            val error = McpError(
                error.error.code.code,
                error.error.message,
                error.error.data,
            )

            handler(null, error)
        }
    }

    fun get_transport(): Transport? {
        return this._transport
    }

    /**
     * Closes the connection.
     */
    fun close(): Deferred<Unit> {
        return this._transport!!.close()
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
    protected abstract fun assertRequestHandlerCapability(method: String)

    fun clearTimeout(id: Any?) {
        TODO()
    }

    fun setTimeout(block: () -> Unit, timeout: Duration): Any {
        return Unit
    }

    /**
     * Sends a request and wait for a response.
     *
     * Do not use this method to emit notifications! Use notification() instead.
     */
    fun <T: RequestResult> request(
        request: SendRequestT,
        options: RequestOptions = RequestOptions(),
    ): Deferred<T> {
        val result = CompletableDeferred<RequestResult>()
        val transport = this@Protocol._transport ?: throw Error("Not connected")
        val options = this@Protocol._options

        if (options?.enforceStrictCapabilities == true) {
            assertCapabilityForMethod(request.method)
        }

        options?.signal?.throwIfAborted()

        val messageId = _requestMessageId++
        val jsonrpcRequest: JSONRPCRequest = TODO()
//            {
//                ...request,
//                jsonrpc: "2.0",
//                id: messageId,
//            }

        if (options?.onprogress != null) {
            _progressHandlers[messageId] = options.onprogress!!
//                jsonrpcRequest.params = {
//                    ...request.params,
//                    _meta: { progressToken: messageId },
//                }
        }

        var timeoutId: Any? = null


        _responseHandlers.set(messageId, { response, error ->
            if (timeoutId !== null) {
                clearTimeout(timeoutId)
            }

            if (options?.signal?.aborted == true) {
                return@set
            }

            if (error != null) {
                result.completeExceptionally(error)
                return@set
            }

            try {
                result.complete(response!!.result)
            } catch (error: Throwable) {
                result.completeExceptionally(error)
            }
        })

        val cancel = { reason: Throwable ->
            _responseHandlers.remove(messageId)
            _progressHandlers.remove(messageId)

            _transport?.send(TODO())
//                {
//                jsonrpc: "2.0",
//                method: "notifications/cancelled",
//                params: {
//                requestId: messageId,
//                reason: String(reason),
//            },
//            }
//            ).catch((error) ->
//            this._onerror(new Error (`Failed to send cancellation: ${error}`)),
//            )

            result.completeExceptionally(reason)
            Unit
        }

        options?.signal?.addEventListener("abort", {
            if (timeoutId !== null) {
                clearTimeout(timeoutId)
            }

            cancel(options.signal?.reason ?: Exception("Aborted"))
        })

        val timeout = options?.timeout ?: DEFAULT_REQUEST_TIMEOUT
        timeoutId = setTimeout(
            {
                cancel(
                    McpError(
                        ErrorCode.Defined.RequestTimeout.code,
                        "Request timed out",
                        JsonObject(mutableMapOf("timeout" to JsonPrimitive(timeout.inWholeMilliseconds)))
                    ),
                )
            },
            timeout,
        )

        _transport!!.send(jsonrpcRequest).invokeOnCompletion { error ->
            if (timeoutId !== null) {
                clearTimeout(timeoutId)
            }
            result.cancel("", error)
        }
    }

    /**
     * Emits a notification, which is a one-way message that does not expect a response.
     */
    fun notification(notification: SendNotificationT): Deferred<Unit> {
        val transport = this._transport ?: error("Not connected")

        assertNotificationCapability(notification.method)


        val jsonrpcNotification: JSONRPCNotification = TODO()
        /**{
        ...notification,
        jsonrpc: "2.0",
        }**/

        return transport.send(jsonrpcNotification)
    }

    /**
     * Registers a handler to invoke when this protocol object receives a request with the given method.
     *
     * Note that this will replace any previous request handler for the same method.
     */
    fun <T : JSONRPCRequest> setRequestHandler(method: Method, block: (T) -> Deferred<Unit>) {
        _requestHandlers[method] = { a, b ->
            // TODO: b
            block(a as T) as Deferred<SendResultT>
        }
    }

    /**
     * Removes the request handler for the given method.
     */
    fun removeRequestHandler(method: Method) {
        _requestHandlers.remove(method)
    }

    /**
     * Registers a handler to invoke when this protocol object receives a notification with the given method.
     *
     * Note that this will replace any previous notification handler for the same method.
     */
    fun <T : Notification> setNotificationHandler(method: Method, handler: (notification: T) -> Deferred<Unit>) {
        this._notificationHandlers[method] = {
            handler(it as T)
        }
    }

    /**
     * Removes the notification handler for the given method.
     */
    fun removeNotificationHandler(method: Method) {
        this._notificationHandlers.remove(method)
    }
}
