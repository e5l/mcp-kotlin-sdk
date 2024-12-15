package client

import org.jetbrains.kotlinx.mcp.Implementation
import org.jetbrains.kotlinx.mcp.ListToolsResult
import kotlinx.coroutines.test.runTest
import org.jetbrains.kotlinx.mcp.client.Client
import org.jetbrains.kotlinx.mcp.client.StdioClientTransport
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import java.net.Socket

class ClientIntegrationTest {

    fun createTransport(): StdioClientTransport {
        val socket = Socket("localhost", 3000)

        return StdioClientTransport(socket.inputStream, socket.outputStream)
    }

    @Disabled("This test requires a running server")
    @Test
    fun testRequestTools() = runTest {
        val client = Client(
            Implementation("test", "1.0"),
        )

        val transport = createTransport()
        try {
            client.connect(transport)

            val response: ListToolsResult? = client.listTools()
            println(response?.tools)

        } finally {
            transport.close()
        }
    }

}
