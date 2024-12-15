package org.jetbrains.kotlinx.mcp.server

import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.kotlinx.mcp.CallToolRequest
import org.jetbrains.kotlinx.mcp.CallToolResult
import org.jetbrains.kotlinx.mcp.ClientCapabilities
import org.jetbrains.kotlinx.mcp.CreateMessageRequest
import org.jetbrains.kotlinx.mcp.CreateMessageResult
import org.jetbrains.kotlinx.mcp.EmptyJsonObject
import org.jetbrains.kotlinx.mcp.EmptyRequestResult
import org.jetbrains.kotlinx.mcp.GetPromptRequest
import org.jetbrains.kotlinx.mcp.GetPromptResult
import org.jetbrains.kotlinx.mcp.Implementation
import org.jetbrains.kotlinx.mcp.InitializeRequest
import org.jetbrains.kotlinx.mcp.InitializeResult
import org.jetbrains.kotlinx.mcp.InitializedNotification
import org.jetbrains.kotlinx.mcp.LATEST_PROTOCOL_VERSION
import org.jetbrains.kotlinx.mcp.ListPromptsRequest
import org.jetbrains.kotlinx.mcp.ListPromptsResult
import org.jetbrains.kotlinx.mcp.ListResourceTemplatesRequest
import org.jetbrains.kotlinx.mcp.ListResourceTemplatesResult
import org.jetbrains.kotlinx.mcp.ListResourcesRequest
import org.jetbrains.kotlinx.mcp.ListResourcesResult
import org.jetbrains.kotlinx.mcp.ListRootsRequest
import org.jetbrains.kotlinx.mcp.ListRootsResult
import org.jetbrains.kotlinx.mcp.ListToolsRequest
import org.jetbrains.kotlinx.mcp.ListToolsResult
import org.jetbrains.kotlinx.mcp.LoggingMessageNotification
import org.jetbrains.kotlinx.mcp.Method
import org.jetbrains.kotlinx.mcp.PingRequest
import org.jetbrains.kotlinx.mcp.Prompt
import org.jetbrains.kotlinx.mcp.PromptListChangedNotification
import org.jetbrains.kotlinx.mcp.ReadResourceRequest
import org.jetbrains.kotlinx.mcp.ReadResourceResult
import org.jetbrains.kotlinx.mcp.Resource
import org.jetbrains.kotlinx.mcp.ResourceListChangedNotification
import org.jetbrains.kotlinx.mcp.ResourceUpdatedNotification
import org.jetbrains.kotlinx.mcp.SUPPORTED_PROTOCOL_VERSIONS
import org.jetbrains.kotlinx.mcp.ServerCapabilities
import org.jetbrains.kotlinx.mcp.ServerNotification
import org.jetbrains.kotlinx.mcp.ServerRequest
import org.jetbrains.kotlinx.mcp.ServerResult
import org.jetbrains.kotlinx.mcp.Tool
import org.jetbrains.kotlinx.mcp.ToolListChangedNotification
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonObject
import org.jetbrains.kotlinx.mcp.PromptArgument
import org.jetbrains.kotlinx.mcp.shared.Protocol
import org.jetbrains.kotlinx.mcp.shared.ProtocolOptions
import org.jetbrains.kotlinx.mcp.shared.RequestOptions

private val logger = KotlinLogging.logger {}

class ServerOptions(
    val capabilities: ServerCapabilities,
    enforceStrictCapabilities: Boolean = true,
) : ProtocolOptions(enforceStrictCapabilities = enforceStrictCapabilities)

/**
 * An MCP server on top of a pluggable transport.
 *
 * This server automatically responds to the initialization flow as initiated by the client.
 *
 * To use with custom types, extend the base org.jetbrains.kotlinx.mcp.Request/org.jetbrains.kotlinx.mcp.Notification/Result types and pass them as type parameters:
 *
 * ```kotlin
 * // Custom data classes
 *
 * // Create typed server
 * class CustomServer(
 *   serverInfo: org.jetbrains.kotlinx.mcp.Implementation,
 *   options: ServerOptions
 * ) : Server<org.jetbrains.kotlinx.mcp.CustomRequest, CustomNotification, CustomResult>(serverInfo, options)
 * ```
 */
