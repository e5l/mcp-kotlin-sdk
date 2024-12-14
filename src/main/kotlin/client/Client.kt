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
import EmptyRequestResult
import GetPromptRequest
import GetPromptResult
import Implementation
import InitializeRequest
import InitializeResult
import InitializedNotification
import LATEST_PROTOCOL_VERSION
import ListPromptsRequest
import ListPromptsResult
import ListResourceTemplatesRequest
import ListResourceTemplatesResult
import ListResourcesRequest
import ListResourcesResult
import ListToolsRequest
import ListToolsResult
import LoggingLevel
import LoggingMessageNotification.SetLevelRequest
import Method
import PingRequest
import ReadResourceRequest
import ReadResourceResult
import RootsListChangedNotification
import SUPPORTED_PROTOCOL_VERSIONS
import ServerCapabilities
import SubscribeRequest
import UnsubscribeRequest
import shared.Protocol
import shared.ProtocolOptions
import shared.RequestOptions
import shared.Transport

class ClientOptions(
    /**
     * Capabilities to advertise as being supported by this client.
     */
    val capabilities: ClientCapabilities = ClientCapabilities(),
    enforceStrictCapabilities: Boolean = true,
) : ProtocolOptions(enforceStrictCapabilities = enforceStrictCapabilities)

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
open class Client(
    private val clientInfo: Implementation,
    options: ClientOptions = ClientOptions(),
) : Protocol<ClientRequest, ClientNotification, ClientResult>(options) {

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


    override suspend fun connect(transport: Transport) {
        super.connect(transport)

        try {
            val message = InitializeRequest(
                protocolVersion = LATEST_PROTOCOL_VERSION,
                capabilities = capabilities,
                clientInfo = clientInfo
            )
            val result = request<InitializeResult>(message)

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

            notification(InitializedNotification())
        } catch (error: Throwable) {
            close()
            throw error
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
            Method.Defined.CompletionComplete,
                -> {
                if (serverCapabilities?.prompts == null) {
                    throw IllegalStateException("Server does not support prompts (required for $method)")
                }
            }

            Method.Defined.ResourcesList,
            Method.Defined.ResourcesTemplatesList,
            Method.Defined.ResourcesRead,
            Method.Defined.ResourcesSubscribe,
            Method.Defined.ResourcesUnsubscribe,
                -> {
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
            Method.Defined.ToolsList,
                -> {
                if (serverCapabilities?.tools == null) {
                    throw IllegalStateException("Server does not support tools (required for $method)")
                }
            }

            Method.Defined.Initialize,
            Method.Defined.Ping,
                -> {
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
            Method.Defined.NotificationsProgress,
                -> {
                // Always allowed
            }

            else -> {
                // For notifications not specifically listed, no assertion by default
            }
        }
    }

    override fun assertRequestHandlerCapability(method: Method) {
        when (method) {
            Method.Defined.SamplingCreateMessage -> {
                if (capabilities.sampling == null) {
                    throw IllegalStateException(
                        "Client does not support sampling capability (required for $method)"
                    )
                }
            }

            Method.Defined.RootsList -> {
                if (capabilities.roots == null) {
                    throw IllegalStateException(
                        "Client does not support roots capability (required for $method)"
                    )
                }
            }

            Method.Defined.Ping -> {
                // No capability required
            }

            else -> {}
        }
    }

    suspend fun ping(options: RequestOptions? = null) {
        request<EmptyRequestResult>(PingRequest(), options)
    }

    suspend fun complete(params: CompleteRequest, options: RequestOptions? = null): CompleteResult? {
        return request<CompleteResult>(params, options)
    }

    suspend fun setLoggingLevel(level: LoggingLevel, options: RequestOptions? = null) {
        request<EmptyRequestResult>(
            SetLevelRequest(level),
            options
        )
    }

    suspend fun getPrompt(request: GetPromptRequest, options: RequestOptions? = null): GetPromptResult? {
        return request<GetPromptResult>(request, options)
    }

    suspend fun listPrompts(
        request: ListPromptsRequest = ListPromptsRequest(),
        options: RequestOptions? = null,
    ): ListPromptsResult? {
        return request<ListPromptsResult>(request, options)
    }

    suspend fun listResources(
        request: ListResourcesRequest = ListResourcesRequest(),
        options: RequestOptions? = null,
    ): ListResourcesResult? {
        return request<ListResourcesResult>(request, options)
    }

    suspend fun listResourceTemplates(
        request: ListResourceTemplatesRequest,
        options: RequestOptions? = null,
    ): ListResourceTemplatesResult? {
        return request<ListResourceTemplatesResult>(
            request,
            options
        )
    }

    suspend fun readResource(request: ReadResourceRequest, options: RequestOptions? = null): ReadResourceResult? {
        return request<ReadResourceResult>(
            request,
            options
        )
    }

    suspend fun subscribeResource(request: SubscribeRequest, options: RequestOptions? = null) {
        request<EmptyRequestResult>(
            request,
            options
        )
    }

    suspend fun unsubscribeResource(request: UnsubscribeRequest, options: RequestOptions? = null) {
        request<EmptyRequestResult>(
            request,
            options
        )
    }

    suspend fun callTool(
        reuquest: CallToolRequest,
        compatibility: Boolean = false,
        options: RequestOptions? = null,
    ): CallToolResultBase? {
        return if (compatibility) {
            request<CompatibilityCallToolResult>(
                reuquest,
                options
            )
        } else {
            request<CallToolResult>(
                reuquest,
                options
            )
        }
    }

    suspend fun listTools(
        request: ListToolsRequest = ListToolsRequest(),
        options: RequestOptions? = null,
    ): ListToolsResult? =
        request<ListToolsResult>(
            request,
            options
        )

    suspend fun sendRootsListChanged() {
        notification(RootsListChangedNotification())
    }
}
