@file:Suppress("unused")

import UnsubscribeRequestSchema.Params

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
typealias RequestId = RequestIdSchema
typealias RequestIdSchema = Any

typealias JSONRPCMessage = JSONRPCMessageSchema

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
typealias JSONRPCNotification = JSONRPCNotificationSchema

abstract class JSONRPCNotificationSchema : NotificationSchema, JSONRPCMessageSchema {
    val jsonrpc: String = JSONRPC_VERSION
}

/**
 * A successful (non-error) response to a request.
 */
typealias JSONRPCResponse = JSONRPCResponseSchema
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

interface ServerCapabilitiesSchema : PassthroughObject {
    /**
     * Experimental, non-standard capabilities that the server supports.
     */
    val experimental: PassthroughObject?

    /**
     * Present if the server supports sending log messages to the client.
     */
    val logging: PassthroughObject?

    /**
     * Present if the server offers any prompt templates.
     */
    val prompts: Prompts?

    interface Prompts : PassthroughObject {
        /**
         * Whether this server supports issuing notifications for changes to the prompt list.
         */
        val listChanged: Boolean?
    }

    /**
     * Present if the server offers any resources to read.
     */
    val resources: Resources?

    interface Resources : PassthroughObject {
        /**
         * Whether this server supports clients subscribing to resource updates.
         */
        val subscribe: Boolean?

        /**
         * Whether this server supports issuing notifications for changes to the resource list.
         */
        val listChanged: Boolean?
    }

    /**
     * Present if the server offers any tools to call.
     */
    val tools: Tools?

    interface Tools : PassthroughObject {
        /**
         * Whether this server supports issuing notifications for changes to the tool list.
         */
        val listChanged: Boolean?
    }
}

/**
 * After receiving an initialize request from the client, the server sends this response.
 */
interface InitializeResultSchema : ResultSchema {
    /**
     * The version of the Model Context Protocol that the server wants to use. This may not match the version that the client requested. If the client cannot support this version, it MUST disconnect.
     */
    val protocolVersion: String
    val capabilities: ServerCapabilitiesSchema
    val serverInfo: ImplementationSchema
}

/**
 * This notification is sent from the client to the server after initialization has finished.
 */
abstract class InitializedNotificationSchema : NotificationSchema {
    final override val method: String = "notifications/initialized"
}

/* Ping */
/**
 * A ping, issued by either the server or the client, to check that the other party is still alive. The receiver must promptly respond, or else may be disconnected.
 */
abstract class PingRequestSchema : RequestSchema {
    final override val method: String = "ping"
}

/* Progress notifications */
interface ProgressSchema : PassthroughObject {
    /**
     * The progress thus far. This should increase every time progress is made, even if the total is unknown.
     */
    val progress: Int
    /**
     * Total number of items to process (or total progress required), if known.
     */
    // todo maybe number?
    val total: Int?
}

/**
 * An out-of-band notification used to inform the receiver of a progress update for a long-running request.
 */
abstract class ProgressNotificationSchema : NotificationSchema {
    final override val method: String = "notifications/progress"
    abstract override val params: Params

    interface Params : BaseNotificationParamsSchema, ProgressSchema {
        /**
         * The progress token which was given in the initial request, used to associate this notification with the request that is proceeding.
         */
        val progressToken: ProgressTokenSchema
    }
}

/* Pagination */
interface PaginatedRequestSchema : RequestSchema {
    abstract override val params: Params?

    interface Params : BaseRequestParamsSchema {
        /**
         * An opaque token representing the current pagination position.
         * If provided, the server should return results starting after this cursor.
         */
        val cursor: CursorSchema?
    }
}

interface PaginatedResultSchema : ResultSchema {
    /**
     * An opaque token representing the pagination position after the last returned result.
     * If present, there may be more results available.
     */
    val nextCursor: CursorSchema?
}

/* Resources */
/**
 * The contents of a specific resource or sub-resource.
 */
sealed interface ResourceContentsSchema : PassthroughObject {
    /**
     * The URI of this resource.
     */
    val uri: String
    /**
     * The MIME type of this resource, if known.
     */
    val mimeType: String?
}

interface TextResourceContentsSchema : ResourceContentsSchema {
    /**
     * The text of the item. This must only be set if the item can actually be represented as text (not binary data).
     */
    val text: String
}

interface BlobResourceContentsSchema : ResourceContentsSchema {
    /**
     * A base64-encoded string representing the binary data of the item.
     *
     * TODO check that it is base64, in ZOD: z.string().base64()
     */
    val blob: String
}

/**
 * A known resource that the server is capable of reading.
 */
interface ResourceSchema : PassthroughObject {
    /**
     * The URI of this resource.
     */
    val uri: String

    /**
     * A human-readable name for this resource.
     *
     * This can be used by clients to populate UI elements.
     */
    val name: String

    /**
     * A description of what this resource represents.
     *
     * This can be used by clients to improve the LLM's understanding of available resources. It can be thought of like a "hint" to the model.
     */
    val description: String?

    /**
     * The MIME type of this resource, if known.
     *
     * TODO ktor's MIME
     */
    val mimeType: String?
}

/**
 * A template description for resources available on the server.
 */
interface ResourceTemplateSchema : PassthroughObject {
    /**
     * A URI template (according to RFC 6570) that can be used to construct resource URIs.
     */
    val uriTemplate: String

