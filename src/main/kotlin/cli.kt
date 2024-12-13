import client.Client
import client.StdioClientTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import server.Server
import server.ServerOptions
import server.StdioServerTransport

fun main(args: Array<String>) {
    if (args.isEmpty())
        return
    val first = args[0]
    when (first) {
        "--server" -> runServer()
        "--demo" -> runDemo()
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
            val tools = client.listTools()
            System.err.println("Tools: $tools")
        }
    } finally {
        process?.destroy()
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
                prompts = ServerCapabilities.Prompts(listChanged = null),
                resources = ServerCapabilities.Resources(subscribe = null, listChanged = null),
                tools = ServerCapabilities.Tools(listChanged = null),
            )
        ),
        onCloseCallback = {
            def.complete(Unit)
        }
    )

    server.setRequestHandler<ListToolsResult>(Method.Defined.ToolsList) { request, _ ->
        val tools = arrayOf(
            Tool(
                name = "Test Tool",
                description = "A test tool",
                input = Tool.Input(),
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
