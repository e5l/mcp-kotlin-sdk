@file:Suppress("unused", "EnumEntryName")

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.*
import shared.McpJson
import java.util.concurrent.atomic.AtomicLong

const val LATEST_PROTOCOL_VERSION = "2024-11-05"

val SUPPORTED_PROTOCOL_VERSIONS = arrayOf(
    LATEST_PROTOCOL_VERSION,
    "2024-10-07",
)

const val JSONRPC_VERSION: String = "2.0"

private val REQUEST_MESSAGE_ID = AtomicLong(0L)

/**
 * A progress token, used to associate progress notifications with the original request.
 * Stores message ID.
 */
typealias ProgressToken = Long

/**
 * An opaque token used to represent a cursor for pagination.
 */
typealias Cursor = String

@Serializable
sealed interface WithMeta {
    /**
     * This result property is reserved by the protocol to allow clients and servers to attach additional metadata to their responses.
     */
    @Suppress("PropertyName")
    val _meta: JsonObject?
}

@Serializable
class CustomMeta(override val _meta: JsonObject) : WithMeta

@Serializable(with = RequestMethodSerializer::class)
sealed interface Method {
    val value: String

    @Serializable
    enum class Defined(override val value: String) : Method {
        Initialize("initialize"),
        Ping("ping"),
        ResourcesList("resources/list"),
        ResourcesTemplatesList("resources/templates/list"),
        ResourcesRead("resources/read"),
        ResourcesSubscribe("resources/subscribe"),
        ResourcesUnsubscribe("resources/unsubscribe"),
        PromptsList("prompts/list"),
        PromptsGet("prompts/get"),
        NotificationsCancelled("notifications/cancelled"),
        NotificationsInitialized("notifications/initialized"),
        NotificationsProgress("notifications/progress"),
        NotificationsMessage("notifications/message"),
        NotificationsResourcesUpdated("notifications/resources/updated"),
        NotificationsResourcesListChanged("notifications/resources/list_changed"),
        NotificationsToolsListChanged("notifications/tools/list_changed"),
        NotificationsRootsListChanged("notifications/roots/list_changed"),
        NotificationsPromptsListChanged("notifications/prompts/list_changed"),
        ToolsList("tools/list"),
        ToolsCall("tools/call"),
        LoggingSetLevel("logging/setLevel"),
        SamplingCreateMessage("sampling/createMessage"),
        CompletionComplete("completion/complete"),
        RootsList("roots/list")
    }

    @Serializable
    data class Custom(override val value: String) : Method
}

@Serializable
sealed interface Request {
    val method: Method
    val params: WithMeta?

    /**
     * If specified, the caller is requesting out-of-band progress notifications for this request (as represented by notifications/progress). The value of this parameter is an opaque token that will be attached to any subsequent notifications. The receiver is not obligated to provide these notifications.
     */
    @Transient
    val progressToken: ProgressToken?
        get() = params?._meta?.get("progressToken")?.jsonPrimitive?.content?.toLong()
}

fun Request.toJSON(): JSONRPCRequest {
    val encoded = when(this) {
        is ClientRequest -> McpJson.encodeToJsonElement<ClientRequest>(this)
        is ServerRequest -> McpJson.encodeToJsonElement<ServerRequest>(this)
        is CustomRequest -> McpJson.encodeToJsonElement<CustomRequest>(this)
        else -> error("Unknown type: ${this::class.qualifiedName}")
    }

    return JSONRPCRequest(
        method = method.value,
        params = encoded,
        jsonrpc = JSONRPC_VERSION,
    )
}

@Serializable
open class CustomRequest(override val method: Method, override val params: WithMeta? = null) : Request

@Serializable
sealed interface Notification {
    val method: Method
    val params: WithMeta?
}

fun Notification.toJSON(): JSONRPCNotification {
    val encoded = McpJson.encodeToJsonElement<Notification>(this)
    return JSONRPCNotification(
        method.value,
        params = encoded
    )
}

@Serializable
sealed interface RequestResult : WithMeta

@Serializable
abstract class CustomResult : RequestResult

@Serializable
object EmptyRequestResult : RequestResult {
    override val _meta: JsonObject? = null
}

