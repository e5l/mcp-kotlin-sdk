package client

import kotlinx.io.asSink
import kotlinx.io.asSource
import java.net.Socket

class ClientIntegrationTest {

    fun createTransport() {
        val socket = Socket("localhost", 3000)

        val transport = StdioClientTransport(socket.inputStream, socket.outputStream)
    }

}