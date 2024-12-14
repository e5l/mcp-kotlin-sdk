package server

import ClientCapabilities
import CreateMessageRequest
import CreateMessageResult
import EmptyJsonObject
import EmptyRequestResult
import Implementation
import InitializeRequest
import InitializeResult
import InitializedNotification
import LATEST_PROTOCOL_VERSION
import ListRootsRequest
import ListRootsResult
import LoggingMessageNotification
import Method
import PingRequest
import PromptListChangedNotification
import ResourceListChangedNotification
import ResourceUpdatedNotification
import SUPPORTED_PROTOCOL_VERSIONS
import ServerCapabilities
import ServerNotification
import ServerRequest
import ServerResult
import ToolListChangedNotification
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonObject
import shared.Protocol
import shared.ProtocolOptions
import shared.RequestOptions

class ServerOptions(
    val capabilities: ServerCapabilities,
    enforceStrictCapabilities: Boolean = true,
) : ProtocolOptions(enforceStrictCapabilities = enforceStrictCapabilities)

/**
 * An MCP server on top of a pluggable transport.
 *
 * This server automatically responds to the initialization flow as initiated by the client.
 *
 * To use with custom types, extend the base Request/Notification/Result types and pass them as type parameters:
 *
 * ```kotlin
 * // Custom data classes
 *
 * // Create typed server
 * class CustomServer(
 *   serverInfo: Implementation,
 *   options: ServerOptions
 * ) : Server<CustomRequest, CustomNotification, CustomResult>(serverInfo, options)
 * ```
 */
open class Server(
    private val _serverInfo: Implementation,
    options: ServerOptions,
    val onCloseCallback : (() -> Unit)? = null
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
        // Assuming CreateMessageResultSchema not needed if we can just deserialize into CreateMessageResult
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
