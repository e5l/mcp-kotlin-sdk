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

interface RequestSchema {
    val method: String
    val params: BaseRequestParamsSchema?
}

interface BaseNotificationParamsSchema : PassthroughObject, WithMeta


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
 * A request that expects a response.
 */
abstract class JSONRPCRequestSchema : RequestSchema {
    val jsonrpc: String = JSONRPC_VERSION
    abstract val id: RequestIdSchema
}

/**
 * A notification which does not expect a response.
 */
abstract class JSONRPCNotificationSchema : NotificationSchema {
    val jsonrpc: String = JSONRPC_VERSION
}

/**
 * A successful (non-error) response to a request.
 */
abstract class JSONRPCResponseSchema : NotificationSchema {
    val jsonrpc: String = JSONRPC_VERSION
    abstract val id: RequestIdSchema
    abstract val result: ResultSchema
}



typealias JSONRPCMessage = JSONRPCMessageSchema

/**
 * TODO
 */
sealed interface JSONRPCMessageSchema
