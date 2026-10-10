package dev.meshmind.core.model

/** Delivery acknowledgement. Seeing one tells every relay to stop replicating [ackedPacketId]. */
data class AckPayload(
    val ackedPacketId: String,
    val ackedType: PacketType,
    /** Destination clock when it delivered the packet (one-way latency; subject to clock skew). */
    val deliveredAt: Long,
    val hopCount: Int,
    /** Route the acked packet took, as seen by the destination. */
    val route: List<NodeId>,
    /**
     * For broadcast packets each recipient acks individually; such acks must NOT stop replication,
     * because other nodes still need the broadcast.
     */
    val broadcastAck: Boolean,
)

/** Flooded by a node that is willing and able to relay Internet requests. Distance = packet hopCount. */
data class BeaconPayload(
    val gatewayId: NodeId,
    val seq: Long,
    val note: String,
    /** Requests waiting in the gateway's fair-share queue, and how many it will hold: requesters avoid busy gateways. */
    val queueLength: Int = 0,
    val queueCapacity: Int = 0,
) {
    val load: Double get() = if (queueCapacity <= 0) 0.0 else (queueLength.toDouble() / queueCapacity).coerceIn(0.0, 1.0)
}

/** Append only: the ordinal is the wire value. */
enum class InetKind {
    /** Raw allowlisted GET (debug tool). */
    HTTP_GET,
    /** Message to the gateway's own webhook (SOS relay). */
    WEBHOOK_POST,
    /** A question: answered by the backend's search + AI, signed by the MeshMind authority. [InetRequest.body] = query. */
    ASK,
    /** A page as plain readable text, extracted and signed by the backend. [InetRequest.url] = page. */
    READ,
}

/**
 * Deliberately tiny request model: no headers, no methods other than GET / fixed-target POST, no binary.
 * For WEBHOOK_POST the requester cannot choose the URL - the gateway posts [body] to its own configured webhook.
 */
data class InetRequest(
    val kind: InetKind,
    val url: String,
    val body: String,
    /** Battery percentage reported by the requester (-1 = unknown). A small, self-reported need signal. */
    val battery: Int = -1,
)

/** Append only: the ordinal is the wire value. */
enum class InetStatus { OK, REJECTED, ERROR, DEFERRED }

data class InetResponse(
    val requestId: String,
    val status: InetStatus,
    val httpCode: Int,
    val contentType: String,
    val body: ByteArray,
    val truncated: Boolean,
    val message: String,
    val gatewayId: NodeId,
    /** Route the request took to reach the gateway, so the requester can show the full round trip. */
    val requestRoute: List<NodeId>,
    val executedAt: Long,
    /** "upstream", "gateway-cache" or "relay-cache": where the answer actually came from. */
    val servedFrom: String = SERVED_UPSTREAM,
    /** Time spent in the gateway's fair-share queue. */
    val queueWaitMs: Long = 0,
    /** Need score the gateway assigned (0-100) and why, so the requester can see how it was prioritised. */
    val need: Int = -1,
    val needCategory: String = "",
    val needReasons: List<String> = emptyList(),
) {
    companion object {
        const val SERVED_UPSTREAM = "upstream"
        const val SERVED_GATEWAY_CACHE = "gateway-cache"
        const val SERVED_RELAY_CACHE = "relay-cache"
        const val SERVED_LOCAL_CACHE = "this-phone-cache"
        /** [contentType] of a body that is an encoded, authority-signed [Answer]. */
        const val ANSWER_CONTENT_TYPE = "application/x-meshmind-answer"
    }

    val bodyText: String get() = body.toString(Charsets.UTF_8)
}

/** Link-local (never forwarded) store-and-forward digest exchanged on every new connection. */
data class SummaryPayload(
    /** Packet IDs this node holds and is still replicating. */
    val held: List<String>,
    /** Packet IDs known to be delivered (stop replicating these). */
    val acked: List<String>,
    /** Packet IDs this node is missing and requests from the peer. */
    val want: List<String>,
)

/** One application-layer chunk of a large transfer (sent as its own packet so higher priorities can interleave). */
data class ChunkPayload(
    val transferId: String,
    val index: Int,
    val total: Int,
    val totalBytes: Int,
    val name: String,
    val data: ByteArray,
)
