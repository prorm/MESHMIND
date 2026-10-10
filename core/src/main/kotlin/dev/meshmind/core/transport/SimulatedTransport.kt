package dev.meshmind.core.transport

import dev.meshmind.core.model.NodeId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * In-process radio model: explicit undirected edges, per-frame latency + bandwidth delay, and fresh endpoint IDs
 * for every connection session (like Nearby), so the HELLO handshake is genuinely needed.
 * A frame in flight when its edge is cut is lost (send returns false).
 *
 * [earlySuccessScope] != null models what Nearby does on real phones: send() reports success as soon as the frame
 * is buffered, while the bytes drain through a FIFO "radio" per direction. (Measured on a OnePlus 12R and a Galaxy
 * S24: SUCCESS arrives 1.3-2.9 s before the peer receives the chunk.)
 */
class SimulatedNetwork(
    var latencyMs: Long = 10,
    var bytesPerSecond: Long = 160_000,
    private val earlySuccessScope: CoroutineScope? = null,
) {
    private class Session(val a: NodeId, val b: NodeId, val epAtA: String, val epAtB: String) {
        fun endpointAt(node: NodeId) = if (node == a) epAtA else epAtB
    }

    private val transports = LinkedHashMap<NodeId, SimulatedTransport>()
    private val sessions = HashMap<Pair<NodeId, NodeId>, Session>()
    /** node -> (endpointId as seen by node -> remote node) */
    private val endpoints = HashMap<NodeId, MutableMap<String, NodeId>>()
    private var counter = 0
    private val lanes = HashMap<Pair<NodeId, NodeId>, Channel<Pair<Session, ByteArray>>>()

    var framesDelivered = 0L; private set
    var bytesDelivered = 0L; private set
    var framesLost = 0L; private set
    /** Optional tap for tests/visualisation: (from, to, bytes). */
    var onFrame: ((NodeId, NodeId, ByteArray) -> Unit)? = null
    /** Test hook: send() fails for matching frames, like Nearby before the peer's side of a connection is accepted. */
    var refuseSend: ((NodeId, NodeId, ByteArray) -> Boolean)? = null
    /** Test hook: send() succeeds but the frame never arrives. */
    var loseFrame: ((NodeId, NodeId, ByteArray) -> Boolean)? = null
    /** Test hook: a malicious radio path that rewrites frames in flight (returns the bytes to deliver). */
    var tamper: ((NodeId, NodeId, ByteArray) -> ByteArray)? = null

    private fun key(a: NodeId, b: NodeId) = if (a < b) a to b else b to a

    @Synchronized
    fun transport(node: NodeId): SimulatedTransport = transports.getOrPut(node) { SimulatedTransport(node, this) }

    @Synchronized
    fun isConnected(a: NodeId, b: NodeId) = sessions.containsKey(key(a, b))

    @Synchronized
    fun edges(): List<Pair<NodeId, NodeId>> = sessions.keys.toList()

    /** The endpoint ID under which [node] currently sees [peer] (lets tests inject raw frames). */
    @Synchronized
    fun endpointOf(node: NodeId, peer: NodeId): String? = sessions[key(node, peer)]?.endpointAt(node)

    suspend fun connect(a: NodeId, b: NodeId) {
        val s: Session
        synchronized(this) {
            if (sessions.containsKey(key(a, b))) return
            s = Session(a, b, "ep${++counter}", "ep${++counter}")
            sessions[key(a, b)] = s
            endpoints.getOrPut(a) { HashMap() }[s.epAtA] = b
            endpoints.getOrPut(b) { HashMap() }[s.epAtB] = a
        }
        transport(a).listener?.onLinkUp(s.epAtA)
        transport(b).listener?.onLinkUp(s.epAtB)
    }

    suspend fun disconnect(a: NodeId, b: NodeId) {
        val s: Session
        synchronized(this) {
            s = sessions.remove(key(a, b)) ?: return
            endpoints[a]?.remove(s.epAtA)
            endpoints[b]?.remove(s.epAtB)
        }
        transport(a).listener?.onLinkDown(s.epAtA)
        transport(b).listener?.onLinkDown(s.epAtB)
    }

    internal suspend fun deliver(from: NodeId, endpointId: String, original: ByteArray): Boolean {
        val (to, session) = synchronized(this) {
            val to = endpoints[from]?.get(endpointId) ?: return false
            to to (sessions[key(from, to)] ?: return false)
        }
        val bytes = tamper?.invoke(from, to, original) ?: original
        if (refuseSend?.invoke(from, to, bytes) == true) return false
        if (loseFrame?.invoke(from, to, bytes) == true) { synchronized(this) { framesLost++ }; return true }
        val bufferScope = earlySuccessScope
        if (bufferScope != null) {
            val lane = synchronized(this) {
                lanes.getOrPut(from to to) {
                    Channel<Pair<Session, ByteArray>>(Channel.UNLIMITED).also { ch ->
                        bufferScope.launch { for ((s, b) in ch) transmit(from, to, s, b) }
                    }
                }
            }
            delay(1)
            lane.trySend(session to bytes)
            return true
        }
        return transmit(from, to, session, bytes)
    }

    private suspend fun transmit(from: NodeId, to: NodeId, session: Session, bytes: ByteArray): Boolean {
        delay(latencyMs + bytes.size * 1000L / bytesPerSecond)
        synchronized(this) {
            if (sessions[key(from, to)] !== session) { framesLost++; return false }
            framesDelivered++
            bytesDelivered += bytes.size
        }
        onFrame?.invoke(from, to, bytes)
        transport(to).listener?.onReceived(session.endpointAt(to), bytes)
        return true
    }
}

class SimulatedTransport internal constructor(
    val nodeId: NodeId,
    private val network: SimulatedNetwork,
) : MeshTransport {
    internal var listener: TransportListener? = null
    override val maxFrameBytes: Int = 32 * 1024

    override fun setListener(listener: TransportListener) { this.listener = listener }
    override suspend fun start() = Unit
    override suspend fun stop() = Unit
    override suspend fun send(endpointId: String, bytes: ByteArray): Boolean = network.deliver(nodeId, endpointId, bytes)
}
