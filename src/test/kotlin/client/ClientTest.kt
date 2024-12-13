package client

import ClientCapabilities
import CreateMessageRequest
import CreateMessageResult
import Implementation
import InitializeResult
import JSONRPCMessage
import JSONRPCRequest
import JSONRPCResponse
import LATEST_PROTOCOL_VERSION
import ListRootsRequest
import Method
import Role
import SUPPORTED_PROTOCOL_VERSIONS
import ServerCapabilities
import TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test
import shared.Transport
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ClientTest {
    @Test
    fun `should initialize with matching protocol version`() = runTest {
        var initialied = false
        var clientTransport = object : Transport {
            override suspend fun start() {}

            override suspend fun send(message: JSONRPCMessage) {
                if (message !is JSONRPCRequest) return
                initialied = true
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

    @Test
    fun `should initialize with supported older protocol version`() = runTest {
        val OLD_VERSION = SUPPORTED_PROTOCOL_VERSIONS[1]
        var clientTransport = object : Transport {
            override suspend fun start() {}

            override suspend fun send(message: JSONRPCMessage) {
                if (message !is JSONRPCRequest) return
                check(message.method == Method.Defined.Initialize.value)

                val result = InitializeResult(
                    protocolVersion = OLD_VERSION,
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
        assertEquals(
            Implementation("test", "1.0"),
            client.getServerVersion()
        )
    }

    @Test
    fun `should reject unsupported protocol version`() = runTest {
        var closed = false
        val clientTransport = object : Transport {
            override suspend fun start() {}

            override suspend fun send(message: JSONRPCMessage) {
                if (message !is JSONRPCRequest) return
                check(message.method == Method.Defined.Initialize.value)

                val result = InitializeResult(
                    protocolVersion = "invalid-version",
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
                closed = true
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
            options = ClientOptions()
        )

        assertFailsWith<IllegalStateException>("Server's protocol version is not supported: invalid-version") {
            client.connect(clientTransport)
        }

        assertTrue(closed)
    }

    @Test
    fun `should respect server capabilities`() = runTest {
        TODO("Server")
    }

    @Test
    fun `should respect client notification capabilities`() {
        TODO("Server")
    }

    @Test
    fun `should respect server notification capabilities`() {
        TODO("Server")
    }

    @Test
    fun `should only allow setRequestHandler for declared capabilities`() = runTest {
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

        client.setRequestHandler<CreateMessageRequest>(Method.Defined.SamplingCreateMessage) { request, _ ->
            CreateMessageResult(
                model = "test-model",
                role = Role.assistant,
                content = TextContent(
                    text = "Test response"
                )
            )
        }

        assertFailsWith<IllegalStateException>("Client does not support roots capability (required for RootsList)") {
            client.setRequestHandler<ListRootsRequest>(Method.Defined.RootsList) { _, _ -> null }
        }
    }

    @Test
    fun `should handle client cancelling a request`() = runTest {
        TODO("Server")
    }

    @Test
    fun `should handle request timeout`() {
        TODO("Server")
    }
}
