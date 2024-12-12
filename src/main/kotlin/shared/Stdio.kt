package shared

import JSONRPCMessage
import io.ktor.utils.io.core.*
import kotlinx.io.Buffer
import kotlinx.io.readLine
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Buffers a continuous stdio stream into discrete JSON-RPC messages.
 */
class ReadBuffer {
    private val buffer: Buffer = Buffer()

    fun append(chunk: ByteArray) {
        buffer.writeFully(chunk)
    }

    fun readMessage(): JSONRPCMessage? {
        if (buffer.exhausted()) return null
        val line = buffer.readLine() ?: return null
        return deserializeMessage(line)
    }

    fun clear() {
        buffer.clear()
    }
}

fun deserializeMessage(line: String): JSONRPCMessage {
    return Json.decodeFromString(line)
}

fun serializeMessage(message: JSONRPCMessage): String {
    return Json.encodeToString(message) + "\n"
}