/**
 * A uniquely identifying ID for a request in JSON-RPC.
 */
typealias RequestId = Long

@Serializable
sealed interface JSONRPCMessage

/**
 * A request that expects a response.
 */
@Serializable
data class JSONRPCRequest(
    val id: RequestId = REQUEST_MESSAGE_ID.incrementAndGet(),
    val method: String,
    val params: JsonElement,
    val jsonrpc: String = JSONRPC_VERSION,
) : JSONRPCMessage

/**
 * A notification which does not expect a response.
 */
@Serializable
data class JSONRPCNotification(
    val method: String,
    val params: JsonElement?,
    val jsonrpc: String = JSONRPC_VERSION
) : JSONRPCMessage

/**
 * A successful (non-error) response to a request.
 */
@Serializable
class JSONRPCResponse(
    val id: RequestId,
    val jsonrpc: String = JSONRPC_VERSION,
    val result: RequestResult? = null,
    val error: JSONRPCError? = null
) : JSONRPCMessage

/**
 * An incomplete set of error codes that may appear in JSON-RPC responses.
 */
@Serializable(with = ErrorCodeSerializer::class)
sealed interface ErrorCode {
    val code: Int

    @Serializable
    enum class Defined(override val code: Int) : ErrorCode {
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

    @Serializable
    data class Unknown(override val code: Int) : ErrorCode
}

/**
 * A response to a request that indicates an error occurred.
 */
@Serializable
class JSONRPCError(
    val code: ErrorCode,
    val message: String,
    val data: JsonObject?,
)

/* Empty result */
/**
 * A response that indicates success but carries no data.
 */
@Serializable
object EmptyResult : ClientResult, ServerResult {
    override val _meta: JsonObject? = null
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
@Serializable
data class CancelledNotification(
    override val params: Params,
) : ClientNotification, ServerNotification {
    override val method: Method = Method.Defined.NotificationsCancelled

    @Serializable
    data class Params(
        /**
         * The ID of the request to cancel.
         *
         * This MUST correspond to the ID of a request previously issued in the same direction.
         */
        val requestId: RequestId,
        /**
         * An optional string describing the reason for the cancellation. This MAY be logged or presented to the user.
         */
        val reason: String?,
        override val _meta: JsonObject? = null,
    ) : WithMeta
}

/* Initialization */
/**
 * Describes the name and version of an MCP implementation.
 */
@Serializable
data class Implementation(
    val name: String,
    val version: String,
)

/**
 * Capabilities a client may support. Known capabilities are defined here, in this , but this is not a closed set: any client can define its own, additional capabilities.
 */
@Serializable
data class ClientCapabilities(
    /**
     * Experimental, non-standard capabilities that the client supports.
     */
    val experimental: JsonObject? = null,
    /**
     * Present if the client supports sampling from an LLM.
     */
    val sampling: JsonObject? = null,
    /**
     * Present if the client supports listing roots.
     */
    val roots: Roots? = null,
) {
    @Serializable
    data class Roots(
        /**
         * Whether the client supports issuing notifications for changes to the roots list.
         */
        val listChanged: Boolean?,
    )
}

@Serializable(with = ClientRequestPolymorphicSerializer::class)
interface ClientRequest : Request

@Serializable(with = ClientNotificationPolymorphicSerializer::class)
sealed interface ClientNotification : Notification

@Serializable
sealed interface ClientResult : RequestResult

@Serializable(with = ServerRequestPolymorphicSerializer::class)
sealed interface ServerRequest : Request

@Serializable(with = ServerNotificationPolymorphicSerializer::class)
sealed interface ServerNotification : Notification

@Serializable
sealed interface ServerResult : RequestResult

@Serializable
data class UnknownMethodRequestOrNotification(
    override val method: Method,
    override val params: WithMeta? = null,
) : ClientNotification, ClientRequest, ServerNotification, ServerRequest

/**
 * This request is sent from the client to the server when it first connects, asking it to begin initialization.
 */
@Serializable
data class InitializeRequest(
    override val params: Params,
) : ClientRequest {
    override val method: Method = Method.Defined.Initialize

    @Serializable
    data class Params(
        /**
         * The latest version of the Model Context Protocol that the client supports. The client MAY decide to support older versions as well.
         */
        val protocolVersion: String,
        val capabilities: ClientCapabilities,
        val clientInfo: Implementation,
        override val _meta: JsonObject? = null,
    ) : WithMeta
}

@Serializable
data class ServerCapabilities(
    /**
     * Experimental, non-standard capabilities that the server supports.
     */
    val experimental: JsonObject? = null,
    /**
     * Present if the client supports sampling from an LLM.
     */
    val sampling: JsonObject? = null,
    /**
     * Present if the server supports sending log messages to the client.
     */
    val logging: JsonObject? = null,
    /**
     * Present if the server offers any prompt templates.
     */
    val prompts: Prompts? = null,
    /**
     * Present if the server offers any resources to read.
     */
    val resources: Resources? = null,
    /**
     * Present if the server offers any tools to call.
     */
    val tools: Tools? = null,
) {
    @Serializable
    data class Prompts(
        /**
         * Whether this server supports issuing notifications for changes to the prompt list.
         */
        val listChanged: Boolean?,
    )

    @Serializable
    data class Resources(
        /**
         * Whether this server supports clients subscribing to resource updates.
         */
        val subscribe: Boolean?,
        /**
         * Whether this server supports issuing notifications for changes to the resource list.
         */
        val listChanged: Boolean?,
    )

    @Serializable
    data class Tools(
        /**
         * Whether this server supports issuing notifications for changes to the tool list.
         */
        val listChanged: Boolean?,
    )
}

/**
 * After receiving an initialize request from the client, the server sends this response.
 */
@Serializable
data class InitializeResult(
    /**
     * The version of the Model Context Protocol that the server wants to use. This may not match the version that the client requested. If the client cannot support this version, it MUST disconnect.
     */
    val protocolVersion: String = LATEST_PROTOCOL_VERSION,
    val capabilities: ServerCapabilities = ServerCapabilities(),
    val serverInfo: Implementation,
    override val _meta: JsonObject? = null,
) : ServerResult

/**
 * This notification is sent from the client to the server after initialization has finished.
 */
@Serializable
data class InitializedNotification(
    override val params: WithMeta? = null,
) : ClientNotification {
    override val method: Method = Method.Defined.NotificationsInitialized
}

/* Ping */
/**
 * A ping, issued by either the server or the client, to check that the other party is still alive. The receiver must promptly respond, or else may be disconnected.
 */
@Serializable
data class PingRequest(
    override val params: WithMeta? = null,
) : ServerRequest, ClientRequest {
    override val method: Method = Method.Defined.Ping
}

@Serializable
sealed interface ProgressBase {
    /**
     * The progress thus far. This should increase every time progress is made, even if the total is unknown.
     */
    val progress: Int

