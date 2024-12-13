import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import server.Server
import server.ServerOptions
import server.StdioServerTransport

fun main(args: Array<String>) {
    if (args.isNotEmpty() && args[0] == "--server") {
        runServer()
        return
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
                logging = null  // or a valid empty JSON object if required
            )
        ),
        onCloseCallback = {
            def.complete(Unit)
        }
    )

    val transport = StdioServerTransport()

    val err = System.err

    runBlocking {
        server.connect(transport)
        err.println("Server running on stdio")
        def.await()
    }

    err.println("Server closed")
}