package org.jetbrains.kotlinx.mcp.server

import org.jetbrains.kotlinx.mcp.ClientCapabilities
import org.jetbrains.kotlinx.mcp.CreateMessageRequest
import org.jetbrains.kotlinx.mcp.CreateMessageResult
import org.jetbrains.kotlinx.mcp.EmptyJsonObject
import org.jetbrains.kotlinx.mcp.EmptyRequestResult
import org.jetbrains.kotlinx.mcp.Implementation
import org.jetbrains.kotlinx.mcp.InitializeRequest
import org.jetbrains.kotlinx.mcp.InitializeResult
import org.jetbrains.kotlinx.mcp.InitializedNotification
import org.jetbrains.kotlinx.mcp.LATEST_PROTOCOL_VERSION
import org.jetbrains.kotlinx.mcp.ListRootsRequest
import org.jetbrains.kotlinx.mcp.ListRootsResult
import org.jetbrains.kotlinx.mcp.LoggingMessageNotification
import org.jetbrains.kotlinx.mcp.Method
import org.jetbrains.kotlinx.mcp.PingRequest
import org.jetbrains.kotlinx.mcp.PromptListChangedNotification
import org.jetbrains.kotlinx.mcp.ResourceListChangedNotification
import org.jetbrains.kotlinx.mcp.ResourceUpdatedNotification
import org.jetbrains.kotlinx.mcp.SUPPORTED_PROTOCOL_VERSIONS
import org.jetbrains.kotlinx.mcp.ServerCapabilities
import org.jetbrains.kotlinx.mcp.ServerNotification
import org.jetbrains.kotlinx.mcp.ServerRequest
import org.jetbrains.kotlinx.mcp.ServerResult
import org.jetbrains.kotlinx.mcp.ToolListChangedNotification
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonObject
import org.jetbrains.kotlinx.mcp.shared.Protocol
import org.jetbrains.kotlinx.mcp.shared.ProtocolOptions
import org.jetbrains.kotlinx.mcp.shared.RequestOptions

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
    private val _serverInfo: Implementation,
    options: ServerOptions,
    var onCloseCallback : (() -> Unit)? = null
) : Protocol<ServerRequest, ServerNotification, ServerResult>(options) {

    private var _clientCapabilities: ClientCapabilities? = null
    private var _clientVersion: Implementation? = null
    private val _capabilities: ServerCapabilities = options.capabilities

    /**
     * Callback invoked when initialization has fully completed (i.e., the client has sent an `initialized` notification).
     */
    var oninitialized: (() -> Unit)? = null

    init {
        // Register handlers for initialization flow
        setRequestHandler<InitializeRequest>(Method.Defined.Initialize) { request, _ ->
            _oninitialize(request) // Cast needed if generics differ
        }
        setNotificationHandler<InitializedNotification>(Method.Defined.NotificationsInitialized) { request ->
            oninitialized?.invoke()
            CompletableDeferred<Unit>() // TODO()
        }
    }

    override fun onclose() {
        onCloseCallback?.invoke()
    }

    override fun assertCapabilityForMethod(method: Method) {
        when (method.value) {
            "sampling/createMessage" -> {
                if (_clientCapabilities?.sampling == null) {
                    throw IllegalStateException("Client does not support sampling (required for ${method.value})")
                }
            }

            "roots/list" -> {
                if (_clientCapabilities?.roots == null) {
                    throw IllegalStateException("Client does not support listing roots (required for ${method.value})")
                }
            }

            "ping" -> {
                // No specific capability required
            }
        }
    }

    override fun assertNotificationCapability(method: Method) {
        when (method.value) {
            "notifications/message" -> {
                if (_capabilities.logging == null) {
                    throw IllegalStateException("Server does not support logging (required for ${method.value})")
                }
            }

            "notifications/resources/updated",
            "notifications/resources/list_changed" -> {
                if (_capabilities.resources == null) {
                    throw IllegalStateException("Server does not support notifying about resources (required for ${method.value})")
                }
            }

            "notifications/tools/list_changed" -> {
                if (_capabilities.tools == null) {
                    throw IllegalStateException("Server does not support notifying of tool list changes (required for ${method.value})")
                }
            }

            "notifications/prompts/list_changed" -> {
                if (_capabilities.prompts == null) {
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
        when (method.value) {
            "sampling/createMessage" -> {
                if (_capabilities.sampling == null) {
                    throw IllegalStateException("Server does not support sampling (required for $method)")
                }
            }

            "logging/setLevel" -> {
                if (_capabilities.logging == null) {
                    throw IllegalStateException("Server does not support logging (required for $method)")
                }
            }

            "prompts/get",
            "prompts/list" -> {
                if (_capabilities.prompts == null) {
                    throw IllegalStateException("Server does not support prompts (required for $method)")
                }
            }

            "resources/list",
            "resources/templates/list",
            "resources/read" -> {
                if (_capabilities.resources == null) {
                    throw IllegalStateException("Server does not support resources (required for $method)")
                }
            }

            "tools/call",
            "tools/list" -> {
                if (_capabilities.tools == null) {
                    throw IllegalStateException("Server does not support tools (required for $method)")
                }
            }

            "ping", "initialize" -> {
                // No capability required
            }
        }
    }

    private suspend fun _oninitialize(request: InitializeRequest): InitializeResult {
        val requestedVersion = request.protocolVersion
        _clientCapabilities = request.capabilities
        _clientVersion = request.clientInfo

        val protocolVersion = if (SUPPORTED_PROTOCOL_VERSIONS.contains(requestedVersion)) {
            requestedVersion
        } else {
            LATEST_PROTOCOL_VERSION
        }

        return InitializeResult(
            protocolVersion = protocolVersion,
            capabilities = getCapabilities(),
            serverInfo = _serverInfo
        )
    }

    /**
     * After initialization has completed, this will be populated with the client's reported capabilities.
     */
    fun getClientCapabilities(): ClientCapabilities? {
        return _clientCapabilities
    }

    /**
     * After initialization has completed, this will be populated with information about the client's name and version.
     */
    fun getClientVersion(): Implementation? {
        return _clientVersion
    }

    private fun getCapabilities(): ServerCapabilities {
        return _capabilities
    }

    suspend fun ping(): EmptyRequestResult {
        return request<EmptyRequestResult>(PingRequest())
    }

    suspend fun createMessage(
        params: CreateMessageRequest,
        options: RequestOptions? = null
    ): CreateMessageResult {
        // Assuming CreateMessageResultSchema not needed if we can just deserialize into org.jetbrains.kotlinx.mcp.CreateMessageResult
        return request<CreateMessageResult>(params, options)
    }

    suspend fun listRoots(
        params: JsonObject = EmptyJsonObject,
        options: RequestOptions? = null
    ): ListRootsResult {
        return request<ListRootsResult>(ListRootsRequest(params), options)
    }

    suspend fun sendLoggingMessage(params: LoggingMessageNotification) {
        notification(params)
    }

    suspend fun sendResourceUpdated(params: ResourceUpdatedNotification) {
        notification(params)
    }

    suspend fun sendResourceListChanged() {
        notification(ResourceListChangedNotification())
    }

    suspend fun sendToolListChanged() {
        notification(ToolListChangedNotification())
    }

    suspend fun sendPromptListChanged() {
        notification(PromptListChangedNotification())
    }
}
