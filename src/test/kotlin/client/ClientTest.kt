package client

import ClientCapabilities
import CreateMessageRequest
import CreateMessageResult
import EmptyJsonObject
import Implementation
import InitializeResult
import JSONRPCMessage
import JSONRPCRequest
import JSONRPCResponse
import LATEST_PROTOCOL_VERSION
import ListResourcesResult
import ListRootsRequest
import ListToolsResult
import LoggingLevel
import Method
import Role
import SUPPORTED_PROTOCOL_VERSIONS
import ServerCapabilities
import TextContent
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import server.Server
import server.ServerOptions
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
                    sampling = EmptyJsonObject
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
                    sampling = EmptyJsonObject
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

    @Disabled("Client can't connect due to issue with initialize request")
    @Test
    fun `should respect server capabilities`() = runTest {
        val serverOptions = ServerOptions(
            capabilities = ServerCapabilities(
                resources = ServerCapabilities.Resources(null, null),
                tools = ServerCapabilities.Tools(null)
            )
        )
        val server = Server(
            Implementation(name = "test server", version = "1.0"),
            serverOptions
        )

        server.setRequestHandler<InitializeResult>(Method.Defined.Initialize) { request, _ ->
            InitializeResult(
                protocolVersion = LATEST_PROTOCOL_VERSION,
                capabilities = ServerCapabilities(
                    resources = ServerCapabilities.Resources(null, null),
                    tools = ServerCapabilities.Tools(null)
                ),
                serverInfo = Implementation(name = "test", version = "1.0")
            )
        }

        server.setRequestHandler<ListResourcesResult>(Method.Defined.ResourcesList) { request, _ ->
            ListResourcesResult(resources = emptyArray(), nextCursor = null)
        }

        server.setRequestHandler<ListToolsResult>(Method.Defined.ToolsList) { request, _ ->
            ListToolsResult(tools = emptyArray(), nextCursor = null)
        }

        val (clientTransport, serverTransport) = InMemoryTransport.createLinkedPair()

        val client = Client(
            clientInfo = Implementation(name = "test client", version = "1.0"),
            options = ClientOptions(
                capabilities = ClientCapabilities(sampling = EmptyJsonObject),
//                enforceStrictCapabilities = true // TODO()
            )
        )

        listOf(
            launch {
                client.connect(clientTransport)
                println("Client connected")
            },
            launch {
                server.connect(serverTransport)
                println("Server connected")
            }
        ).joinAll()

        // Server supports resources and tools, but not prompts
        val caps = client.getServerCapabilities()
        assertEquals(ServerCapabilities.Resources(null, null), caps?.resources)
        assertEquals(ServerCapabilities.Tools(null), caps?.tools)
        assertTrue(caps?.prompts == null) // or check that prompts are absent

        // These should not throw
        client.listResources()
        client.listTools()

        // This should fail because prompts are not supported
        val ex = assertFailsWith<IllegalStateException> {
            client.listPrompts()
        }
        assertTrue(ex.message?.contains("Server does not support prompts") == true)
    }

    @Test
    fun `should respect client notification capabilities`() = runTest {
        TODO("Client can't connect due to issue with initialize request")
        val server = Server(
            Implementation(name = "test server", version = "1.0"),
            ServerOptions(capabilities = ServerCapabilities())
        )

        val client = Client(
            clientInfo = Implementation(name = "test client", version = "1.0"),
            options = ClientOptions(
                capabilities = ClientCapabilities(
                    roots = ClientCapabilities.Roots(listChanged = true)
                )
            )
        )

        val (clientTransport, serverTransport) = InMemoryTransport.createLinkedPair()

        listOf(
            launch { client.connect(clientTransport) },
            launch { server.connect(serverTransport) }
        ).joinAll()

        // This should not throw because the client supports roots.listChanged
        client.sendRootsListChanged()

        // Create a new client without the roots.listChanged capability
        val clientWithoutCapability = Client(
            clientInfo = Implementation(name = "test client without capability", version = "1.0"),
            options = ClientOptions(
                capabilities = ClientCapabilities(),
                //                enforceStrictCapabilities = true // TODO()
            )
        )

        clientWithoutCapability.connect(clientTransport)
        // Using the same transport pair might not be realistic - in a real scenario you'd create another pair.
        // Adjust if necessary.

        // This should fail
        val ex = assertFailsWith<IllegalStateException> {
            clientWithoutCapability.sendRootsListChanged()
        }
        assertTrue(ex.message?.startsWith("Client does not support") == true)
    }

    @Test
    fun `should respect server notification capabilities`() = runTest {
        TODO("Client can't connect due to issue with initialize request")
        val server = Server(
            Implementation(name = "test server", version = "1.0"),
            ServerOptions(
                capabilities = ServerCapabilities(
                    logging = EmptyJsonObject,
                    resources = ServerCapabilities.Resources(listChanged = true, subscribe = null)
                )
            )
        )

        val client = Client(
            clientInfo = Implementation(name = "test client", version = "1.0"),
            options = ClientOptions(
                capabilities = ClientCapabilities()
            )
        )

        val (clientTransport, serverTransport) = InMemoryTransport.createLinkedPair()

        listOf(
            launch { client.connect(clientTransport) },
            launch { server.connect(serverTransport) }
        ).joinAll()

        // These should not throw
        val jsonObject = buildJsonObject {
            put("name", "John")
            put("age", 30)
            put("isStudent", false)
        }
        server.sendLoggingMessage(
            LoggingMessageNotification.Params(
                level = LoggingLevel.info,
                data = jsonObject
            )
        )
        server.sendResourceListChanged()

        // This should fail because the server doesn't have the tools capability
        val ex = assertFailsWith<IllegalStateException> {
            server.sendToolListChanged()
        }
        assertTrue(ex.message?.contains("Server does not support notifying of tool list changes") == true)
    }

