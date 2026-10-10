package dev.meshmind.core.routing

import dev.meshmind.core.gateway.GatewayQueueSnapshot
import dev.meshmind.core.model.Answer
import dev.meshmind.core.model.Bulletin
import dev.meshmind.core.model.InetRequest
import dev.meshmind.core.model.InetResponse
import dev.meshmind.core.model.NodeId
import dev.meshmind.core.model.PacketState
import dev.meshmind.core.model.PacketType
import dev.meshmind.core.model.Priority
import dev.meshmind.core.security.Role
import dev.meshmind.core.security.TrustLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RouterConfig(
    val defaultTtl: Int = 8,
    val beaconTtl: Int = 8,
    val messageLifetimeMs: Long = 24 * 3_600_000L,
    val sosLifetimeMs: Long = 24 * 3_600_000L,
    val inetLifetimeMs: Long = 10 * 60_000L,
    val chunkLifetimeMs: Long = 2 * 3_600_000L,
    /**
     * ACKs are never acknowledged themselves, so they would otherwise be replicated for their whole lifetime.
     * Keep it short; delivery knowledge also travels in every SUMMARY's acked list.
     */
    val ackLifetimeMs: Long = 30 * 60_000L,
    /**
     * ACKs older than this are housekeeping, not news: they are scheduled after fresh NORMAL traffic. On phones, a
     * restored backlog of thousands of old ACKs otherwise held a gateway beacon back for ~45 s.
     */
    val staleAckAfterMs: Long = 60_000L,
    val beaconLifetimeMs: Long = 45_000L,
    val gatewayEntryLifetimeMs: Long = 90_000L,
    /** Max IDs per list in a SUMMARY (2 x 500 x 16 B fits a 32 KB Nearby payload). */
    val summaryMaxIds: Int = 500,
    /** Application-layer chunk size for large transfers (Nearby BYTES max is 32 KB). */
    val chunkSize: Int = 16 * 1024,
    val sendRetryDelayMs: Long = 500,
    val maxConsecutiveFailures: Int = 3,
    /**
     * A link is only usable once the peer has our HELLO; its SUMMARY is the proof. Without one, HELLO is re-sent this
     * often, up to [handshakeRetries] times. On phones a HELLO sent before the peer's side of a Nearby connection was
     * accepted failed four times and was dropped, leaving a half-open link that silently discarded every packet.
     */
    val handshakeRetryMs: Long = 3_000,
    val handshakeRetries: Int = 20,
    /**
     * Max CHUNK frames per link handed to the transport but not yet LINK_ACKed by the neighbour. Small = a CRITICAL
     * packet waits behind at most this many chunks inside Nearby's own buffer. Int.MAX_VALUE disables flow control.
     */
    val bulkWindow: Int = 2,
    /** A window slot is freed after this long even without a LINK_ACK (peer on an older build, lost ack). */
    val bulkAckTimeoutMs: Long = 5_000,
    /** Minimum gap between beacons (handshake-triggered beacons included). */
    val minBeaconGapMs: Long = 3_000,
    /** A NextHopAdvisor call slower than this counts as slow; [advisorMaxSlowCalls] slow calls in a row disable it. */
    val advisorBudgetMs: Long = 5,
    val advisorMaxSlowCalls: Int = 3,
    /** A LinkExtension payload (SAGE summary) is re-sent on long-lived links this often. */
    val linkExtensionRefreshMs: Long = 5 * 60_000L,
    /**
     * Drop packets and HELLOs without a valid signature. Invalid signatures (tampering, spoofed IDs) are always
     * dropped; this additionally refuses unsigned ones.
     */
    val requireSignatures: Boolean = true,
    /**
     * A HELLO's signed timestamp may differ from our clock by at most this much (replay window). Wide because phone
     * wall clocks can be far apart offline; a replayed HELLO still cannot sign packets for that node.
     */
    val helloMaxSkewMs: Long = 24 * 3_600_000L,
    /** Upper bound on how long a bulletin is replicated. */
    val bulletinLifetimeMs: Long = 30 * 60_000L,
)

enum class LogCategory { PEER, RX, FORWARD, DUPLICATE, QUEUE, BEACON, GATEWAY, DELIVERY, EXPIRY, ERROR, INFO, SECURITY }

data class MeshLog(val time: Long, val category: LogCategory, val message: String, val packetId: String? = null)

class MeshLogger(
    private val clock: () -> Long,
    private val capacity: Int = 3000,
    private val echo: ((MeshLog) -> Unit)? = null,
) {
    private val buf = ArrayDeque<MeshLog>()
    private val _entries = MutableStateFlow<List<MeshLog>>(emptyList())
    val entries: StateFlow<List<MeshLog>> = _entries.asStateFlow()

    @Synchronized
    fun log(category: LogCategory, message: String, packetId: String? = null) {
        val e = MeshLog(clock(), category, message, packetId)
        buf.addLast(e)
        while (buf.size > capacity) buf.removeFirst()
        _entries.value = buf.toList()
        echo?.invoke(e)
    }

    @Synchronized
    fun clear() { buf.clear(); _entries.value = emptyList() }

    fun format(e: MeshLog): String = "${formatTime(e.time)} [${e.category}] ${e.message}"

    companion object {
        fun formatTime(t: Long): String = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(t))
    }
}

data class PeerInfo(
    val endpointId: String,
    val nodeId: NodeId?,
    val queued: Int,
    val bytesSent: Long,
    val framesSent: Int,
    val connectedAt: Long,
)

