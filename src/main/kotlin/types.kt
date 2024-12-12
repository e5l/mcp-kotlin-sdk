@file:Suppress("unused", "EnumEntryName")

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

const val LATEST_PROTOCOL_VERSION = "2024-11-05"

val SUPPORTED_PROTOCOL_VERSIONS = arrayOf(
    LATEST_PROTOCOL_VERSION,
    "2024-10-07",
)

const val JSONRPC_VERSION: String = "2.0"

// line 15
/**
 * A progress token, used to associate progress notifications with the original request.
 *
 * TODO String | Int
 */
typealias ProgressToken = String

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
sealed interface BaseRequestParams : WithMeta {
    override val _meta: JsonObject? // TODO !!!!

    @Serializable
    data class Meta(
        /**
         * If specified, the caller is requesting out-of-band progress notifications for this request (as represented by notifications/progress). The value of this parameter is an opaque token that will be attached to any subsequent notifications. The receiver is not obligated to provide these notifications.
         */
        val progressToken: ProgressToken?,
    )
}

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
        RootsList("roots/list"),
        ;
    }

    @Serializable
    data class Unknown(override val value: String) : Method
}

@Serializable
sealed interface Request {
    val method: Method
    val params: BaseRequestParams?
}

@Serializable
sealed interface BaseNotificationParams : WithMeta

@Serializable
sealed interface Notification {
    val method: Method
    val params: BaseNotificationParams?
}

@Serializable
sealed interface RequestResult : WithMeta

/**
 * A uniquely identifying ID for a request in JSON-RPC.
 */
typealias RequestId = String

/**
 * TODO
 */
@Serializable
sealed interface JSONRPCMessage

/**
 * A request that expects a response.
 */
abstract class JSONRPCRequest : Request, JSONRPCMessage {
    val jsonrpc: String = JSONRPC_VERSION
    abstract val id: RequestId
}

/**
 * A notification which does not expect a response.
 */
@Serializable
abstract class JSONRPCNotification : Notification, JSONRPCMessage {
    val jsonrpc: String = JSONRPC_VERSION
}

/**
 * A successful (non-error) response to a request.
 */
@Serializable
abstract class JSONRPCResponse : Notification, JSONRPCMessage {
    val jsonrpc: String = JSONRPC_VERSION
    abstract val id: RequestId
    abstract val result: RequestResult
}

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
    val id: RequestId,
    val error: Error,
) : JSONRPCMessage {
    val jsonrpc: String = JSONRPC_VERSION

    @Serializable
    class Error(
        val code: ErrorCode,
        val message: String,
        val data: JsonObject?,
    )
}

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
data class CancelledNotification(
    override val params: Params,
) : ClientNotification, ServerNotification, JSONRPCNotification() {
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
    ) : BaseNotificationParams
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
    val experimental: JsonObject?,
    /**
     * Present if the client supports sampling from an LLM.
     */
    val sampling: JsonObject?,
    /**
     * Present if the client supports listing roots.
     */
    val roots: Roots?,
) {
    @Serializable
    data class Roots(
        /**
         * Whether the client supports issuing notifications for changes to the roots list.
         */
        val listChanged: Boolean?,
    )
}

@Serializable
sealed interface ClientRequest : Request
@Serializable
sealed interface ClientNotification : Notification
@Serializable
sealed interface ClientResult : RequestResult

@Serializable
sealed interface ServerRequest : Request
@Serializable
sealed interface ServerNotification : Notification
@Serializable
sealed interface ServerResult : RequestResult

/**
 * This request is sent from the client to the server when it first connects, asking it to begin initialization.
 */
@Serializable
data class InitializeRequest(
    override val method: Method = Method.Defined.Initialize,
    override val params: Params,
) : ClientRequest {
    @Serializable
    data class Params(
        /**
         * The latest version of the Model Context Protocol that the client supports. The client MAY decide to support older versions as well.
         */
        val protocolVersion: String,
        val capabilities: ClientCapabilities,
        val clientInfo: Implementation,
        override val _meta: JsonObject? = null,
    ): BaseRequestParams
}

