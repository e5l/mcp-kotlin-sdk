package server

import Implementation
import ServerCapabilities
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.sse.sse
import io.ktor.util.collections.ConcurrentMap

fun Application.MCP(block: () -> Server) {
    val servers = ConcurrentMap<String, Server>()

    install(SSE)
    routing {
        sse("/sse") {
            val transport = SSEServerTransport("/message", this)
            val options = ServerOptions(
                capabilities = ServerCapabilities(
                    prompts = ServerCapabilities.Prompts(listChanged = null),
                    resources = ServerCapabilities.Resources(subscribe = null, listChanged = null),
                )
            )
            val server = Server(
                Implementation(
                    name = "mcp-kotlin test server",
                    version = "0.1.0"
                ),
                options
            )

            servers[transport.sessionId] = server

            server.onCloseCallback = {
                println("Server closed")
                servers.remove(transport.sessionId)
            }

            server.connect(transport)
        }
        post("/message") {
            println("Received Message")
            val sessionId: String = call.request.queryParameters["sessionId"]!!
            val transport = servers[sessionId]?.transport as? SSEServerTransport
            if (transport == null) {
                call.respond(HttpStatusCode.NotFound, "Session not found")
                return@post
            }

            transport.handlePostMessage(call)
        }
    }
}