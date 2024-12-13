package client

import ClientCapabilities
import Implementation
import JSONRPCMessage
import PingRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.runBlocking
import shared.Transport
import kotlin.test.Test

class ClientTest {

    @Test
    fun `test ping functionality`() = runBlocking {
        val clientOptions = ClientOptions(
            capabilities = ClientCapabilities(
                experimental = null,
                sampling = null,
                roots = null
            )
        )
        val client = Client(
            clientInfo = Implementation(name = "TestClient", version = "1.0"),
            options = clientOptions
        )

        val transport = object : Transport {
            override var onClose: (() -> Unit)? = null
            override var onError: ((Throwable) -> Unit)? = null
            override var onMessage: (suspend (CoroutineScope.(JSONRPCMessage) -> Unit))? = null


            override suspend fun start() {
                // Simulated start implementation
            }

            override suspend fun send(message: JSONRPCMessage) {
                val callback = onMessage ?: return
                if (message is PingRequest) {
                    GlobalScope.callback(message)
                }
            }

            override suspend fun close() {
                onClose?.invoke()
            }
        }

        client.connect(transport)

        client.ping()
    }

}