@Serializable
data class ServerCapabilities(
    /**
     * Experimental, non-standard capabilities that the server supports.
     */
    val experimental: JsonObject?,
    /**
     * Present if the server supports sending log messages to the client.
     */
    val logging: JsonObject?,
    /**
     * Present if the server offers any prompt templates.
     */
    val prompts: Prompts?,
    /**
     * Present if the server offers any resources to read.
     */
    val resources: Resources?,
    /**
     * Present if the server offers any tools to call.
     */
    val tools: Tools?,
) {
    interface Prompts {
        /**
         * Whether this server supports issuing notifications for changes to the prompt list.
         */
        val listChanged: Boolean?
    }

    interface Resources {
        /**
         * Whether this server supports clients subscribing to resource updates.
         */
        val subscribe: Boolean?

        /**
         * Whether this server supports issuing notifications for changes to the resource list.
         */
        val listChanged: Boolean?
    }

    interface Tools {
        /**
         * Whether this server supports issuing notifications for changes to the tool list.
         */
        val listChanged: Boolean?
    }
}

/**
 * After receiving an initialize request from the client, the server sends this response.
 */
interface InitializeResult : ServerResult {
    /**
     * The version of the Model Context Protocol that the server wants to use. This may not match the version that the client requested. If the client cannot support this version, it MUST disconnect.
     */
    val protocolVersion: String
    val capabilities: ServerCapabilities
    val serverInfo: Implementation
}

/**
 * This notification is sent from the client to the server after initialization has finished.
 */
abstract class InitializedNotification : ClientNotification {
    final override val method: Method = Method.Defined.NotificationsInitialized
}

/* Ping */
/**
 * A ping, issued by either the server or the client, to check that the other party is still alive. The receiver must promptly respond, or else may be disconnected.
 */
abstract class PingRequest : ClientRequest, ServerRequest, JSONRPCRequest() {
    final override val method: Method = Method.Defined.Ping
}

/* Progress notifications */
@Serializable
sealed interface Progress {
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
abstract class ProgressNotification : ClientNotification, ServerNotification, JSONRPCNotification() {
    final override val method: Method = Method.Defined.NotificationsProgress
    abstract override val params: Params

    @Serializable

    /**
     * The progress token which was given in the initial request, used to associate this notification with the request that is proceeding.
     */
    class Params(
        val progressToken: ProgressToken,
        override val _meta: JsonObject?,
        override val progress: Int,
        override val total: Int?,
    ) : BaseNotificationParams, Progress
}

/* Pagination */
interface PaginatedRequest : Request {
    abstract override val params: Params?

    interface Params : BaseRequestParams {
        /**
         * An opaque token representing the current pagination position.
         * If provided, the server should return results starting after this cursor.
         */
        val cursor: Cursor?
    }
}

interface PaginatedResult : RequestResult {
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

interface TextResourceContents : ResourceContents {
    /**
     * The text of the item. This must only be set if the item can actually be represented as text (not binary data).
     */
    val text: String
}

interface BlobResourceContents : ResourceContents {
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
interface Resource {
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
interface ResourceTemplate {
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
abstract class ListResourcesRequest : ClientRequest, PaginatedRequest {
    final override val method: Method = Method.Defined.ResourcesList
}

/**
 * The server's response to a resources/list request from the client.
 */
interface ListResourcesResult : ServerResult, PaginatedResult {
    val resources: Array<Resource>
}

/**
 * Sent from the client to request a list of resource templates the server has.
 */
abstract class ListResourceTemplatesRequest : ClientRequest, PaginatedRequest {
    final override val method: Method = Method.Defined.ResourcesTemplatesList
}

/**
 * The server's response to a resources/templates/list request from the client.
 */
interface ListResourceTemplatesResult : ServerResult, PaginatedResult {
    val resourceTemplates: Array<ResourceTemplate>
}

/**
 * Sent from the client to the server, to read a specific resource URI.
 */
abstract class ReadResourceRequest : ClientRequest {
    final override val method: Method = Method.Defined.ResourcesRead
    abstract override val params: Params

