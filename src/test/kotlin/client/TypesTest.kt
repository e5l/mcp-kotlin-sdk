package client

import JSONRPCMessage
import org.junit.jupiter.api.Test
import shared.McpJson

class TypesTest {

    @Test
    fun testRequestResult() {
        val message = "{\"result\":{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{\"tools\":{\"listChanged\":true},\"resources\":{}},\"serverInfo\":{\"name\":\"jetbrains/proxy\",\"version\":\"0.1.0\"}},\"jsonrpc\":\"2.0\",\"id\":1}"
        McpJson.decodeFromString<JSONRPCMessage>(message)
    }
}