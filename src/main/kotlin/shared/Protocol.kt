package shared

import CancelledNotification
import ErrorCode
import JSONRPCNotification
import JSONRPCRequest
import JSONRPCResponse
import Method
import Notification
import PingRequest
import Progress
import ProgressNotification
import Request
import RequestId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
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
    fun abort(reason: String? = null)
}

class ResultSchema

val COMPLETED = CompletableDeferred(Unit).also { it.complete(Unit) }

/**
 * Implements MCP protocol framing on top of a pluggable transport, including
 * features like request/response linking, notifications, and progress.
 */
abstract class Protocol<SendRequestT : Request, SendNotificationT : Notification, SendResultT : ResultSchema>(
    private val _options: ProtocolOptions?
) {
    private var _transport: Transport? = null
    private var _requestMessageId = 0
    private var _requestHandlers: MutableMap<String, (request: JSONRPCRequest, extra: RequestHandlerExtra) -> Deferred<SendResultT>> =
        mutableMapOf()
    private var _requestHandlerAbortControllers: MutableMap<RequestId, AbortController> = mutableMapOf()
    var _notificationHandlers: MutableMap<String, (notification: JSONRPCNotification) -> Deferred<Unit>> =
        mutableMapOf()
    private var _responseHandlers: MutableMap<Int, (response: JSONRPCResponse?, error: Error) -> Unit> = mutableMapOf()
    private var _progressHandlers: MutableMap<Int, ProgressCallback> = mutableMapOf()

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
    open fun onerror(error: Error) {}

    /**
     * A handler to invoke for any request types that do not have their own handler installed.
     */
    open fun fallbackRequestHandler(request: Request): Deferred<SendResultT> {
        error("not implemented")
    }

    /**
     * A handler to invoke for any notification types that do not have their own handler installed.
     */
    open fun fallbackNotificationHandler(notification: Notification): Deferred<Unit> {
        error("not implemented")
    }

    init {
        setNotificationHandler<CancelledNotification> { notification ->
            val controller: AbortController? = this._requestHandlerAbortControllers.get(
                notification.params.requestId,
            )
            controller?.abort(notification.params.reason)
            COMPLETED
        }

        setNotificationHandler<ProgressNotification> { notification ->
            this._onprogress(notification)
            COMPLETED
        }

        setRequestHandler<PingRequest> { _request ->
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
    fun connect(transport: Transport): Deferred<Unit> {
        this._transport = transport
        this._transport.onclose = () -> {
            this._onclose()
        }

        this._transport.onerror = (error: Error) -> {
            this._onerror(error)
        }

        this._transport.onmessage = (message) -> {
            if (!("method" in message)) {
                this._onresponse(message)
            } else if ("id" in message) {
                this._onrequest(message)
            } else {
                this._onnotification(message)
            }
        }

        await this._transport.start()
    }

    private fun _onclose() {
        val responseHandlers = this._responseHandlers
        this._responseHandlers = mutableMapOf()
        this._progressHandlers.clear()
        this._transport = undefined
        this.onclose?.()

        val error = new McpError (ErrorCode.ConnectionClosed, "Connection closed")
        for (val handler of responseHandlers.values()) {
            handler(error)
        }
    }

    private fun _onerror(error: Error) {
        this.onerror?.(error)
    }

    private fun _onnotification(notification: JSONRPCNotification) {
        val handler =
            this._notificationHandlers.get(notification.method) ??
        this.fallbackNotificationHandler

        // Ignore notifications not being subscribed to.
        if (handler === undefined) {
            return
        }

        // Starting with Deferred.resolve() puts any synchronous errors into the monad as well.
        Deferred.resolve().then(() -> handler(notification))
        .catch((error) ->
        this._onerror(
            new Error (`Uncaught error in notification handler: ${error}`),
        ),
        )
    }

    private fun _onrequest(request: JSONRPCRequest) {
        val handler =
            this._requestHandlers.get(request.method) ?? this.fallbackRequestHandler

        if (handler === undefined) {
            this._transport?.send({
                jsonrpc: "2.0",
                id: request.id,
                error: {
                code: ErrorCode.MethodNotFound,
                message: "Method not found",
            },
            }).catch((error) ->
            this._onerror(
                new Error (`Failed to send an error response: ${error}`),
            ),
            )
            return
        }

        val abortController = new AbortController ()
        this._requestHandlerAbortControllers.set(request.id, abortController)

        // Starting with Deferred.resolve() puts any synchronous errors into the monad as well.
        Deferred.resolve().then(() -> handler(request, { signal: abortController.signal }))
        .then(
            (result) -> {
            if (abortController.signal.aborted) {
                return
            }

            return this._transport?.send({
                result,
                jsonrpc: "2.0",
                id: request.id,
            })
        },
        (error) -> {
            if (abortController.signal.aborted) {
                return
            }

            return this._transport?.send({
                jsonrpc: "2.0",
                id: request.id,
                error: {
                code: Number.isSafeInteger(error["code"])
                ? error["code"]
                : ErrorCode.InternalError,
                message: error.message ?? "Internal error",
            },
            })
        },
        )
        .catch((error) ->
        this._onerror(new Error (`Failed to send response: ${error}`)),
        )
        .finally(() -> {
            this._requestHandlerAbortControllers.delete(request.id)
        })
    }

    private fun _onprogress(notification: ProgressNotification) {
        val { progress, total, progressToken } = notification.params
        val handler = this._progressHandlers.get(Number(progressToken))
        if (handler === undefined) {
            this._onerror(
                new Error (`Received a progress notification for an unknown token: ${JSON.stringify(notification)}`,
            ),
            )
            return
        }

        handler({ progress, total })
    }

    private fun _onresponse(response: JSONRPCResponse | JSONRPCError): Unit
    {
        val messageId = response.id const handler = this._responseHandlers.get(Number(messageId))
        if (handler === undefined) {
            this._onerror(
                new Error (`Received a response for an unknown message ID: ${JSON.stringify(response)}`,
            ),
            )
            return
        }

        this._responseHandlers.delete(Number(messageId))
        this._progressHandlers.delete(Number(messageId))
        if ("result" in response) {
            handler(response)
        } else {
            const error = new McpError(
                response.error.code,
                response.error.message,
                response.error.data,
            )
            handler(error)
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
    protected abstract fun assertCapabilityForMethod(method: Method)

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

    /**
     * Sends a request and wait for a response.
     *
     * Do not use this method to emit notifications! Use notification() instead.
     */
    fun request<T extends ZodType <
    object > >(
    request: SendRequestT,
    resultSchema: T,
    options ?: RequestOptions,
    ): Deferred<z.infer<T>>
    {
        return new Deferred ((resolve, reject) -> {
        if (!this._transport) {
            reject(new Error ("Not connected"))
            return
        }

        if (this._options?.enforceStrictCapabilities === true) {
            this.assertCapabilityForMethod(request.method)
        }

        options?.signal?.throwIfAborted()

        const messageId = this._requestMessageId++
        const jsonrpcRequest : JSONRPCRequest = {
            ...request,
            jsonrpc: "2.0",
            id: messageId,
        }

        if (options?.onprogress) {
            this._progressHandlers.set(messageId, options.onprogress)
            jsonrpcRequest.params = {
                ...request.params,
                _meta: { progressToken: messageId },
            }
        }

        let timeoutId : ReturnType <typeof setTimeout> | undefined = undefined

        this._responseHandlers.set(messageId, (response) -> {
        if (timeoutId !== undefined) {
            clearTimeout(timeoutId)
        }

        if (options?.signal?.aborted) {
            return
        }

        if (response instanceof Error) {
            return reject(response)
        }

        try {
            const result = resultSchema . parse (response.result)
            resolve(result)
        } catch (error) {
            reject(error)
        }
    })

        const cancel =(reason: unknown) -> {
        this._responseHandlers.delete(messageId)
        this._progressHandlers.delete(messageId)

        this._transport?.send({
            jsonrpc: "2.0",
            method: "notifications/cancelled",
            params: {
            requestId: messageId,
            reason: String(reason),
        },
        }).catch((error) ->
        this._onerror(new Error (`Failed to send cancellation: ${error}`)),
        )

        reject(reason)
    }

        options?.signal?.addEventListener("abort", () -> {
        if (timeoutId !== undefined) {
            clearTimeout(timeoutId)
        }

        cancel(options?.signal?.reason)
    })

        const timeout = options ?. timeout ?? DEFAULT_REQUEST_TIMEOUT_MSEC
        timeoutId = setTimeout(
            () ->
        cancel(
            new McpError (ErrorCode.RequestTimeout, "Request timed out", { timeout,
            }),
        ),
        timeout,
        )

        this._transport.send(jsonrpcRequest).catch((error) -> {
        if (timeoutId !== undefined) {
            clearTimeout(timeoutId)
        }

        reject(error)
    })
    })
    }

    /**
     * Emits a notification, which is a one-way message that does not expect a response.
     */
    fun notification(notification: SendNotificationT): Deferred<Unit> {
        val transport = this._transport ?: error("Not connected")

        assertNotificationCapability(notification.method)


        val jsonrpcNotification : JSONRPCNotification = TODO() /**{
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
//        fun <T> setRequestHandler<
//                T extends ZodObject<
//        { method: ZodLiteral<string>
//        } >,
//        >(
//                requestSchema: T,
//        handler: (
//        request: z.infer<T>,
//        extra: RequestHandlerExtra,
//        ) -> SendResultT | Deferred<SendResultT>,
//        ): Unit
    fun <T> setRequestHandler(block: (T) -> Deferred<Unit>) {
        val method = TODO("requestSchema.shape.method.value this.assertRequestHandlerCapability(method)")
        this._requestHandlers.set(method, block)
    }

    /**
     * Removes the request handler for the given method.
     */
    fun removeRequestHandler(method: String) {
        this._requestHandlers.remove(method)
    }

    /**
     * Registers a handler to invoke when this protocol object receives a notification with the given method.
     *
     * Note that this will replace any previous notification handler for the same method.
     */
    fun setNotificationHandler(handler: (notification: JSONRPCNotification) -> Deferred<Unit>) {
//        val name = notification
        val name = TODO()
        this._notificationHandlers[name] = handler
    }

    /**
     * Removes the notification handler for the given method.
     */
    fun removeNotificationHandler(method: String) {
        this._notificationHandlers.remove(method)
    }
}