    interface Params : BaseRequestParams {
        /**
         * The URI of the resource to read. The URI can use any protocol; it is up to the server how to interpret it.
         */
        val uri: String
    }
}

/**
 * The server's response to a resources/read request from the client.
 */
interface ReadResourceResult : ServerResult {
    // TODO original z.union([TextResourceContents, BlobResourceContents]),
    val contents: Array<ResourceContents>
}

/**
 * An optional notification from the server to the client, informing it that the list of resources it can read from has changed. This may be issued by servers without any previous subscription from the client.
 */
abstract class ResourceListChangedNotification : ServerNotification {
    final override val method: Method = Method.Defined.NotificationsResourcesListChanged
}

/**
 * Sent from the client to request resources/updated notifications from the server whenever a particular resource changes.
 */
abstract class SubscribeRequest : ClientRequest {
    final override val method: Method = Method.Defined.ResourcesSubscribe
    abstract override val params: Params

    interface Params : BaseRequestParams {
        /**
         * The URI of the resource to subscribe to. The URI can use any protocol; it is up to the server how to interpret it.
         */
        val uri: String
    }
}

/**
 * Sent from the client to request cancellation of resources/updated notifications from the server. This should follow a previous resources/subscribe request.
 */
abstract class UnsubscribeRequest : ClientRequest {
    final override val method: Method = Method.Defined.ResourcesUnsubscribe
    abstract override val params: Params

    interface Params : BaseRequestParams {
        /**
         * The URI of the resource to unsubscribe from.
         */
        val uri: String
    }
}

/**
 * A notification from the server to the client, informing it that a resource has changed and may need to be read again. This should only be sent if the client previously sent a resources/subscribe request.
 */
abstract class ResourceUpdatedNotification : ServerNotification {
    final override val method: Method = Method.Defined.NotificationsResourcesUpdated

    abstract override val params: Params

    interface Params : BaseNotificationParams {
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
interface PromptArgument {
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
interface Prompt {
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
    val arguments: Array<PromptArgument>?
}

/**
 * Sent from the client to request a list of prompts and prompt templates the server has.
 */
abstract class ListPromptsRequest : ClientRequest, PaginatedRequest {
    final override val method: Method = Method.Defined.PromptsList
}

/**
 * The server's response to a prompts/list request from the client.
 */
interface ListPromptsResult : ServerResult, PaginatedResult {
    val prompts: Array<Prompt>
}

/**
 * Used by the client to get a prompt provided by the server.
 */
abstract class GetPromptRequest : ClientRequest {
    final override val method: Method = Method.Defined.PromptsGet
    abstract override val params: Params

    interface Params : BaseRequestParams {
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

sealed interface PromptMessageContent {
    val type: String
}

sealed interface PromptMessageContentTextOrImage : PromptMessageContent

/**
 * Text provided to or from an LLM.
 */
abstract class TextContent : PromptMessageContentTextOrImage {
    final override val type: String = "text"

    /**
     * The text content of the message.
     */
    abstract val text: String
}

/**
 * An image provided to or from an LLM.
 */
abstract class ImageContent : PromptMessageContentTextOrImage {
    final override val type: String = "image"

    /**
     * The base64-encoded image data.
     *
     * TODO check that it is base64
     */
    abstract val data: String

    /**
     * The MIME type of the image. Different providers may support different image types.
     *
     * TODO ktor's mime
     */
    abstract val mimeType: String
}

/**
 * The contents of a resource, embedded into a prompt or tool call result.
 */
abstract class EmbeddedResource : PromptMessageContent {
    final override val type: String = "resource"
    abstract val resource: ResourceContents
}

@Suppress("EnumEntryName")
enum class Role {
    user, assistant,
}

/**
 * Describes a message returned as part of a prompt.
 */
interface PromptMessage {
    val role: Role

    val content: PromptMessageContent
}

/**
 * The server's response to a prompts/get request from the client.
 */
interface GetPromptResult : ServerResult {
    /**
     * An optional description for the prompt.
     */
    val description: String?
    val messages: Array<PromptMessage>
}

/**
 * An optional notification from the server to the client, informing it that the list of prompts it offers has changed. This may be issued by servers without any previous subscription from the client.
 */
abstract class PromptListChangedNotification : ServerNotification {
    final override val method: Method = Method.Defined.NotificationsPromptsListChanged
}

/* Tools */
/**
 * Definition for a tool the client can call.
 */
interface Tool {
    /**
     * The name of the tool.
     */
    val name: String

