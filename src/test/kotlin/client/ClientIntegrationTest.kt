package client

import Implementation
import ListToolsResult
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.net.Socket

class ClientIntegrationTest {

    fun createTransport(): StdioClientTransport {
        val socket = Socket("localhost", 3000)

        return StdioClientTransport(socket.inputStream, socket.outputStream)
    }

    @Test
    fun testRequestTools() = runTest {
        val client = Client(
            Implementation("test", "1.0"),
        )

        val transport = createTransport()
        try {
            client.connect(transport)

            val response: ListToolsResult? = client.listTools()
            println(response)

        } finally {
            transport.close()
        }
    }

}
