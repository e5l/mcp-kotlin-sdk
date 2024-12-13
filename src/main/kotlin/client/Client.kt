package client

import CallToolRequest
import CallToolResult
import CallToolResultBase
import ClientCapabilities
import ClientNotification
import ClientRequest
import ClientResult
import CompatibilityCallToolResult
import CompleteRequest
import CompleteResult
import EmptyResult
import GetPromptRequest
import GetPromptResult
import Implementation
import InitializeRequest
import InitializeResult
import LATEST_PROTOCOL_VERSION
import ListPromptsResult
import ListResourceTemplatesResult
import ListResourcesResult
import ListToolsResult
import Method
import PingRequest
import ReadResourceRequest
import ReadResourceResult
import SUPPORTED_PROTOCOL_VERSIONS
import ServerCapabilities
import SetLevelRequest
import SubscribeRequest
import UnsubscribeRequest
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import shared.*

class ClientOptions(
    /**
     * Capabilities to advertise as being supported by this client.
     */
    val capabilities: ClientCapabilities
) : ProtocolOptions()

/**
 * An MCP client on top of a pluggable transport.
 *
 * The client will automatically begin the initialization flow with the server when connect() is called.
 *
 * To use with custom types, define custom request/notification/result types and pass them as type parameters:
 *
 * Example:
 * ```kotlin
 * // Define custom data classes/types for requests, notifications, and results.
 *
 * class CustomClient(
 *     clientInfo: Implementation,
 *     options: ClientOptions
 * ) : Client<CustomRequest, CustomNotification, CustomResult>(clientInfo, options)
 * ```
 */
