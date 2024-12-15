package org.jetbrains.kotlinx.mcp

import org.jetbrains.kotlinx.mcp.client.Client
import org.jetbrains.kotlinx.mcp.client.StdioClientTransport
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import io.ktor.util.collections.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import org.jetbrains.kotlinx.mcp.server.MCP
import org.jetbrains.kotlinx.mcp.server.SSEServerTransport
import org.jetbrains.kotlinx.mcp.server.Server
import org.jetbrains.kotlinx.mcp.server.ServerOptions
import org.jetbrains.kotlinx.mcp.server.StdioServerTransport

fun main(args: Array<String>) {
    if (args.isEmpty())
        return
    val first = args[0]
    when (first) {
        "--server" -> runServer()
        "--demo" -> runDemo()
        "--sse-server" -> {
            if (args.size < 2) {
                System.err.println("Missing port argument")
                return
            }
            val port = args[1].toIntOrNull()
            if (port == null) {
                System.err.println("Invalid port: ${args[1]}")
                return
            }
            runSseServer(port)
        }

        else -> {
            System.err.println("Unknown argument: $first")
        }
    }
}

private fun runDemo() {
    val processBuilder = ProcessBuilder("npx", "-y", "@jetbrains/mcp-proxy")

    var process: Process? = null
    try {
        process = processBuilder.start()

        val client = Client(
            Implementation("test", "1.0"),
        )
        val transport = StdioClientTransport(process.inputStream, process.outputStream)
        runBlocking {
            client.connect(transport)

            val serverCapabilities = client.getServerCapabilities()

            // Resources capability check
            serverCapabilities?.resources?.let {
                try {
                    val resources = client.listResources()
                    System.err.println("Resources: ${resources?.resources?.joinToString { it.name }}")
                } catch (e: Exception) {
                    System.err.println("Failed to list resources: ${e.message}")
                }
            }

            // Tools capability check
            serverCapabilities?.tools?.let {
                try {
                    val terminal = client.callTool("execute_terminal_command", mapOf("command" to "ls"))
                    System.err.println(terminal?.content?.first())

                    val tools = client.listTools()
                    System.err.println(tools?.tools?.joinToString(", ") { tool -> tool.name })

//                    tools?.tools?.reversed()?.find { it.name == "toggle_debugger_breakpoint" }?.let { org.jetbrains.kotlinx.mcp.callTool(client, it) }

                    tools?.tools?.reversed()?.forEachIndexed { i, tool ->
                        System.err.println("$i out of ${tools.tools.size}: ${tool.name}")
                        callTool(client, tool)
                    }
                } catch (e: Exception) {
                    System.err.println("Failed to list tools: ${e.message}")
                }
            }

            // Prompts capability check
            serverCapabilities?.prompts?.let {
                try {
                    val prompts = client.listPrompts()
                    System.err.println("Prompts: ${prompts?.prompts?.joinToString { it.name }}")
                } catch (e: Exception) {
                    System.err.println("Failed to list prompts: ${e.message}")
                }
            }
        }
    } finally {
        process?.destroy()
    }
}

private suspend fun callTool(client: Client, tool: Tool) {
    System.err.println(tool.inputSchema)

    val map = fillSchema(tool.inputSchema)

    System.err.println("calling: ${tool.name}: $map")
    val result = try {
        client.callTool(CallToolRequest(tool.name, map))
    } catch (e: Exception) {
        System.err.println("Failed to call tool ${tool.name}: ${e.message}")
        return
    }
    System.err.println("Result:  ${result?.content?.first()}\n")
}

private fun fillSchema(schema: Tool.Input): JsonObject {
    return buildJsonObject {
        schema.properties.forEach { name, elt ->
            val type = (elt.jsonObject["type"] as JsonPrimitive).content
            val value = when (type) {
                "string" -> JsonPrimitive("Hello")
                "number" -> JsonPrimitive(42)
                "boolean" -> JsonPrimitive(true)
                else -> {
                    System.err.println("+".repeat(30) + " Unknown type: $type " + "+".repeat(30))
                    JsonPrimitive("Unknown")
                }
            }
            put(name, value)
        }
    }

}

private fun runServer() {
    val def = CompletableDeferred<Unit>()

    val server = Server(
        Implementation(
            name = "mcp-kotlin test server",
            version = "0.1.0"
        ),
        ServerOptions(
            capabilities = ServerCapabilities(
                prompts = ServerCapabilities.Prompts(listChanged = true),
                resources = ServerCapabilities.Resources(subscribe = true, listChanged = true),
                tools = ServerCapabilities.Tools(listChanged = true),
            )
        ),
        onCloseCallback = {
            def.complete(Unit)
        }
    )

    server.addPrompt(
        name = "Kotlin Developer",
        description = "Develop small kotlin applications",
        arguments = listOf(
            PromptArgument(
                name = "Project Name",
                description = "Project name for the new project",
                required = true
            )
        )
    ) { request ->
        GetPromptResult(
            "Description for ${request.name}",
            messages = listOf(
                PromptMessage(
                    role = Role.user,
                    content = TextContent("Develop a kotlin project named <name>${request.arguments?.get("Project Name")}</name>")
                )
            )
        )
    }

    // Add a tool
    server.addTool(
        name = "Test org.jetbrains.kotlinx.mcp.Tool",
        description = "A test tool",
        inputSchema = Tool.Input()
    ) { request ->
        CallToolResult(
            content = listOf(TextContent("Hello, world!"))
        )
    }

    // Add a resource
    server.addResource(
        uri = "https://google.com/",
        name = "Google Search",
        description = "Web search engine",
        mimeType = "text/html"
    ) { request ->
        ReadResourceResult(
            contents = listOf(
                TextResourceContents("Placeholder content for ${request.uri}", request.uri, "text/html")
            )
        )
    }

    // Note: The server will handle listing prompts, tools, and resources automatically.
    // The handleListResourceTemplates will return empty as defined in the Server code.

    val transport = StdioServerTransport()

    val err = System.err

    runBlocking {
        server.connect(transport)
        err.println("Server running on stdio")
        def.await()
    }

    err.println("Server closed")
}

fun runSseServer(port: Int): Unit = runBlocking {
    val servers = ConcurrentMap<String, Server>()

    embeddedServer(CIO, host = "0.0.0.0", port = port) {
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

                // For SSE, you can also add prompts/tools/resources if needed:
                // server.addTool(...), server.addPrompt(...), server.addResource(...)

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
    }.start(wait = true)
}

fun runSseServerKtor(port: Int): Unit = runBlocking {
    embeddedServer(CIO, host = "0.0.0.0", port = port) {
        MCP {
            val options = ServerOptions(
                capabilities = ServerCapabilities(
                    prompts = ServerCapabilities.Prompts(listChanged = null),
                    resources = ServerCapabilities.Resources(subscribe = null, listChanged = null),
                )
            )

            Server(
                Implementation(
                    name = "mcp-kotlin test server",
                    version = "0.1.0"
                ),
                options
            )
        }
    }
}