    /**
     * Total number of items to process (or total progress required), if known.
     */
    val total: Double?
}

/* Progress notifications */
@Serializable
open class Progress(
    /**
     * The progress thus far. This should increase every time progress is made, even if the total is unknown.
     */
    override val progress: Int,

    /**
     * Total number of items to process (or total progress required), if known.
     */
    override val total: Double?
) : ProgressBase

/**
 * An out-of-band notification used to inform the receiver of a progress update for a long-running request.
 */
@Serializable
data class ProgressNotification(
    override val params: Params,
) : ClientNotification, ServerNotification {
    override val method: Method = Method.Defined.NotificationsProgress

    /**
     * The progress token which was given in the initial request, used to associate this notification with the request that is proceeding.
     */
    @Serializable
    class Params(
        val progressToken: ProgressToken,
        override val _meta: JsonObject?,
        override val progress: Int,
        override val total: Double?,
    ) : WithMeta, ProgressBase
}

/* Pagination */
@Serializable
sealed interface PaginatedRequest : Request {
    abstract override val params: Params?

    @Serializable
    data class Params(
        /**
         * An opaque token representing the current pagination position.
         * If provided, the server should return results starting after this cursor.
         */
        val cursor: Cursor?,
        override val _meta: JsonObject? = null,
    ) : WithMeta
}

@Serializable
sealed interface PaginatedResult : RequestResult {
    /**
     * An opaque token representing the pagination position after the last returned result.
     * If present, there may be more results available.
     */
    val nextCursor: Cursor?
}

/* Resources */
/**
 * The contents of a specific resource or sub-resource.
 */
@Serializable
sealed interface ResourceContents {
    /**
     * The URI of this resource.
     */
    val uri: String

