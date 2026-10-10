package dev.meshmind.core.model

import java.util.UUID

/** Persistent node identity. Nearby endpoint IDs change every session; this does not. */
typealias NodeId = String

object Addresses {
    /** Deliver to every node (e.g. a broadcast SOS). */
    const val BROADCAST = "*"
    /** Deliver to whichever Internet gateway gets it first. */
    const val ANY_GATEWAY = "@gateway"
}

/** Append only: the ordinal is the wire value. */
enum class PacketType { TEXT, SOS, ACK, GATEWAY_BEACON, INET_REQUEST, INET_RESPONSE, SUMMARY, CHUNK, LIGHT_SHOW, BULLETIN }

/** Lower rank = sent first. */
enum class Priority(val rank: Int) { CRITICAL(0), HIGH(1), NORMAL(2), LOW(3) }

enum class PacketState { QUEUED, FORWARDING, DELIVERED, EXPIRED }

/**
 * A mesh packet. Routing is done entirely at the application layer:
 * - [ttl] counts remaining hops (decremented on every transmission),
 * - [expiresAt] bounds how long a stored copy stays useful,
 * - [routeHistory] lists every node that has held this copy, source first.
 */
data class Packet(
    val packetId: String,
    val sourceId: NodeId,
    val destinationId: NodeId,
    val type: PacketType,
    val priority: Priority,
    val createdAt: Long,
    val expiresAt: Long,
    val ttl: Int,
    val hopCount: Int,
    val payload: ByteArray,
    val routeHistory: List<NodeId>,
    /** Human-readable classification label from the PriorityClassifier (for UI/debug). */
    val label: String = "",
    /** Origin's signature over [dev.meshmind.core.security.signingBytes]; null only for link-local control packets. */
    val signature: ByteArray? = null,
    /** Origin's encoded certificate, so any relay can verify the packet without having met the origin. */
    val cert: ByteArray? = null,
) {
    val isBroadcast: Boolean get() = destinationId == Addresses.BROADCAST
    val isForAnyGateway: Boolean get() = destinationId == Addresses.ANY_GATEWAY
    fun isExpired(now: Long): Boolean = now >= expiresAt

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Packet) return false
        return packetId == other.packetId && sourceId == other.sourceId &&
            destinationId == other.destinationId && type == other.type &&
            priority == other.priority && createdAt == other.createdAt &&
            expiresAt == other.expiresAt && ttl == other.ttl && hopCount == other.hopCount &&
            payload.contentEquals(other.payload) && routeHistory == other.routeHistory &&
            label == other.label && signature.contentEquals(other.signature) && cert.contentEquals(other.cert)
    }

    override fun hashCode(): Int = packetId.hashCode() * 31 + hopCount

    override fun toString(): String =
        "Packet(${packetId.take(8)} $type $priority $sourceId->$destinationId ttl=$ttl hops=$hopCount " +
            "route=${routeHistory.joinToString(">")} ${payload.size}B)"

    companion object {
        const val DEFAULT_TTL = 8
        fun newId(): String = UUID.randomUUID().toString()
    }
}

/** A packet as held by a node's store-and-forward buffer. */
data class StoredPacket(
    val packet: Packet,
    val state: PacketState,
    val updatedAt: Long,
    /** True if this node created the packet. */
    val origin: Boolean,
) {
    val id: String get() = packet.packetId
}
