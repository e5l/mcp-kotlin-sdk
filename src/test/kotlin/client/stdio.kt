package client

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail


class StdioClientTransportTest {
    private val serverParameters = StdioServerParameters("/usr/bin/tee")

    @Test
    fun `should start then close cleanly`() = runTest {
        val client = StdioClientTransport(serverParameters)
        client.onError = { error ->
            fail("Unexpected error: $error")
        }

        var didClose = false
        client.onClose = { didClose = true }

        // Стартуем транспорт
        client.start().await()
        assertFalse(didClose, "Transport should not be closed immediately after start")

        // Закрываем транспорт
        client.close().await()
        assertTrue(didClose, "Transport should be closed after close() call")
    }
}
