package server

import Implementation
import ServerCapabilities
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.application
import io.ktor.server.routing.post
import io.ktor.server.sse.ServerSSESession
import io.ktor.server.sse.sse
import io.ktor.util.AttributeKey
import io.ktor.util.Attributes
import kotlinx.coroutines.CompletableDeferred
import shared.IMPLEMENTATION_NAME
import shared.LIB_VERSION

typealias IncomingHandler = suspend RoutingContext.(forward: suspend () -> Unit) -> Unit

fun Route.mcpSse(
    options: ServerOptions? = null,
    incomingPath: String = "",
    incomingHandler: (IncomingHandler)? = null,
    handler: suspend Server.() -> Unit = {},
) {
    sse {
        createMcpServer(this, incomingPath, options, handler)
    }

    setupPostRoute(incomingPath, incomingHandler)
}

fun Route.mcpSse(
    path: String,
    incomingPath: String = path,
    options: ServerOptions? = null,
    incomingHandler: (IncomingHandler)? = null,
    handler: suspend Server.() -> Unit = {},
) {
    sse(path) {
        createMcpServer(this, incomingPath, options, handler)
    }

    setupPostRoute(incomingPath, incomingHandler)
}

internal val McpServersKey = AttributeKey<Attributes>("mcp-servers")

private fun String.asAttributeKey() = AttributeKey<SseMcpServerTransport>(this)

private suspend fun Route.forwardMcpMessage(call: ApplicationCall) {
    val sessionId = call.request.queryParameters[SESSION_ID_PARAM]
        ?.asAttributeKey()
        ?: run {
            call.sessionNotFound()
            return
        }

    application.attributes.getOrNull(McpServersKey)
        ?.get(sessionId)
        ?.handlePostMessage(call)
        ?: call.sessionNotFound()
}

private suspend fun ApplicationCall.sessionNotFound() {
    respondText("Session not found", status = HttpStatusCode.NotFound)
}

private fun Route.setupPostRoute(incomingPath: String, incomingHandler: IncomingHandler?) {
    post(incomingPath) {
        if (incomingHandler != null) {
            incomingHandler {
                forwardMcpMessage(call)
            }
        } else {
            forwardMcpMessage(call)
        }
    }
}

private suspend fun Route.createMcpServer(
    session: ServerSSESession,
    incomingPath: String,
    options: ServerOptions?,
    handler: suspend Server.() -> Unit = {},
) {
    val transport = SseMcpServerTransport(
        endpoint = incomingPath,
        session = session,
    )

    val closed = CompletableDeferred<Unit>()

    val server = Server(
        _serverInfo = Implementation(
            name = IMPLEMENTATION_NAME,
            version = LIB_VERSION,
        ),
        options = options ?: ServerOptions(
            capabilities = ServerCapabilities(
                prompts = ServerCapabilities.Prompts(listChanged = null),
                resources = ServerCapabilities.Resources(subscribe = null, listChanged = null),
                tools = ServerCapabilities.Tools(listChanged = null),
                logging = null  // or a valid empty JSON object if required
            )
        ),
        onCloseCallback = {
            closed.complete(Unit)
        },
    )

    application.attributes
        .computeIfAbsent(McpServersKey) { Attributes(concurrent = true) }
        .put(transport.sessionId.asAttributeKey(), transport)

    server.connect(transport)
    handler(server)
    server.close()
}
