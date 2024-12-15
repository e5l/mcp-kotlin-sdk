import client.Client
import client.StdioClientTransport
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import server.Server
import server.ServerOptions
import server.StdioServerTransport
import server.mcpSse

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
//                    val terminal = client.callTool(CallToolRequest("execute_terminal_command", buildJsonObject { put("command", "ls") }))
//                    System.err.println(terminal?.content?.first())

                    val tools = client.listTools()
                    System.err.println(tools?.tools?.joinToString(", ") { tool -> tool.name })

                    tools?.tools?.reversed()?.find { it.name == "toggle_debugger_breakpoint" }?.let {
                        callTool(client, it)
                    }

//                    tools?.tools?.reversed()?.forEachIndexed { i, tool ->
//                        System.err.println("$i out of ${tools.tools.size}: ${tool.name}")
//                        callTool(client, tool)
//                    }
                } catch (e: Exception) {
                    System.err.println("Failed to list tools: ${e.message}")
                }
            }

            // Prompts capability check (commented out but showing proper structure)
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

    server.setRequestHandler<ListPromptsRequest>(Method.Defined.PromptsList) { request, extra ->
        val prompt = Prompt(
            name = "Kotlin Developer",
            description = "Develop small kotlin applicatoins",
            arguments = listOf(
                PromptArgument(
                    name = "Project Name",
                    description = "Project name for the new project",
                    required = true
                )
            )
        )
        ListPromptsResult(
            prompts = listOf(prompt)
        )
    }

    server.setRequestHandler<GetPromptRequest>(Method.Defined.PromptsGet) { request, extra ->

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

    server.setRequestHandler<ListToolsRequest>(Method.Defined.ToolsList) { request, _ ->
        val tools = arrayOf(
            Tool(
                name = "Test Tool",
                description = "A test tool",
                inputSchema = Tool.Input(),
            )
        )
        ListToolsResult(
            tools = tools,
            nextCursor = null,
        )
    }

    server.setRequestHandler<CallToolRequest>(Method.Defined.ToolsCall) { request, _ ->
        val result: Array<PromptMessageContent> = arrayOf(
            TextContent(
                text = "Hello, world!"
            )
        )
        CallToolResult(
            content = result,
        )
    }

    server.setRequestHandler<ListResourcesRequest>(Method.Defined.ResourcesList) { request, _ ->
        val search = Resource(
            "https://google.com/",
            "Google Search",
            "Web search engine",
            "text/html"
        )

        ListResourcesResult(
            resources = listOf(search)
        )
    }

    server.setRequestHandler<ReadResourceRequest>(Method.Defined.ResourcesRead) { request, extra ->
        val uri: String = request.uri

        // Handles all resources at once

        ReadResourceResult(
            contents = listOf(
                TextResourceContents("Placeholder content for $uri", uri, "text/html")
            )
        )
    }

    server.setRequestHandler<ListResourceTemplatesRequest>(Method.Defined.ResourcesTemplatesList) { request, extra ->
        val element = ResourceTemplate(
            uriTemplate = "https://google.com/q={query}",
            name = "Google Search template",
            description = "Google search template",
            mimeType = "text/html"
        )
        ListResourceTemplatesResult(listOf(element))
    }

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

    embeddedServer(CIO, host = "0.0.0.0", port = port) {
        install(SSE)
        routing {
            mcpSse(options) {
                while (true) {
                    delay(100)
                }
            }
        }
    }.start(wait = true)
}