//    @Test
//    fun `should handle client cancelling a request`() = runTest {
//        val server = Server(
//            Implementation(name = "test server", version = "1.0"),
//            ServerOptions(
//                capabilities = ServerCapabilities(resources = EmptyJsonObject)
//            )
//        )
//
//        server.setRequestHandler(ListResourcesRequestSchema) { _, extra ->
//            // Simulate delay
//            kotlinx.coroutines.delay(1000)
//            ListResourcesResult(resources = emptyList())
//        }
//
//        val (clientTransport, serverTransport) = InMemoryTransport.createLinkedPair()
//
//        val client = Client(
//            clientInfo = Implementation(name = "test client", version = "1.0"),
//            options = ClientOptions(capabilities = ClientCapabilities())
//        )
//
//        client.connect(clientTransport)
//        server.connect(serverTransport)
//
//        val job = kotlinx.coroutines.launch {
//            try {
//                client.listResources(signal = kotlinx.coroutines.CoroutineScope(coroutineContext).coroutineContext.job.apply {
//                    // Simulate something like AbortSignal by cancelling immediately
//                    cancel(CancellationException("Cancelled by test"))
//                })
//                fail("Expected cancellation")
//            } catch (e: CancellationException) {
//                assertEquals("Cancelled by test", e.message)
//            }
//        }
//        job.join()
//    }

//    @Test
//    fun `should handle request timeout`() = runTest {
//        val server = Server(
//            Implementation(name = "test server", version = "1.0"),
//            ServerOptions(
//                capabilities = ServerCapabilities(resources = EmptyJsonObject)
//            )
//        )
//
//        server.setRequestHandler(ListResourcesRequestSchema) { _, extra ->
//            // Simulate a delayed response
//            val signal = extra.signal
//            // Wait ~100ms unless cancelled
//            try {
//                withTimeout(100) {
//                    // Just delay here, if timeout is 0 on client side this won't return in time
//                    kotlinx.coroutines.delay(100)
//                }
//            } catch (e: Exception) {
//                // If aborted, just rethrow or return early
//            }
//            ListResourcesResult(resources = emptyList())
//        }
//
//        val (clientTransport, serverTransport) = InMemoryTransport.createLinkedPair()
//        val client = Client(
//            clientInfo = Implementation(name = "test client", version = "1.0"),
//            options = ClientOptions(capabilities = ClientCapabilities())
//        )
//
//        client.connect(clientTransport)
//        server.connect(serverTransport)
//
//        // Request with 0 msec timeout should fail immediately
//        val ex = assertFailsWith<ServerException> {
//            client.listResources(timeout = 0)
//        }
//        assertTrue(ex.code == ErrorCode.RequestTimeout)
//    }

    @Test
    fun `should handle client cancelling a request`() = runTest {
        TODO("Server")
    }

    @Test
    fun `should handle request timeout`() {
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
                    sampling = EmptyJsonObject
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


}
