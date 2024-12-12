package shared

import InitializedNotification
import Method
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets

class ReadBufferTest {
    private val testMessage = InitializedNotification()
    
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
        readBuffer.append(Method.Defined.NotificationsInitialized.value.toByteArray(StandardCharsets.UTF_8))
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