data class MeshStats(
    val framesReceived: Long = 0,
    val packetsForwarded: Long = 0,
    val packetsOriginated: Long = 0,
    val deliveredToMe: Long = 0,
    val acksReceived: Long = 0,
    val duplicatesRejected: Long = 0,
    val expired: Long = 0,
    val ttlExhausted: Long = 0,
    val bytesSent: Long = 0,
    val bytesReceived: Long = 0,
    val beaconsReceived: Long = 0,
    val inetExecuted: Long = 0,
    val sendFailures: Long = 0,
    /** Packets/HELLOs/ACKs refused by the trust layer (forged, tampered, spoofed or unsigned). */
    val rejectedUntrusted: Long = 0,
)

enum class TrackStatus { QUEUED, IN_TRANSIT, DELIVERED, EXPIRED }

/** A packet this node originated, followed until it is acknowledged. */
data class TrackedPacket(
    val packetId: String,
    val type: PacketType,
    val priority: Priority,
    val label: String,
    val reasons: List<String>,
    val destination: NodeId,
    val createdAt: Long,
    val preview: String,
    val firstHops: List<NodeId> = emptyList(),
    val status: TrackStatus = TrackStatus.QUEUED,
    val deliveredRoute: List<NodeId> = emptyList(),
    val hopCount: Int = 0,
    val rttMs: Long? = null,
    val oneWayMs: Long? = null,
    val confirmedBy: List<NodeId> = emptyList(),
)

data class InboxMessage(
    val packetId: String,
    val type: PacketType,
    val from: NodeId,
    val to: NodeId,
    val text: String,
    val priority: Priority,
    val label: String,
    val route: List<NodeId>,
    val hopCount: Int,
    val latencyMs: Long,
    val receivedAt: Long,
    val trust: TrustLevel = TrustLevel.UNSIGNED,
)

data class InetResult(
    val requestId: String,
    val request: InetRequest,
    val sentAt: Long,
    val priority: Priority,
    val reachedGatewayAt: Long? = null,
    val response: InetResponse? = null,
    val responseRoute: List<NodeId> = emptyList(),
    val completedAt: Long? = null,
    /** Decoded answer for ASK/READ requests. */
    val answer: Answer? = null,
    /** True only if a trusted MeshMind authority signed [answer]. */
    val answerVerified: Boolean = false,
    val servedFrom: String = "",
)

data class TransferProgress(
    val transferId: String,
    val name: String,
    val peer: NodeId,
    val outgoing: Boolean,
    val total: Int,
    val done: Int,
    val totalBytes: Int,
    val startedAt: Long,
    val completedAt: Long? = null,
)

/** One latency/hop measurement, for exporting numbers rather than screenshots. */
data class DeliveryMetric(
    val packetId: String,
    val type: PacketType,
    val priority: Priority,
    val sourceId: NodeId,
    val destinationId: NodeId,
    val hops: Int,
    val route: List<NodeId>,
    /** Destination clock minus source clock: only meaningful if the phones' clocks agree. */
    val oneWayMs: Long,
    /** Source-measured send-to-ACK time (single clock, reliable). Null when measured at the destination. */
    val rttMs: Long?,
    val measuredAt: Long,
    /** "destination" or "source". */
    val side: String,
)

data class QueuedInfo(
    val packetId: String,
    val type: PacketType,
    val priority: Priority,
    val sourceId: NodeId,
    val destinationId: NodeId,
    val state: PacketState,
    val ttl: Int,
    val hops: Int,
    val ageMs: Long,
)

/** This node's identity as shown in the UI. */
data class IdentityView(
    val nodeId: NodeId,
    val fingerprint: String,
    val level: TrustLevel,
    val role: Role,
    val issuer: String,
    val expiresAt: Long,
    /** Fingerprints of the authorities this phone trusts (empty = never enrolled). */
    val anchors: List<String>,
)

data class BulletinView(
    val bulletin: Bulletin,
    /** Signed by the MeshMind authority (as opposed to a verified organizer's own board). */
    val official: Boolean,
    val signerRole: Role,
    val from: NodeId,
    val receivedAt: Long,
    val hops: Int,
)

data class MeshSnapshot(
    val selfId: NodeId,
    val peers: List<PeerInfo> = emptyList(),
    val gateways: List<GatewayEntry> = emptyList(),
    val isGateway: Boolean = false,
    val stats: MeshStats = MeshStats(),
    val tracked: List<TrackedPacket> = emptyList(),
    val inbox: List<InboxMessage> = emptyList(),
    val inet: List<InetResult> = emptyList(),
    val transfers: List<TransferProgress> = emptyList(),
    val knownNodes: List<NodeId> = emptyList(),
    val edges: List<Pair<NodeId, NodeId>> = emptyList(),
    val queued: List<QueuedInfo> = emptyList(),
    val metrics: List<DeliveryMetric> = emptyList(),
    /** Store-and-forward load: packets still to be replicated (all, not just the [queued] preview) and their bytes. */
    val pendingCount: Int = 0,
    val pendingBytes: Long = 0,
    val identity: IdentityView? = null,
    val bulletins: List<BulletinView> = emptyList(),
    /** Present while this node is an active gateway. */
    val gatewayQueue: GatewayQueueSnapshot? = null,
    val answerCacheSize: Int = 0,
    /** Questions this phone answered for others from its own cache. */
    val relayCacheAnswers: Long = 0,
) {
    val connectedPeers: List<NodeId> get() = peers.mapNotNull { it.nodeId }
    val nearestGateway: GatewayEntry? get() = gateways.minByOrNull { it.hopDistance }
}