    /**
     * A human-readable description of the tool.
     */
    val description: String?

    /**
     * A JSON  object defining the expected parameters for the tool.
     */
    val input: Input

    abstract class Input {
        val type: String = "object"
        abstract val properties: JsonObject?
    }
}

/**
 * Sent from the client to request a list of tools the server has.
 */
abstract class ListToolsRequest : ClientRequest, PaginatedRequest {
    final override val method: Method = Method.Defined.ToolsList
}

/**
 * The server's response to a tools/list request from the client.
 */
interface ListToolsResult : ServerResult, PaginatedResult {
    val tools: Array<Tool>
}

/**
 * The server's response to a tool call.
 */
interface CallToolResult : ServerResult {
    val content: PromptMessageContent
    val isError: Boolean? get() = false
}

/**
 * CallToolResult extended with backwards compatibility to protocol version 2024-10-07.
 */
interface CompatibilityCallToolResult : CallToolResult {
    val toolResult: JsonObject?
}

/**
 * Used by the client to invoke a tool provided by the server.
 */
abstract class CallToolRequest : ClientRequest {
    final override val method: Method = Method.Defined.ToolsCall
    abstract override val params: Params

    interface Params : BaseRequestParams {
        val name: String
        val arguments: Map<String, JsonObject?>?
    }
}

/**
 * An optional notification from the server to the client, informing it that the list of tools it offers has changed. This may be issued by servers without any previous subscription from the client.
 */
abstract class ToolListChangedNotification : ServerNotification {
    final override val method: Method = Method.Defined.NotificationsToolsListChanged
}

/* Logging */
/**
 * The severity of a log message.
 */
@Suppress("EnumEntryName")
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
 * A request from the client to the server, to enable or adjust logging.
 */
abstract class SetLevelRequest : ClientRequest {
    final override val method: Method = Method.Defined.LoggingSetLevel
    abstract override val params: Params

    interface Params : BaseRequestParams {
        /**
         * The level of logging that the client wants to receive from the server. The server should send all logs at this level and higher (i.e., more severe) to the client as notifications/logging/message.
         */
        val level: LoggingLevel
    }
}

/**
 * Notification of a log message passed from server to client. If no logging/setLevel request has been sent from the client, the server MAY decide which messages to send automatically.
 */
abstract class LoggingMessageNotification : ServerNotification {
    final override val method: Method = Method.Defined.NotificationsMessage
    abstract override val params: Params

    interface Params : BaseNotificationParams {
        /**
         * The severity of this log message.
         */
        val level: LoggingLevel

        /**
         * An optional name of the logger issuing this message.
         */
        val logger: String?

        /**
         * The data to be logged, such as a string message or an object. Any JSON serializable type is allowed here.
         */
        val data: JsonObject?
    }
}

/* Sampling */
/**
 * Hints to use for model selection.
 */
interface ModelHint {
    /**
     * A hint for a model name.
     */
    val name: String?
}

/**
 * The server's preferences for model selection, requested of the client during sampling.
 */
interface ModelPreferences {
    /**
     * Optional hints to use for model selection.
     */
    val hints: Array<ModelHint>?

    /**
     * How much to prioritize cost when selecting a model.
     *
     * TODO MIN 0, MAX 1
     */
    val costPriority: Double?

    /**
     * How much to prioritize sampling speed (latency) when selecting a model.
     *
     * TODO MIN 0, MAX 1
     */
    val speedPriority: Double?

    /**
     * How much to prioritize intelligence and capabilities when selecting a model.
     *
     * TODO MIN 0, MAX 1
     */
    val intelligencePriority: Double?
}

/**
 * Describes a message issued to or received from an LLM API.
 */
interface SamplingMessage {
    val role: Role
    val content: PromptMessageContentTextOrImage
}

/**
 * A request from the server to sample an LLM via the client. The client has full discretion over which model to select. The client should also inform the user before beginning sampling, to allow them to inspect the request (human in the loop) and decide whether to approve it.
 */
abstract class CreateMessageRequest : ServerRequest {
    final override val method: Method = Method.Defined.SamplingCreateMessage
    abstract override val params: Params

    interface Params : BaseRequestParams {
        val messages: Array<SamplingMessage>

