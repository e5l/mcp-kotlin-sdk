package org.jetbrains.kotlinx.mcp.shared

import org.jetbrains.kotlinx.mcp.JSONRPCMessage

/**
 * Describes the minimal contract for a MCP transport that a client or server can communicate over.
 */
interface Transport {
    /**
     * Starts processing messages on the transport, including any connection steps that might need to be taken.
     *
     * This method should only be called after callbacks are installed, or else messages may be lost.
     *
     * NOTE: This method should not be called explicitly when using Client, Server, or Protocol classes,
     * as they will implicitly call start().
     */
    suspend fun start()

    /**
     * Sends a JSON-RPC message (request or response).
     */
    suspend fun send(message: JSONRPCMessage)

    /**
     * Closes the connection.
     */
    suspend fun close()

    /**
     * Callback for when the connection is closed for any reason.
     *
     * This should be invoked when close() is called as well.
     */
    var onClose: (() -> Unit)?

    /**
     * Callback for when an error occurs.
     *
     * Note that errors are not necessarily fatal; they are used for reporting any kind of
     * exceptional condition out of band.
     */
    var onError: ((Throwable) -> Unit)?

    /**
     * Callback for when a message (request or response) is received over the connection.
     */
    var onMessage: (suspend ((JSONRPCMessage) -> Unit))?
}
