
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import server.Server
import server.ServerOptions
import server.StdioServerTransport

fun main() {

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
        onCloseCallback =  {
            def.complete(Unit)
        }
    )

    val transport = StdioServerTransport()

    // If connect is a suspend function, we run it in a coroutine context:
    runBlocking {
        server.connect(transport)
        println("Server running on stdio")
        def.await()
    }

    println("Server closed")
}