        /**
         * An optional system prompt the server wants to use for sampling. The client MAY modify or omit this prompt.
         */
        val systemPrompt: String?

        /**
         * A request to include context from one or more MCP servers (including the caller), to be attached to the prompt. The client MAY ignore this request.
         */
        val includeContext: IncludeContext?
        val temperature: Double?

        /**
         * The maximum number of tokens to sample, as requested by the server. The client MAY choose to sample fewer tokens than requested.
         */
        val maxTokens: Int
        val stopSequences: Array<String>?

        /**
         * Optional metadata to pass through to the LLM provider. The format of this metadata is provider-specific.
         */
        val metadata: JsonObject?

        /**
         * The server's preferences for which model to select.
         */
        val modelPreferences: ModelPreferences?

        enum class IncludeContext { none, thisServer, allServers }
    }
}

/**
 * The client's response to a sampling/create_message request from the server. The client should inform the user before returning the sampled message, to allow them to inspect the response (human in the loop) and decide whether to allow the server to see it.
 */
interface CreateMessageResult : ClientResult {
    /**
     * The name of the model that generated the message.
     */
    val model: String

    /**
     * The reason why sampling stopped.
     */
    val stopReason: StopReason?
    val role: Role

    val content: PromptMessageContentTextOrImage

    sealed interface StopReason {
        val value: String

        data object EndTurn : StopReason {
            override val value: String = "endTurn"
        }

        data object StopSequence : StopReason {
            override val value: String = "stopSequence"
        }

        data object MaxTokens : StopReason {
            override val value: String = "maxTokens"
        }

        @JvmInline
        value class Other(override val value: String) : StopReason
    }
}

/* Autocomplete */
sealed interface Reference

/**
 * A reference to a resource or resource template definition.
 */
abstract class ResourceReference : Reference {
    val type: String = "ref/resource"

    /**
     * The URI or URI template of the resource.
     */
    abstract val uri: String
}

/**
 * Identifies a prompt.
 */
abstract class PromptReference : Reference {
    val type: String = "ref/prompt"

    /**
     * The name of the prompt or prompt template
     */
    abstract val name: String
}


/**
 * A request from the client to the server, to ask for completion options.
 */
abstract class CompleteRequest : ClientRequest {
    final override val method: Method = Method.Defined.CompletionComplete
    abstract override val params: Params

    interface Params : BaseRequestParams {
        val ref: Reference

        /**
         * The argument's information
         */
        val argument: Argument

        interface Argument {
            /**
             * The name of the argument
             */
            val name: String

            /**
             * The value of the argument to use for completion matching.
             */
            val value: String
        }
    }
}

/**
 * The server's response to a completion/complete request
 */
interface CompleteResult : ServerResult {
    val completion: Completion

    interface Completion {
        /**
         * An array of completion values. Must not exceed 100 items.
         *
         * TODO max 100 values
         */
        val values: Array<String>

        /**
         * The total number of completion options available. This can exceed the number of values actually sent in the response.
         */
        val total: Int?

        /**
         * Indicates whether there are additional completion options beyond those provided in the current response, even if the exact total is unknown.
         */
        val hasMore: Boolean?
    }
}

/* Roots */
/**
 * Represents a root directory or file that the server can operate on.
 */
interface Root {
    /**
     * The URI identifying the root. This *must* start with file:// for now.
     *
     * TODO startsWith("file://"),
     */
    val uri: String

    /**
     * An optional name for the root.
     */
    val name: String?
}

/**
 * Sent from the server to request a list of root URIs from the client.
 */
abstract class ListRootsRequest : ServerRequest {
    final override val method: Method = Method.Defined.RootsList
}

/**
 * The client's response to a roots/list request from the server.
 */
interface ListRootsResult : ClientResult {
    val roots: Array<Root>
}

/**
 * A notification from the client to the server, informing it that the list of roots has changed.
 */
abstract class RootsListChangedNotification : ClientNotification {
    final override val method: Method = Method.Defined.NotificationsRootsListChanged
}

@Suppress("CanBeParameter")
class McpError(val code: Int, message: String, val data: JsonObject? = null) : Exception() {
    override val message: String = "MCP error ${code}: $message"
}