    /**
     * The MIME type of this resource, if known.
     */
    val mimeType: String?
}

@Serializable
data class TextResourceContents(
    /**
     * The text of the item. This must only be set if the item can actually be represented as text (not binary data).
     */
    val text: String,
    override val uri: String,
    override val mimeType: String?,
) : ResourceContents

@Serializable
data class BlobResourceContents(
    /**
     * A base64-encoded string representing the binary data of the item.
     */
    val blob: String,
    override val uri: String,
    override val mimeType: String?,
) : ResourceContents

/**
 * A known resource that the server is capable of reading.
 */
@Serializable
data class Resource(
    /**
     * The URI of this resource.
     */
    val uri: String,
    /**
     * A human-readable name for this resource.
     *
     * This can be used by clients to populate UI elements.
     */
    val name: String,
    /**
     * A description of what this resource represents.
     *
     * This can be used by clients to improve the LLM's understanding of available resources. It can be thought of like a "hint" to the model.
     */
    val description: String?,
    /**
     * The MIME type of this resource, if known.
     */
    val mimeType: String?,
)

/**
 * A template description for resources available on the server.
 */
@Serializable
data class ResourceTemplate(
    /**
     * A URI template (according to RFC 6570) that can be used to construct resource URIs.
     */
    val uriTemplate: String,
    /**
     * A human-readable name for the type of resource this template refers to.
     *
     * This can be used by clients to populate UI elements.
     */
    val name: String,
    /**
     * A description of what this template is for.
     *
     * This can be used by clients to improve the LLM's understanding of available resources. It can be thought of like a "hint" to the model.
     */
    val description: String?,
    /**
     * The MIME type for all resources that match this template. This should only be included if all resources matching this template have the same type.
     */
    val mimeType: String?,
)

/**
 * Sent from the client to request a list of resources the server has.
 */
@Serializable
data class ListResourcesRequest(
    override val params: PaginatedRequest.Params? = null,
) : ClientRequest, PaginatedRequest {
    override val method: Method = Method.Defined.ResourcesList
}

/**
 * The server's response to a resources/list request from the client.
 */
@Serializable
class ListResourcesResult(
    val resources: Array<Resource>,
    override val nextCursor: Cursor?,
    override val _meta: JsonObject? = null,
) : ServerResult, PaginatedResult

/**
 * Sent from the client to request a list of resource templates the server has.
 */
@Serializable
data class ListResourceTemplatesRequest(
    override val params: PaginatedRequest.Params? = null,
) : ClientRequest, PaginatedRequest {
    override val method: Method = Method.Defined.ResourcesTemplatesList
}

/**
 * The server's response to a resources/templates/list request from the client.
 */
@Serializable
class ListResourceTemplatesResult(
    val resourceTemplates: Array<ResourceTemplate>,
    override val nextCursor: Cursor?,
    override val _meta: JsonObject? = null,
) : ServerResult, PaginatedResult

/**
 * Sent from the client to the server, to read a specific resource URI.
 */
@Serializable
data class ReadResourceRequest(
    override val params: Params,
) : ClientRequest {
    override val method: Method = Method.Defined.ResourcesRead

    @Serializable
    data class Params(
        /**
         * The URI of the resource to read. The URI can use any protocol; it is up to the server how to interpret it.
         */
        val uri: String,
        override val _meta: JsonObject? = null,
    ) : WithMeta
}

/**
 * The server's response to a resources/read request from the client.
 */
@Serializable
class ReadResourceResult(
    val contents: Array<ResourceContents>,
    override val _meta: JsonObject? = null,
) : ServerResult

/**
 * An optional notification from the server to the client, informing it that the list of resources it can read from has changed. This may be issued by servers without any previous subscription from the client.
 */
@Serializable
data class ResourceListChangedNotification(
    override val params: WithMeta? = null,
) : ServerNotification {
    override val method: Method = Method.Defined.NotificationsResourcesListChanged
}

/**
 * Sent from the client to request resources/updated notifications from the server whenever a particular resource changes.
 */
@Serializable
data class SubscribeRequest(
    override val params: Params,
) : ClientRequest {
    override val method: Method = Method.Defined.ResourcesSubscribe

    @Serializable
    data class Params(
        /**
         * The URI of the resource to subscribe to. The URI can use any protocol; it is up to the server how to interpret it.
         */
        val uri: String,
        override val _meta: JsonObject? = null,
    ) : WithMeta
}

/**
 * Sent from the client to request cancellation of resources/updated notifications from the server. This should follow a previous resources/subscribe request.
 */
@Serializable
data class UnsubscribeRequest(
    override val params: Params,
) : ClientRequest {
    override val method: Method = Method.Defined.ResourcesUnsubscribe

    @Serializable
    data class Params(
        /**
         * The URI of the resource to unsubscribe from.
         */
        val uri: String,
        override val _meta: JsonObject? = null,
    ) : WithMeta
}

/**
 * A notification from the server to the client, informing it that a resource has changed and may need to be read again. This should only be sent if the client previously sent a resources/subscribe request.
 */
@Serializable
data class ResourceUpdatedNotification(
    override val params: Params,
) : ServerNotification {
    override val method: Method = Method.Defined.NotificationsResourcesUpdated

    @Serializable
    data class Params(
        /**
         * The URI of the resource that has been updated. This might be a sub-resource of the one that the client actually subscribed to.
         */
        val uri: String,
        override val _meta: JsonObject? = null,
    ) : WithMeta
}

/* Prompts */
/**
 * Describes an argument that a prompt can accept.
 */
@Serializable
data class PromptArgument(
    /**
     * The name of the argument.
     */
    val name: String,
    /**
     * A human-readable description of the argument.
     */
    val description: String?,
    /**
     * Whether this argument must be provided.
     */
    val required: Boolean?,
)

/**
 * A prompt or prompt template that the server offers.
 */
@Serializable
class Prompt(
    /**
     * The name of the prompt or prompt template.
     */
    val name: String,
    /**
     * An optional description of what this prompt provides
     */
    val description: String?,
    /**
     * A list of arguments to use for templating the prompt.
     */
    val arguments: Array<PromptArgument>?,
)

/**
 * Sent from the client to request a list of prompts and prompt templates the server has.
 */
@Serializable
data class ListPromptsRequest(
    override val params: PaginatedRequest.Params? = null,
) : ClientRequest, PaginatedRequest {
    override val method: Method = Method.Defined.PromptsList
}

/**
 * The server's response to a prompts/list request from the client.
 */
@Serializable
class ListPromptsResult(
    val prompts: Array<Prompt>,
    override val nextCursor: Cursor?,
    override val _meta: JsonObject? = null,
) : ServerResult, PaginatedResult

/**
 * Used by the client to get a prompt provided by the server.
 */
@Serializable
data class GetPromptRequest(
    override val params: Params,
) : ClientRequest {
    override val method: Method = Method.Defined.PromptsGet

    @Serializable
    data class Params(
        /**
         * The name of the prompt or prompt template.
         */
        val name: String,

        /**
         * Arguments to use for templating the prompt.
         */
        val arguments: Map<String, String>?,

        override val _meta: JsonObject? = null,
    ) : WithMeta
}

@Serializable(with = PromptMessageContentPolymorphicSerializer::class)
sealed interface PromptMessageContent {
    val type: String
}

@Serializable(with = PromptMessageContentTextOrImagePolymorphicSerializer::class)
sealed interface PromptMessageContentTextOrImage : PromptMessageContent

/**
 * Text provided to or from an LLM.
 */
@Serializable
data class TextContent(
    /**
     * The text content of the message.
     */
    val text: String,
) : PromptMessageContentTextOrImage {
    override val type: String = TYPE

    companion object {
        const val TYPE = "text"
    }
}

/**
 * An image provided to or from an LLM.
 */
@Serializable
data class ImageContent(
    /**
     * The base64-encoded image data.
     */
    val data: String,

    /**
     * The MIME type of the image. Different providers may support different image types.
     */
    val mimeType: String,
) : PromptMessageContentTextOrImage {
    override val type: String = TYPE

    companion object {
        const val TYPE = "image"
    }
}

/**
 * An image provided to or from an LLM.
 */
@Serializable
data class UnknownContent(
    override val type: String,
) : PromptMessageContentTextOrImage

/**
 * The contents of a resource, embedded into a prompt or tool call result.
 */
@Serializable
data class EmbeddedResource(
    val resource: ResourceContents,
) : PromptMessageContent {
    override val type: String = TYPE

    companion object {
        const val TYPE = "resource"
    }
}

@Suppress("EnumEntryName")
@Serializable
enum class Role {
    user, assistant,
}

/**
 * Describes a message returned as part of a prompt.
 */
@Serializable
data class PromptMessage(
    val role: Role,
    val content: PromptMessageContent,
)

/**
 * The server's response to a prompts/get request from the client.
 */
@Serializable
class GetPromptResult(
    /**
     * An optional description for the prompt.
     */
    val description: String?,
    val messages: Array<PromptMessage>,
    override val _meta: JsonObject? = null,
) : ServerResult

/**
 * An optional notification from the server to the client, informing it that the list of prompts it offers has changed. This may be issued by servers without any previous subscription from the client.
 */
@Serializable
data class PromptListChangedNotification(
    override val params: WithMeta? = null,
) : ServerNotification {
    override val method: Method = Method.Defined.NotificationsPromptsListChanged
}

/* Tools */
/**
 * Definition for a tool the client can call.
 */
@Serializable
data class Tool(
    /**
     * The name of the tool.
     */
    val name: String,
    /**
     * A human-readable description of the tool.
     */
    val description: String?,
    /**
     * A JSON  object defining the expected parameters for the tool.
     */
    val input: Input,
) {
    @Serializable
    data class Input(
        val properties: JsonObject? = null,
    ) {
        val type: String = "object"
    }
}

/**
 * Sent from the client to request a list of tools the server has.
 */
@Serializable
data class ListToolsRequest(
    override val params: PaginatedRequest.Params? = null,
) : ClientRequest, PaginatedRequest {
    override val method: Method = Method.Defined.ToolsList
}

/**
 * The server's response to a tools/list request from the client.
 */
@Serializable
class ListToolsResult(
    val tools: Array<Tool>,
    override val nextCursor: Cursor?,
    override val _meta: JsonObject? = null,
) : ServerResult, PaginatedResult

/**
 * The server's response to a tool call.
 */
interface CallToolResultBase : ServerResult {
    val content: PromptMessageContent
    val isError: Boolean? get() = false
}

/**
 * The server's response to a tool call.
 */
@Serializable
data class CallToolResult(
    override val content: PromptMessageContent,
    override val isError: Boolean? = false,
    override val _meta: JsonObject? = null,
) : CallToolResultBase

/**
 * [CallToolResult] extended with backwards compatibility to protocol version 2024-10-07.
 */
@Serializable
data class CompatibilityCallToolResult(
    override val content: PromptMessageContent,
    override val isError: Boolean? = false,
    override val _meta: JsonObject? = null,
    val toolResult: JsonObject? = null,
) : CallToolResultBase

/**
 * Used by the client to invoke a tool provided by the server.
 */
@Serializable
data class CallToolRequest(
    override val params: Params,
) : ClientRequest {
    override val method: Method = Method.Defined.ToolsCall

    @Serializable
    data class Params(
        val name: String,
        val arguments: Map<String, JsonObject?>?,
        override val _meta: JsonObject? = null,
    ) : WithMeta
}

/**
 * An optional notification from the server to the client, informing it that the list of tools it offers has changed. This may be issued by servers without any previous subscription from the client.
 */
@Serializable
data class ToolListChangedNotification(
    override val params: WithMeta? = null,
) : ServerNotification {
    override val method: Method = Method.Defined.NotificationsToolsListChanged
}

/* Logging */
/**
 * The severity of a log message.
 */
@Suppress("EnumEntryName")
@Serializable
enum class LoggingLevel {
    debug,
    info,
    notice,
    warning,
    error,
    critical,
    alert,
    emergency,
    ;
}

/**
 * Notification of a log message passed from server to client. If no logging/setLevel request has been sent from the client, the server MAY decide which messages to send automatically.
 */
@Serializable
data class LoggingMessageNotification(
    override val params: Params,
) : ServerNotification {
    /**
     * A request from the client to the server, to enable or adjust logging.
     */
    @Serializable
    data class SetLevelRequest(
        override val params: Params,
    ) : ClientRequest {
        override val method: Method = Method.Defined.LoggingSetLevel

        @Serializable
        data class Params(
            /**
             * The level of logging that the client wants to receive from the server. The server should send all logs at this level and higher (i.e., more severe) to the client as notifications/logging/message.
             */
            val level: LoggingLevel,
            override val _meta: JsonObject? = null,
        ) : WithMeta
    }

    override val method: Method = Method.Defined.NotificationsMessage

    @Serializable
    data class Params(
        /**
         * The severity of this log message.
         */
        val level: LoggingLevel,

        /**
         * An optional name of the logger issuing this message.
         */
        val logger: String?,

        /**
         * The data to be logged, such as a string message or an object. Any JSON serializable type is allowed here.
         */
        val data: JsonObject?,
        override val _meta: JsonObject? = null,
    ) : WithMeta
}

/* Sampling */
/**
 * Hints to use for model selection.
 */
@Serializable
data class ModelHint(
    /**
     * A hint for a model name.
     */
    val name: String?,
)

/**
 * The server's preferences for model selection, requested of the client during sampling.
 */
@Suppress("CanBeParameter")
@Serializable
class ModelPreferences(
    /**
     * Optional hints to use for model selection.
     */
    val hints: Array<ModelHint>?,
    /**
     * How much to prioritize cost when selecting a model.
     */
    val costPriority: Double?,
    /**
     * How much to prioritize sampling speed (latency) when selecting a model.
     */
    val speedPriority: Double?,
    /**
     * How much to prioritize intelligence and capabilities when selecting a model.
     */
    val intelligencePriority: Double?,
) {
    init {
        require(costPriority == null || costPriority in 0.0..1.0) {
            "costPriority must be in 0.0 <= x <= 1.0 value range"
        }

        require(speedPriority == null || speedPriority in 0.0..1.0) {
            "costPriority must be in 0.0 <= x <= 1.0 value range"
        }

        require(intelligencePriority == null || intelligencePriority in 0.0..1.0) {
            "intelligencePriority must be in 0.0 <= x <= 1.0 value range"
        }
    }
}

/**
 * Describes a message issued to or received from an LLM API.
 */
@Serializable
data class SamplingMessage(
    val role: Role,
    val content: PromptMessageContentTextOrImage,
)

/**
 * A request from the server to sample an LLM via the client. The client has full discretion over which model to select. The client should also inform the user before beginning sampling, to allow them to inspect the request (human in the loop) and decide whether to approve it.
 */
@Serializable
data class CreateMessageRequest(
    override val params: Params,
) : ServerRequest {
    override val method: Method = Method.Defined.SamplingCreateMessage

    @Serializable
    class Params(
        val messages: Array<SamplingMessage>,
        /**
         * An optional system prompt the server wants to use for sampling. The client MAY modify or omit this prompt.
         */
        val systemPrompt: String?,
        /**
         * A request to include context from one or more MCP servers (including the caller), to be attached to the prompt. The client MAY ignore this request.
         */
        val includeContext: IncludeContext?,
        val temperature: Double?,
        /**
         * The maximum number of tokens to sample, as requested by the server. The client MAY choose to sample fewer tokens than requested.
         */
        val maxTokens: Int,
        val stopSequences: Array<String>?,
        /**
         * Optional metadata to pass through to the LLM provider. The format of this metadata is provider-specific.
         */
        val metadata: JsonObject?,
        /**
         * The server's preferences for which model to select.
         */
        val modelPreferences: ModelPreferences?,
        override val _meta: JsonObject? = null,
    ) : WithMeta {
        @Serializable
        enum class IncludeContext { none, thisServer, allServers }
    }
}

@Serializable(with = StopReasonSerializer::class)
sealed interface StopReason {
    val value: String

