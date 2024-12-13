package client

import ClientCapabilities
import CreateMessageRequest
import CreateMessageResult
import Implementation
import InitializeRequest
import InitializeResult
import JSONRPCMessage
import JSONRPCResponse
import LATEST_PROTOCOL_VERSION
import ListRootsRequest
import SUPPORTED_PROTOCOL_VERSIONS
import ServerCapabilities
import TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import shared.Transport
import kotlin.test.Test
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
                if (message !is InitializeRequest) return
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
                if (message !is InitializeRequest) return
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
                if (message !is InitializeRequest) return
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
        TODO()
    }

    @Test
    fun `should respect client notification capabilities`() {
        TODO()
    }

    @Test
    fun `should respect server notification capabilities`() {
        TODO()
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
}

///*
//  Test that custom request/notification/result schemas can be used with the Client class.
//  */
//test("should typecheck", () => {
//    const GetWeatherRequestSchema = RequestSchema.extend({
//        method: z.literal("weather/get"),
//        params: z.object({
//        city: z.string(),
//    }),
//    });
//
//    const GetForecastRequestSchema = RequestSchema.extend({
//        method: z.literal("weather/forecast"),
//        params: z.object({
//        city: z.string(),
//        days: z.number(),
//    }),
//    });
//
//    const WeatherForecastNotificationSchema = NotificationSchema.extend({
//        method: z.literal("weather/alert"),
//        params: z.object({
//        severity: z.enum(["warning", "watch"]),
//        message: z.string(),
//    }),
//    });
//
//    const WeatherRequestSchema = GetWeatherRequestSchema.or(
//            GetForecastRequestSchema,
//    );
//    const WeatherNotificationSchema = WeatherForecastNotificationSchema;
//    const WeatherResultSchema = ResultSchema.extend({
//        temperature: z.number(),
//        conditions: z.string(),
//    });
//
//    type WeatherRequest = z.infer<typeof WeatherRequestSchema>;
//    type WeatherNotification = z.infer<typeof WeatherNotificationSchema>;
//    type WeatherResult = z.infer<typeof WeatherResultSchema>;
//
//    // Create a typed Client for weather data
//    const weatherClient = new Client<
//            WeatherRequest,
//            WeatherNotification,
//            WeatherResult
//            >(
//        {
//            name: "WeatherClient",
//            version: "1.0.0",
//        },
//        {
//            capabilities: {
//            sampling: {},
//        },
//        },
//    );
//
//    // Typecheck that only valid weather requests/notifications/results are allowed
//    false &&
//            weatherClient.request(
//                {
//                    method: "weather/get",
//                    params: {
//                    city: "Seattle",
//                },
//                },
//                WeatherResultSchema,
//            );
//
//    false &&
//            weatherClient.notification({
//                method: "weather/alert",
//                params: {
//                severity: "warning",
//                message: "Storm approaching",
//            },
//            });
//});
//
//test("should handle client cancelling a request", async () => {
//    const server = new Server(
//        {
//            name: "test server",
//            version: "1.0",
//        },
//        {
//            capabilities: {
//            resources: {},
//        },
//        },
//    );
//
//    // Set up server to delay responding to listResources
//    server.setRequestHandler(
//        ListResourcesRequestSchema,
//        async (request, extra) => {
//        await new Promise((resolve) => setTimeout(resolve, 1000));
//        return {
//            resources: [],
//        };
//    },
//    );
//
//    const [clientTransport, serverTransport] =
//        InMemoryTransport.createLinkedPair();
//
//    const client = new Client(
//        {
//            name: "test client",
//            version: "1.0",
//        },
//        {
//            capabilities: {},
//        },
//    );
//
//    await Promise.all([
//        client.connect(clientTransport),
//        server.connect(serverTransport),
//    ]);
//
//    // Set up abort controller
//    const controller = new AbortController();
//
//    // Issue request but cancel it immediately
//    const listResourcesPromise = client.listResources(undefined, {
//            signal: controller.signal,
//    });
//    controller.abort("Cancelled by test");
//
//    // Request should be rejected
//    await expect(listResourcesPromise).rejects.toBe("Cancelled by test");
//});
//
//test("should handle request timeout", async () => {
//    const server = new Server(
//        {
//            name: "test server",
//            version: "1.0",
//        },
//        {
//            capabilities: {
//            resources: {},
//        },
//        },
//    );
//
//    // Set up server with a delayed response
//    server.setRequestHandler(
//        ListResourcesRequestSchema,
//        async (_request, extra) => {
//        const timer = new Promise((resolve) => {
//            const timeout = setTimeout(resolve, 100);
//            extra.signal.addEventListener("abort", () => clearTimeout(timeout));
//        });
//
//        await timer;
//        return {
//            resources: [],
//        };
//    },
//    );
//
//    const [clientTransport, serverTransport] =
//        InMemoryTransport.createLinkedPair();
//
//    const client = new Client(
//        {
//            name: "test client",
//            version: "1.0",
//        },
//        {
//            capabilities: {},
//        },
//    );
//
//    await Promise.all([
//        client.connect(clientTransport),
//        server.connect(serverTransport),
//    ]);
//
//    // Request with 0 msec timeout should fail immediately
//    await expect(
//            client.listResources(undefined, { timeout: 0 }),
//    ).rejects.toMatchObject({
//            code: ErrorCode.RequestTimeout,
//    });
//});
