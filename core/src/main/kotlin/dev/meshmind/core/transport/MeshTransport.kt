package dev.meshmind.core.transport

/**
 * Callbacks from a transport into the routing layer. Implementations must deliver calls for one endpoint
 * in order (a single consumer), since routing relies on HELLO arriving before data.
 */
interface TransportListener {
    suspend fun onLinkUp(endpointId: String)
    suspend fun onLinkDown(endpointId: String)
    suspend fun onReceived(endpointId: String, bytes: ByteArray)
}

/**
 * One-hop byte pipe to directly connected neighbours. It knows nothing about routing, NodeIDs or priorities.
 * Endpoint IDs are transport-session identifiers (they change between sessions); the router maps them to
 * persistent NodeIDs via the HELLO handshake.
 */
interface MeshTransport {
    /** Largest single frame the transport accepts (Nearby BYTES payloads: 32 KB). */
    val maxFrameBytes: Int

    fun setListener(listener: TransportListener)
    suspend fun start()
    suspend fun stop()

    /**
     * Sends one frame and suspends until the transport reports it fully transferred (true) or failed (false).
     * The router never has more than one frame in flight per link, which is what makes priority preemption work.
     */
    suspend fun send(endpointId: String, bytes: ByteArray): Boolean
}
