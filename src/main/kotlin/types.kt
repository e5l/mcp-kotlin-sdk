import CancelledNotificationSchema.Params

const val LATEST_PROTOCOL_VERSION = "2024-11-05"

val SUPPORTED_PROTOCOL_VERSIONS = arrayOf(
    LATEST_PROTOCOL_VERSION,
    "2024-10-07",
)

const val JSONRPC_VERSION: String = "2.0"

// line 15
/**
 * A progress token, used to associate progress notifications with the original request.
 */
typealias ProgressTokenSchema = Any

/**
 * An opaque token used to represent a cursor for pagination.
 */
typealias CursorSchema = String

interface PassthroughObject {
    val additionalProperties: Map<String, Any?>
}

interface WithMeta {
    /**
     * This result property is reserved by the protocol to allow clients and servers to attach additional metadata to their responses.
     */
    val _meta: PassthroughObject?
}

interface BaseRequestParamsSchema : PassthroughObject, WithMeta {
    override val _meta: Meta?

    interface Meta : PassthroughObject {
        /**
         * If specified, the caller is requesting out-of-band progress notifications for this request (as represented by notifications/progress). The value of this parameter is an opaque token that will be attached to any subsequent notifications. The receiver is not obligated to provide these notifications.
         */
        val progressToken: ProgressTokenSchema?
    }
}

typealias Request = RequestSchema
interface RequestSchema {
    val method: String
    val params: BaseRequestParamsSchema?
}

interface BaseNotificationParamsSchema : PassthroughObject, WithMeta

typealias Notification = NotificationSchema
interface NotificationSchema : PassthroughObject {
    val method: String
    val params: BaseNotificationParamsSchema?
}

interface ResultSchema : PassthroughObject, WithMeta

/**
 * A uniquely identifying ID for a request in JSON-RPC.
 */
typealias RequestIdSchema = Any

/**
 * TODO
 */
sealed interface JSONRPCMessageSchema

/**
 * A request that expects a response.
 */
typealias JSONRPCRequest = JSONRPCRequestSchema
abstract class JSONRPCRequestSchema : RequestSchema, JSONRPCMessageSchema {
    val jsonrpc: String = JSONRPC_VERSION
    abstract val id: RequestIdSchema
}

/**
 * A notification which does not expect a response.
 */
abstract class JSONRPCNotificationSchema : NotificationSchema, JSONRPCMessageSchema {
    val jsonrpc: String = JSONRPC_VERSION
}

/**
 * A successful (non-error) response to a request.
 */
abstract class JSONRPCResponseSchema : NotificationSchema, JSONRPCMessageSchema {
    val jsonrpc: String = JSONRPC_VERSION
    abstract val id: RequestIdSchema
    abstract val result: ResultSchema
}

/**
 * An incomplete set of error codes that may appear in JSON-RPC responses.
 */
enum class ErrorCode(val code: Int) {
    // SDK error codes
    ConnectionClosed(-1),
    RequestTimeout(-2),

    // Standard JSON-RPC error codes
    ParseError(-32700),
    InvalidRequest(-32600),
    MethodNotFound(-32601),
    InvalidParams(-32602),
    InternalError(-32603),
    ;
}

/**
 * A response to a request that indicates an error occurred.
 */
abstract class JSONRPCErrorSchema : JSONRPCMessageSchema {
    val jsonrpc: String = JSONRPC_VERSION
    abstract val id: RequestIdSchema

    abstract val error: Error

    interface Error {
        /**
         * The error type that occurred.
         */
        val code: Int

        /**
         * A short description of the error. The message SHOULD be limited to a concise single sentence.
         */
        val message: String

        /**
         * Additional information about the error. The value of this member is defined by the sender (e.g. detailed error information, nested errors etc.).
         */
        val data: Any?
    }
}

/* Empty result */
/**
 * A response that indicates success but carries no data.
 */
object EmptyResultSchema : ResultSchema {
    override val additionalProperties: Map<String, Any?> = emptyMap()
    override val _meta: PassthroughObject? = null
}

/* Cancellation */
/**
 * This notification can be sent by either side to indicate that it is cancelling a previously-issued request.
 *
 * The request SHOULD still be in-flight, but due to communication latency, it is always possible that this notification MAY arrive after the request has already finished.
 *
 * This notification indicates that the result will be unused, so any associated processing SHOULD cease.
 *
 * A client MUST NOT attempt to cancel its `initialize` request.
 */
abstract class CancelledNotificationSchema : NotificationSchema {
    final override val method: String = "notifications/cancelled"
    abstract override val params: Params

    interface Params : BaseNotificationParamsSchema {
        /**
         * The ID of the request to cancel.
         *
         * This MUST correspond to the ID of a request previously issued in the same direction.
         */
        val requestId: RequestIdSchema

        /**
         * An optional string describing the reason for the cancellation. This MAY be logged or presented to the user.
         */
        val reason: String?
    }
}

/* Initialization */
/**
 * Describes the name and version of an MCP implementation.
 */
interface ImplementationSchema : PassthroughObject {
    val name: String
    val version: String
}

/**
 * Capabilities a client may support. Known capabilities are defined here, in this schema, but this is not a closed set: any client can define its own, additional capabilities.
 */
interface ClientCapabilitiesSchema : PassthroughObject {
    /**
     * Experimental, non-standard capabilities that the client supports.
     */
    val experimental: PassthroughObject?
    /**
     * Present if the client supports sampling from an LLM.
     */
    val sampling: PassthroughObject?
    /**
     * Present if the client supports listing roots.
     */
    val roots: Roots?

    interface Roots {
        /**
         * Whether the client supports issuing notifications for changes to the roots list.
         */
        val listChanged: Boolean?
    }
}

/**
 * This request is sent from the client to the server when it first connects, asking it to begin initialization.
 */
abstract class InitializeRequestSchema : RequestSchema {
    final override val method: String = "initialize"
    abstract override val params: Params

    interface Params : BaseRequestParamsSchema {
        /**
         * The latest version of the Model Context Protocol that the client supports. The client MAY decide to support older versions as well.
         */
        val protocolVersion: String
        val capabilities: ClientCapabilitiesSchema
        val clientInfo: ImplementationSchema
    }
}

typealias JSONRPCMessage = JSONRPCMessageSchema
