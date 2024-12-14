import client.Client
import client.StdioClientTransport
import io.ktor.server.application.install
import io.ktor.server.cio.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
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
                    val tools = client.listTools()
                    tools?.tools?.forEach { tool ->
                        callTool(client, tool)
                    }
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
    System.err.println(tool.name)
    System.err.println(tool.inputSchema)
    val result = client.callTool(CallToolRequest(tool.name))
    System.err.println("Tool result: ${result?.content?.first()}")
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
                prompts = ServerCapabilities.Prompts(listChanged = null),
                resources = ServerCapabilities.Resources(subscribe = null, listChanged = null),
                tools = ServerCapabilities.Tools(listChanged = null),
            )
        ),
        onCloseCallback = {
            def.complete(Unit)
        }
    )

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

    embeddedServer(CIO, host="0.0.0.0", port = port) {
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
