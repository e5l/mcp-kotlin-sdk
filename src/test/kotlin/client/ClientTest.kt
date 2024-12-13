package client

import ClientCapabilities
import Implementation
import InitializeRequest
import InitializeResult
import JSONRPCMessage
import JSONRPCResponse
import LATEST_PROTOCOL_VERSION
import ServerCapabilities
import io.mockk.coVerify
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import shared.Transport
import kotlin.test.Test
import kotlin.test.assertTrue

class ClientTest {
    @Test
    fun `should initialize with matching protocol version`() = runTest {
        var initialied = false
        var clientTransport = object : Transport {
            override suspend fun start() {}

            override suspend fun send(message: JSONRPCMessage) {
                if (message !is InitializeRequest) return
                initialied = true
                check(message.params.protocolVersion == LATEST_PROTOCOL_VERSION)

                val result = InitializeResult(
                    protocolVersion = LATEST_PROTOCOL_VERSION,
                    capabilities = ServerCapabilities(),
                    serverInfo = Implementation(
                        name = "test",
                        version = "1.0"
                    )
                )

                val response = JSONRPCResponse(
                    id = message.id,
                    result = result
                )
                onMessage?.invoke(response)
            }

            override suspend fun close() {
            }

            override var onClose: (() -> Unit)? = null
            override var onError: ((Throwable) -> Unit)? = null
            override var onMessage: (suspend (JSONRPCMessage) -> Unit)? = null
        }

        val client = Client(
            clientInfo = Implementation(
                name = "test client",
                version = "1.0"
            ),
            options = ClientOptions(
                capabilities = ClientCapabilities(
                    sampling = JsonObject(emptyMap())
                )
            )
        )

        client.connect(clientTransport)
        assertTrue(initialied)
    }
}