open class Server(
    private val serverInfo: Implementation,
    options: ServerOptions,
    var onCloseCallback : (() -> Unit)? = null
) : Protocol<ServerRequest, ServerNotification, ServerResult>(options) {

    private var clientCapabilities: ClientCapabilities? = null
    private var clientVersion: Implementation? = null
    private val capabilities: ServerCapabilities = options.capabilities

    private val tools = mutableMapOf<String, RegisteredTool>()
    private val prompts = mutableMapOf<String, RegisteredPrompt>()
    private val resources = mutableMapOf<String, RegisteredResource>()

    /**
     * Callback invoked when initialization has fully completed (i.e., the client has sent an `initialized` notification).
     */
    var onInitialized: (() -> Unit)? = null

    init {
        logger.debug { "Initializing MCP server with capabilities: $capabilities" }
        // Core protocol handlers
        setRequestHandler<InitializeRequest>(Method.Defined.Initialize) { request, _ ->
            handleInitialize(request)
        }
        setNotificationHandler<InitializedNotification>(Method.Defined.NotificationsInitialized) {
            onInitialized?.invoke()
            CompletableDeferred(Unit)
        }

        // Internal handlers for tools
        setRequestHandler<ListToolsRequest>(Method.Defined.ToolsList) { _, _ ->
            handleListTools()
        }
        setRequestHandler<CallToolRequest>(Method.Defined.ToolsCall) { request, _ ->
            handleCallTool(request)
        }

        // Internal handlers for prompts
        setRequestHandler<ListPromptsRequest>(Method.Defined.PromptsList) { _, _ ->
            handleListPrompts()
        }
        setRequestHandler<GetPromptRequest>(Method.Defined.PromptsGet) { request, _ ->
            handleGetPrompt(request)
        }

        // Internal handlers for resources
        setRequestHandler<ListResourcesRequest>(Method.Defined.ResourcesList) { _, _ ->
            handleListResources()
        }
        setRequestHandler<ReadResourceRequest>(Method.Defined.ResourcesRead) { request, _ ->
            handleReadResource(request)
        }
        setRequestHandler<ListResourceTemplatesRequest>(Method.Defined.ResourcesTemplatesList) { _, _ ->
            handleListResourceTemplates()
        }
    }

    override fun onclose() {
        logger.info { "Server connection closing" }
        onCloseCallback?.invoke()
    }

    /**
     * Register a single tool.
     */
    fun addTool(
        name: String,
        description: String,
        inputSchema: Tool.Input = Tool.Input(),
        handler: suspend (CallToolRequest) -> CallToolResult
    ) {
        if (capabilities.tools == null) {
            logger.error { "Failed to add tool '$name': Server does not support tools capability" }
            throw IllegalStateException("Server does not support tools capability. Enable it in ServerOptions.")
        }
        logger.info { "Registering tool: $name" }
        tools[name] = RegisteredTool(Tool(name, description, inputSchema), handler)
    }

    /**
     * Register multiple tools at once.
     */
    fun addTools(toolsToAdd: List<RegisteredTool>) {
        if (capabilities.tools == null) {
            logger.error { "Failed to add tools: Server does not support tools capability" }
            throw IllegalStateException("Server does not support tools capability.")
        }
        logger.info { "Registering ${toolsToAdd.size} tools" }
        for (rt in toolsToAdd) {
            logger.debug { "Registering tool: ${rt.tool.name}" }
            tools[rt.tool.name] = rt
        }
    }

    /**
     * Register a prompt.
     */
    fun addPrompt(prompt: Prompt, promptProvider: suspend (GetPromptRequest) -> GetPromptResult) {
        if (capabilities.prompts == null) {
            logger.error { "Failed to add prompt '${prompt.name}': Server does not support prompts capability" }
            throw IllegalStateException("Server does not support prompts capability.")
        }
        logger.info { "Registering prompt: ${prompt.name}" }
        prompts[prompt.name] = RegisteredPrompt(prompt, promptProvider)
    }

    /**
     * Register a prompt using individual parameters.
     */
    fun addPrompt(
        name: String,
        description: String? = null,
        arguments: List<PromptArgument>? = null,
        promptProvider: suspend (GetPromptRequest) -> GetPromptResult
    ) {
        val prompt = Prompt(name = name, description = description, arguments = arguments)
        addPrompt(prompt, promptProvider)
    }

    /**
     * Register multiple prompts.
     */
    fun addPrompts(promptsToAdd: List<RegisteredPrompt>) {
        if (capabilities.prompts == null) {
            logger.error { "Failed to add prompts: Server does not support prompts capability" }
            throw IllegalStateException("Server does not support prompts capability.")
        }
        logger.info { "Registering ${promptsToAdd.size} prompts" }
        for (rp in promptsToAdd) {
            logger.debug { "Registering prompt: ${rp.prompt.name}" }
            prompts[rp.prompt.name] = rp
        }
    }

    /**
     * Register a resource.
     */
    fun addResource(
        uri: String,
        name: String,
        description: String,
        mimeType: String = "text/html",
        readHandler: suspend (ReadResourceRequest) -> ReadResourceResult
    ) {
        if (capabilities.resources == null) {
            logger.error { "Failed to add resource '$name': Server does not support resources capability" }
            throw IllegalStateException("Server does not support resources capability.")
        }
        logger.info { "Registering resource: $name ($uri)" }
        resources[uri] = RegisteredResource(Resource(uri, name, description, mimeType), readHandler)
    }

    /**
     * Register multiple resources.
     */
    fun addResources(resourcesToAdd: List<RegisteredResource>) {
        if (capabilities.resources == null) {
            logger.error { "Failed to add resources: Server does not support resources capability" }
            throw IllegalStateException("Server does not support resources capability.")
        }
        logger.info { "Registering ${resourcesToAdd.size} resources" }
        for (r in resourcesToAdd) {
            logger.debug { "Registering resource: ${r.resource.name} (${r.resource.uri})" }
            resources[r.resource.uri] = r
        }
    }

    // --- Internal Handlers ---

    private suspend fun handleInitialize(request: InitializeRequest): InitializeResult {
        logger.info { "Handling initialize request from client ${request.clientInfo}" }
        clientCapabilities = request.capabilities
        clientVersion = request.clientInfo

        val requestedVersion = request.protocolVersion
        val protocolVersion = if (SUPPORTED_PROTOCOL_VERSIONS.contains(requestedVersion)) {
            requestedVersion
        } else {
            logger.warn { "Client requested unsupported protocol version $requestedVersion, falling back to $LATEST_PROTOCOL_VERSION" }
            LATEST_PROTOCOL_VERSION
        }

        return InitializeResult(
            protocolVersion = protocolVersion,
            capabilities = capabilities,
            serverInfo = serverInfo
        )
    }

    private suspend fun handleListTools(): ListToolsResult {
        val toolList = tools.values.map { it.tool }
        return ListToolsResult(tools = toolList, nextCursor = null)
    }

    private suspend fun handleCallTool(request: CallToolRequest): CallToolResult {
        logger.debug { "Handling tool call request for tool: ${request.name}" }
        val tool = tools[request.name]
            ?: run {
                logger.error { "Tool not found: ${request.name}" }
                throw IllegalArgumentException("Tool not found: ${request.name}")
            }
        logger.trace { "Executing tool ${request.name} with input: ${request.arguments}" }
        return tool.handler(request)
    }

    private suspend fun handleListPrompts(): ListPromptsResult {
        logger.debug { "Handling list prompts request" }
        return ListPromptsResult(prompts = prompts.values.map { it.prompt })
    }

    private suspend fun handleGetPrompt(request: GetPromptRequest): GetPromptResult {
        logger.debug { "Handling get prompt request for: ${request.name}" }
        val prompt = prompts[request.name]
            ?: run {
                logger.error { "Prompt not found: ${request.name}" }
                throw IllegalArgumentException("Prompt not found: ${request.name}")
            }
        return prompt.messageProvider(request)
    }

    private suspend fun handleListResources(): ListResourcesResult {
        logger.debug { "Handling list resources request" }
        return ListResourcesResult(resources = resources.values.map { it.resource })
    }

    private suspend fun handleReadResource(request: ReadResourceRequest): ReadResourceResult {
        logger.debug { "Handling read resource request for: ${request.uri}" }
        val resource = resources[request.uri]
            ?: run {
                logger.error { "Resource not found: ${request.uri}" }
                throw IllegalArgumentException("Resource not found: ${request.uri}")
            }
        return resource.readHandler(request)
    }

    private suspend fun handleListResourceTemplates(): ListResourceTemplatesResult {
        // If you have resource templates, return them here. For now, return empty.
        return ListResourceTemplatesResult(listOf())
    }

    /**
     * Capability assertions (from the first snippet)
     */
    override fun assertCapabilityForMethod(method: Method) {
        logger.trace { "Asserting capability for method: ${method.value}" }
        when (method.value) {
            "sampling/createMessage" -> {
                if (clientCapabilities?.sampling == null) {
                    logger.error { "Client capability assertion failed: sampling not supported" }
                    throw IllegalStateException("Client does not support sampling (required for ${method.value})")
                }
            }

            "roots/list" -> {
                if (clientCapabilities?.roots == null) {
                    throw IllegalStateException("Client does not support listing roots (required for ${method.value})")
                }
            }

            "ping" -> {
                // No specific capability required
            }
        }
    }

    override fun assertNotificationCapability(method: Method) {
        logger.trace { "Asserting notification capability for method: ${method.value}" }
        when (method.value) {
            "notifications/message" -> {
                if (capabilities.logging == null) {
                    logger.error { "Server capability assertion failed: logging not supported" }
                    throw IllegalStateException("Server does not support logging (required for ${method.value})")
                }
            }

            "notifications/resources/updated",
            "notifications/resources/list_changed" -> {
                if (capabilities.resources == null) {
                    throw IllegalStateException("Server does not support notifying about resources (required for ${method.value})")
                }
            }

            "notifications/tools/list_changed" -> {
                if (capabilities.tools == null) {
                    throw IllegalStateException("Server does not support notifying of tool list changes (required for ${method.value})")
                }
            }

            "notifications/prompts/list_changed" -> {
                if (capabilities.prompts == null) {
                    throw IllegalStateException("Server does not support notifying of prompt list changes (required for ${method.value})")
                }
            }

            "notifications/cancelled",
            "notifications/progress" -> {
                // Always allowed
            }
        }
    }

    override fun assertRequestHandlerCapability(method: Method) {
        logger.trace { "Asserting request handler capability for method: ${method.value}" }
        when (method.value) {
            "sampling/createMessage" -> {
                if (capabilities.sampling == null) {
                    logger.error { "Server capability assertion failed: sampling not supported" }
                    throw IllegalStateException("Server does not support sampling (required for $method)")
                }
            }

            "logging/setLevel" -> {
                if (capabilities.logging == null) {
                    throw IllegalStateException("Server does not support logging (required for $method)")
                }
            }

            "prompts/get",
            "prompts/list" -> {
                if (capabilities.prompts == null) {
                    throw IllegalStateException("Server does not support prompts (required for $method)")
                }
            }

            "resources/list",
            "resources/templates/list",
            "resources/read" -> {
                if (capabilities.resources == null) {
                    throw IllegalStateException("Server does not support resources (required for $method)")
                }
            }

            "tools/call",
            "tools/list" -> {
                if (capabilities.tools == null) {
                    throw IllegalStateException("Server does not support tools (required for $method)")
                }
            }

            "ping", "initialize" -> {
                // No capability required
            }
        }
    }

    /**
     * After initialization has completed, this will be populated with the client's reported capabilities.
     */
    fun getClientCapabilities(): ClientCapabilities? {
        return clientCapabilities
    }

    /**
     * After initialization has completed, this will be populated with information about the client's name and version.
     */
    fun getClientVersion(): Implementation? {
        return clientVersion
    }

    suspend fun ping(): EmptyRequestResult {
        return request<EmptyRequestResult>(PingRequest())
    }

    suspend fun createMessage(
        params: CreateMessageRequest,
        options: RequestOptions? = null
    ): CreateMessageResult {
        logger.debug { "Creating message with params: $params" }
        return request<CreateMessageResult>(params, options)
    }

    suspend fun listRoots(
        params: JsonObject = EmptyJsonObject,
        options: RequestOptions? = null
    ): ListRootsResult {
        logger.debug { "Listing roots with params: $params" }
        return request<ListRootsResult>(ListRootsRequest(params), options)
    }

    suspend fun sendLoggingMessage(params: LoggingMessageNotification) {
        logger.trace { "Sending logging message: ${params.data}" }
        notification(params)
    }

    suspend fun sendResourceUpdated(params: ResourceUpdatedNotification) {
        logger.debug { "Sending resource updated notification for: ${params.uri}" }
        notification(params)
    }

    suspend fun sendResourceListChanged() {
        logger.debug { "Sending resource list changed notification" }
        notification(ResourceListChangedNotification())
    }

    suspend fun sendToolListChanged() {
        logger.debug { "Sending tool list changed notification" }
        notification(ToolListChangedNotification())
    }

    suspend fun sendPromptListChanged() {
        logger.debug { "Sending prompt list changed notification" }
        notification(PromptListChangedNotification())
    }
}

/**
 * Wrapper classes for registered entities.
 */
data class RegisteredTool(
    val tool: Tool,
    val handler: suspend (CallToolRequest) -> CallToolResult
)

data class RegisteredPrompt(
    val prompt: Prompt,
    val messageProvider: suspend (GetPromptRequest) -> GetPromptResult
)

data class RegisteredResource(
    val resource: Resource,
    val readHandler: suspend (ReadResourceRequest) -> ReadResourceResult
)