    @Serializable
    data object EndTurn : StopReason {
        override val value: String = "endTurn"
    }

    @Serializable
    data object StopSequence : StopReason {
        override val value: String = "stopSequence"
    }

    @Serializable
    data object MaxTokens : StopReason {
        override val value: String = "maxTokens"
    }

    @Serializable
    @JvmInline
    value class Other(override val value: String) : StopReason
}

/**
 * The client's response to a sampling/create_message request from the server. The client should inform the user before returning the sampled message, to allow them to inspect the response (human in the loop) and decide whether to allow the server to see it.
 */
@Serializable
data class CreateMessageResult(
    /**
     * The name of the model that generated the message.
     */
    val model: String,
    /**
     * The reason why sampling stopped.
     */
    val stopReason: StopReason? = null,
    val role: Role,
    val content: PromptMessageContentTextOrImage,
    override val _meta: JsonObject? = null,
) : ClientResult

/* Autocomplete */
@Serializable(with = ReferencePolymorphicSerializer::class)
sealed interface Reference {
    val type: String
}

/**
 * A reference to a resource or resource template definition.
 */
@Serializable
data class ResourceReference(
    /**
     * The URI or URI template of the resource.
     */
    val uri: String,
) : Reference {
    override val type: String = TYPE

    companion object {
        const val TYPE = "ref/resource"
    }
}

/**
 * Identifies a prompt.
 */
@Serializable
data class PromptReference(
    /**
     * The name of the prompt or prompt template
     */
    val name: String,
) : Reference {
    override val type: String = TYPE

    companion object {
        const val TYPE = "ref/prompt"
    }
}

/**
 * Identifies a prompt.
 */
@Serializable
data class UnknownReference(
    override val type: String,
) : Reference

/**
 * A request from the client to the server, to ask for completion options.
 */
@Serializable
data class CompleteRequest(
    override val params: Params,
) : ClientRequest {
    override val method: Method = Method.Defined.CompletionComplete

    @Serializable
    data class Params(
        val ref: Reference,
        /**
         * The argument's information
         */
        val argument: Argument,
        override val _meta: JsonObject? = null,
    ) : WithMeta {
        @Serializable
        data class Argument(
            /**
             * The name of the argument
             */
            val name: String,
            /**
             * The value of the argument to use for completion matching.
             */
            val value: String,
        )
    }
}

/**
 * The server's response to a completion/complete request
 */
@Serializable
data class CompleteResult(
    val completion: Completion,
    override val _meta: JsonObject? = null,
) : ServerResult {
    @Suppress("CanBeParameter")
    @Serializable
    class Completion(
        /**
         * An array of completion values. Must not exceed 100 items.
         */
        val values: Array<String>,
        /**
         * The total number of completion options available. This can exceed the number of values actually sent in the response.
         */
        val total: Int?,
        /**
         * Indicates whether there are additional completion options beyond those provided in the current response, even if the exact total is unknown.
         */
        val hasMore: Boolean?,
    ) {
        init {
            require(values.size <= 100) {
                "'values' field must not exceed 100 items"
            }
        }
    }
}

/* Roots */
/**
 * Represents a root directory or file that the server can operate on.
 */
@Serializable
data class Root(
    /**
     * The URI identifying the root. This *must* start with file:// for now.
     */
    val uri: String,

    /**
     * An optional name for the root.
     */
    val name: String?,
) {
    init {
        require(uri.startsWith("file://")) {
            "'uri' param must start with 'file://'"
        }
    }
}

/**
 * Sent from the server to request a list of root URIs from the client.
 */
@Serializable
data class ListRootsRequest(
    override val params: WithMeta? = null,
) : ServerRequest {
    override val method: Method = Method.Defined.RootsList
}

/**
 * The client's response to a roots/list request from the server.
 */
@Serializable
class ListRootsResult(
    val roots: Array<Root>,
    override val _meta: JsonObject? = null,
) : ClientResult

/**
 * A notification from the client to the server, informing it that the list of roots has changed.
 */
@Serializable
data class RootsListChangedNotification(
    override val params: WithMeta? = null,
) : ClientNotification {
    override val method: Method = Method.Defined.NotificationsRootsListChanged
}

@Suppress("CanBeParameter")
class McpError(val code: Int, message: String, val data: JsonObject? = null) : Exception() {
    override val message: String = "MCP error ${code}: $message"
}