open class Client<
        RequestT : ClientRequest,
        NotificationT : ClientNotification,
        ResultT : ClientResult
        >(
    private val clientInfo: Implementation,
    options: ClientOptions
) : Protocol<RequestT, NotificationT, ResultT>(options) {

    private var serverCapabilities: ServerCapabilities? = null
    private var serverVersion: Implementation? = null
    private val capabilities: ClientCapabilities = options.capabilities

    protected fun assertCapability(capability: String, method: String) {
        val caps = serverCapabilities
        val hasCapability = when (capability) {
            "logging" -> caps?.logging != null
            "prompts" -> caps?.prompts != null
            "resources" -> caps?.resources != null
            "tools" -> caps?.tools != null
            else -> true
        }

        if (!hasCapability) {
            throw IllegalStateException("Server does not support $capability (required for $method)")
        }
    }


    override fun connect(transport: Transport): Deferred<Unit> {
        return GlobalScope.async {
            super.connect(transport).await()

            try {
                val result = request<InitializeResult>(
                    InitializeRequest(
                        params = InitializeRequest.Params(
                            protocolVersion = LATEST_PROTOCOL_VERSION,
                            capabilities = capabilities,
                            clientInfo = clientInfo
                        )
                    )
                ).await()

                if (result == null) {
                    throw IllegalStateException("Server sent invalid initialize result.")
                }

                if (!SUPPORTED_PROTOCOL_VERSIONS.contains(result.protocolVersion)) {
                    throw IllegalStateException(
                        "Server's protocol version is not supported: ${result.protocolVersion}"
                    )
                }

                serverCapabilities = result.capabilities
                serverVersion = result.serverInfo

                notification(
                    NotificationBase(
                        method = Method.Defined.NotificationsInitialized
                    )
                )
            } catch (error: Throwable) {
                close()
                throw error
            }
        }
    }

    /**
     * After initialization has completed, this will be populated with the server's reported capabilities.
     */
    fun getServerCapabilities(): ServerCapabilities? {
        return serverCapabilities
    }

    /**
     * After initialization has completed, this will be populated with information about the server's name and version.
     */
    fun getServerVersion(): Implementation? {
        return serverVersion
    }

    override fun assertCapabilityForMethod(method: Method) {
        when (method) {
            Method.Defined.LoggingSetLevel -> {
                if (serverCapabilities?.logging == null) {
                    throw IllegalStateException("Server does not support logging (required for $method)")
                }
            }

            Method.Defined.PromptsGet,
            Method.Defined.PromptsList,
            Method.Defined.CompletionComplete -> {
                if (serverCapabilities?.prompts == null) {
                    throw IllegalStateException("Server does not support prompts (required for $method)")
                }
            }

            Method.Defined.ResourcesList,
            Method.Defined.ResourcesTemplatesList,
            Method.Defined.ResourcesRead,
            Method.Defined.ResourcesSubscribe,
            Method.Defined.ResourcesUnsubscribe -> {
                val resCaps = serverCapabilities?.resources
                if (resCaps == null) {
                    throw IllegalStateException("Server does not support resources (required for $method)")
                }

                if (method == Method.Defined.ResourcesSubscribe && resCaps.subscribe != true) {
                    throw IllegalStateException(
                        "Server does not support resource subscriptions (required for $method)"
                    )
                }
            }

            Method.Defined.ToolsCall,
            Method.Defined.ToolsList -> {
                if (serverCapabilities?.tools == null) {
                    throw IllegalStateException("Server does not support tools (required for $method)")
                }
            }

            Method.Defined.Initialize,
            Method.Defined.Ping -> {
                // No specific capability required
            }

            else -> {
                // For unknown or future methods, no assertion by default
            }
        }
    }

    override fun assertNotificationCapability(method: Method) {
        when (method) {
            Method.Defined.NotificationsRootsListChanged -> {
                if (capabilities.roots?.listChanged != true) {
                    throw IllegalStateException(
                        "Client does not support roots list changed notifications (required for $method)"
                    )
                }
            }

            Method.Defined.NotificationsInitialized,
            Method.Defined.NotificationsCancelled,
            Method.Defined.NotificationsProgress -> {
                // Always allowed
            }

            else -> {
                // For notifications not specifically listed, no assertion by default
            }
        }
    }

    override fun assertRequestHandlerCapability(method: String) {
        when (method) {
            "sampling/createMessage" -> {
                if (capabilities.sampling == null) {
                    throw IllegalStateException(
                        "Client does not support sampling capability (required for $method)"
                    )
                }
            }

            "roots/list" -> {
                if (capabilities.roots == null) {
                    throw IllegalStateException(
                        "Client does not support roots capability (required for $method)"
                    )
                }
            }

            "ping" -> {
                // No capability required
            }
        }
    }

    suspend fun ping(options: RequestOptions? = null) {
        request<EmptyResult>(PingRequest(), options).await()
    }

    suspend fun complete(params: CompleteRequest.Params, options: RequestOptions? = null): CompleteResult? {
        return request(
            RequestBase(method = Method.Defined.CompletionComplete, params = params),
            CompleteResult::class,
            options
        )
    }

    suspend fun setLoggingLevel(level: LoggingLevel, options: RequestOptions? = null) {
        request(
            RequestBase(method = Method.Defined.LoggingSetLevel, params = SetLevelRequest.Params(level)),
            EmptyResult::class,
            options
        )
    }

    suspend fun getPrompt(params: GetPromptRequest.Params, options: RequestOptions? = null): GetPromptResult? {
        return request(
            RequestBase(method = Method.Defined.PromptsGet, params = params),
            GetPromptResult::class,
            options
        )
    }

    suspend fun listPrompts(
        params: ListPromptsRequest.Params? = null,
        options: RequestOptions? = null
    ): ListPromptsResult? {
        return request(
            RequestBase(method = Method.Defined.PromptsList, params = params),
            ListPromptsResult::class,
            options
        )
    }

    suspend fun listResources(
        params: ListResourcesRequest.Params? = null,
        options: RequestOptions? = null
    ): ListResourcesResult? {
        return request(
            RequestBase(method = Method.Defined.ResourcesList, params = params),
            ListResourcesResult::class,
            options
        )
    }

    suspend fun listResourceTemplates(
        params: ListResourceTemplatesRequest.Params? = null,
        options: RequestOptions? = null
    ): ListResourceTemplatesResult? {
        return request(
            RequestBase(method = Method.Defined.ResourcesTemplatesList, params = params),
            ListResourceTemplatesResult::class,
            options
        )
    }

    suspend fun readResource(params: ReadResourceRequest.Params, options: RequestOptions? = null): ReadResourceResult? {
        return request(
            RequestBase(method = Method.Defined.ResourcesRead, params = params),
            ReadResourceResult::class,
            options
        )
    }

    suspend fun subscribeResource(params: SubscribeRequest.Params, options: RequestOptions? = null) {
        request(
            RequestBase(method = Method.Defined.ResourcesSubscribe, params = params),
            EmptyResult::class,
            options
        )
    }

    suspend fun unsubscribeResource(params: UnsubscribeRequest.Params, options: RequestOptions? = null) {
        request(
            RequestBase(method = Method.Defined.ResourcesUnsubscribe, params = params),
            EmptyResult::class,
            options
        )
    }

    suspend fun callTool(
        params: CallToolRequest.Params,
        compatibility: Boolean = false,
        options: RequestOptions? = null
    ): CallToolResultBase? {
        val resultClass = if (compatibility) CompatibilityCallToolResult::class else CallToolResult::class
        return request(
            RequestBase(method = Method.Defined.ToolsCall, params = params),
            resultClass,
            options
        )
    }

    suspend fun listTools(params: ListToolsRequest.Params? = null, options: RequestOptions? = null): ListToolsResult? {
        return request(
            RequestBase(method = Method.Defined.ToolsList, params = params),
            ListToolsResult::class,
            options
        )
    }

    suspend fun sendRootsListChanged() {
        notification(NotificationBase(method = Method.Defined.NotificationsRootsListChanged))
    }
}