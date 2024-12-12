package shared

import BaseNotificationParamsSchema
import JSONRPCNotification
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.nio.charset.StandardCharsets

class TestJSONRPCNotification(
    override val method: String,
    override val params: BaseNotificationParamsSchema? = null,
    override val additionalProperties: Map<String, Any?> = emptyMap()
) : JSONRPCNotification()

class ReadBufferTest {
    private val testMessage: JSONRPCNotification = TestJSONRPCNotification(
        method = "foobar"
    )
    
    private val json = Json { 
        ignoreUnknownKeys = true 
        encodeDefaults = true
    }

    @Test
    fun `should have no messages after initialization`() {
        val readBuffer = ReadBuffer()
        assertNull(readBuffer.readMessage())
    }

    @Test
    fun `should only yield a message after a newline`() {
        val readBuffer = ReadBuffer()

        // Append message without newline
        val messageBytes = json.encodeToString(testMessage)
            .toByteArray(StandardCharsets.UTF_8)
        readBuffer.append(messageBytes)
        assertNull(readBuffer.readMessage())

        // Append newline and verify message is now available
        readBuffer.append("\n".toByteArray(StandardCharsets.UTF_8))
        assertEquals(testMessage, readBuffer.readMessage())
        assertNull(readBuffer.readMessage())
    }

    @Test
    fun `should be reusable after clearing`() {
        val readBuffer = ReadBuffer()

        // Test clearing buffer
        readBuffer.append("foobar".toByteArray(StandardCharsets.UTF_8))
        readBuffer.clear()
        assertNull(readBuffer.readMessage())

        // Test reuse after clearing
        val messageBytes = json.encodeToString(testMessage)
            .toByteArray(StandardCharsets.UTF_8)
        readBuffer.append(messageBytes)
        readBuffer.append("\n".toByteArray(StandardCharsets.UTF_8))
        assertEquals(testMessage, readBuffer.readMessage())
    }
}