    /**
     * A human-readable name for the type of resource this template refers to.
     *
     * This can be used by clients to populate UI elements.
     */
    val name: String

    /**
     * A description of what this template is for.
     *
     * This can be used by clients to improve the LLM's understanding of available resources. It can be thought of like a "hint" to the model.
     */
    val description: String?

    /**
     * The MIME type for all resources that match this template. This should only be included if all resources matching this template have the same type.
     *
     * TODO ktor's MIME
     */
    val mimeType: String?
}

/**
 * Sent from the client to request a list of resources the server has.
 */
abstract class ListResourcesRequestSchema : PaginatedRequestSchema {
    final override val method: String = "resources/list"
}

/**
 * The server's response to a resources/list request from the client.
 */
interface ListResourcesResultSchema : PaginatedResultSchema {
    val resources: Array<ResourceSchema>
}

/**
 * Sent from the client to request a list of resource templates the server has.
 */
abstract class ListResourceTemplatesRequestSchema : PaginatedRequestSchema {
    final override val method: String  = "resources/templates/list"
}

/**
 * The server's response to a resources/templates/list request from the client.
 */
interface ListResourceTemplatesResultSchema : PaginatedResultSchema {
    val resourceTemplates: Array<ResourceTemplateSchema>
}

/**
 * Sent from the client to the server, to read a specific resource URI.
 */
abstract class ReadResourceRequestSchema : RequestSchema {
    final override val method: String = "resources/read"
    abstract override val params: Params

    interface Params : BaseRequestParamsSchema {
        /**
         * The URI of the resource to read. The URI can use any protocol; it is up to the server how to interpret it.
         */
        val uri: String
    }
}

/**
 * The server's response to a resources/read request from the client.
 */
interface ReadResourceResultSchema : ResultSchema {
    // TODO original z.union([TextResourceContentsSchema, BlobResourceContentsSchema]),
    val contents: Array<ResourceContentsSchema>
}

/**
 * An optional notification from the server to the client, informing it that the list of resources it can read from has changed. This may be issued by servers without any previous subscription from the client.
 */
abstract class ResourceListChangedNotificationSchema : NotificationSchema {
    final override val method: String = "notifications/resources/list_changed"
}

/**
 * Sent from the client to request resources/updated notifications from the server whenever a particular resource changes.
 */
abstract class SubscribeRequestSchema : RequestSchema {
    final override val method: String = "resources/subscribe"
    abstract override val params: Params

    interface Params : BaseRequestParamsSchema {
        /**
         * The URI of the resource to subscribe to. The URI can use any protocol; it is up to the server how to interpret it.
         */
        val uri: String
    }
}

/**
 * Sent from the client to request cancellation of resources/updated notifications from the server. This should follow a previous resources/subscribe request.
 */
abstract class UnsubscribeRequestSchema : RequestSchema {
    final override val method: String = "resources/unsubscribe"
    abstract override val params: Params

    interface Params : BaseRequestParamsSchema {
        /**
         * The URI of the resource to unsubscribe from.
         */
        val uri: String
    }
}

/**
 * A notification from the server to the client, informing it that a resource has changed and may need to be read again. This should only be sent if the client previously sent a resources/subscribe request.
 */
abstract class ResourceUpdatedNotificationSchema : NotificationSchema {
    final override val method: String = "notifications/resources/updated"

    abstract override val params: Params

    interface Params : BaseNotificationParamsSchema {
        /**
         * The URI of the resource that has been updated. This might be a sub-resource of the one that the client actually subscribed to.
         */
        val uri: String
    }
}

/* Prompts */
/**
 * Describes an argument that a prompt can accept.
 */
interface PromptArgumentSchema : PassthroughObject {
    /**
     * The name of the argument.
     */
    val name: String
    /**
     * A human-readable description of the argument.
     */
    val description: String?
    /**
     * Whether this argument must be provided.
     */
    val required: Boolean?
}

/**
 * A prompt or prompt template that the server offers.
 */
interface PromptSchema : PassthroughObject {
    /**
     * The name of the prompt or prompt template.
     */
    val name: String
    /**
     * An optional description of what this prompt provides
     */
    val description: String?
    /**
     * A list of arguments to use for templating the prompt.
     */
    val arguments: Array<PromptArgumentSchema>?
}

/**
 * Sent from the client to request a list of prompts and prompt templates the server has.
 */
abstract class ListPromptsRequestSchema : PaginatedRequestSchema {
    final override val method: String = "prompts/list"
}

/**
 * The server's response to a prompts/list request from the client.
 */
interface ListPromptsResultSchema : PaginatedResultSchema {
    val prompts: Array<PromptSchema>
}

/**
 * Used by the client to get a prompt provided by the server.
 */
abstract class GetPromptRequestSchema : RequestSchema {
    final override val method: String = "prompts/get"
    abstract override val params: Params

    interface Params : BaseRequestParamsSchema {
        /**
         * The name of the prompt or prompt template.
         */
        val name: String
        /**
         * Arguments to use for templating the prompt.
         */
        val arguments: Map<String, String>?
    }
}

/**
 * Text provided to or from an LLM.
 */
abstract class TextContentSchema : PassthroughObject {
    val type: String = "text"
    /**
     * The text content of the message.
     */
    abstract val text: String
}
