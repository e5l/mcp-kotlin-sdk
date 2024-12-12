import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import shared.Transport

/**
 * In-memory transport for creating clients and servers that talk to each other within the same process.
 */
class InMemoryTransport : Transport {
    private val scope = CoroutineScope(SupervisorJob())

    private var otherTransport: InMemoryTransport? = null
    private val messageQueue: MutableList<JSONRPCMessage> = mutableListOf()

    override var onclose: (() -> Unit)? = null
    override var onerror: ((Throwable) -> Unit)? = null
    override var onmessage: (CoroutineScope.(JSONRPCMessage) -> Unit)? = null

    /**
     * Creates a pair of linked in-memory transports that can communicate with each other.
     * One should be passed to a Client and one to a Server.
     */
    companion object {
        fun createLinkedPair(): Pair<InMemoryTransport, InMemoryTransport> {
            val clientTransport = InMemoryTransport()
            val serverTransport = InMemoryTransport()
            clientTransport.otherTransport = serverTransport
            serverTransport.otherTransport = clientTransport
            return Pair(clientTransport, serverTransport)
        }
    }

    override fun start(): Deferred<Unit> {
        // Process any messages that were queued before start was called
        while (messageQueue.isNotEmpty()) {
            messageQueue.removeFirstOrNull()?.let { message ->
                onmessage?.invoke(scope, message) // todo?
            }
        }
        return CompletableDeferred(Unit)
    }

    override fun close(): Deferred<Unit> {
        val deferred = CompletableDeferred<Unit>()
        try {
            val other = otherTransport
            otherTransport = null
            other?.close()
            onclose?.invoke()
        } catch (e: Throwable) {
            deferred.completeExceptionally(e)
        }
        return deferred
    }

    override fun send(message: JSONRPCMessage): Deferred<Unit> {
        val other = otherTransport ?: throw IllegalStateException("Not connected")

        if (other.onmessage != null) {
            other.onmessage?.invoke(scope, message) // todo?
        } else {
            other.messageQueue.add(message)
        }
        return CompletableDeferred()
    }
}