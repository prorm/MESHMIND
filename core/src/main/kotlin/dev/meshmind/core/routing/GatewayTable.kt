package dev.meshmind.core.routing

import dev.meshmind.core.model.NodeId

data class GatewayEntry(
    val gatewayId: NodeId,
    val hopDistance: Int,
    /** Neighbour the best beacon arrived through (next hop toward the gateway). */
    val viaPeer: NodeId,
    val lastSeen: Long,
    val expiresAt: Long,
    /** The gateway's beacon was signed under an authority-issued (phone-verified) certificate. */
    val verified: Boolean = false,
    /** Fraction of the gateway's fair-share queue in use, from its last beacon. */
    val load: Double = 0.0,
)

/** Distance-vector-lite table of Internet gateways learned from flooded GATEWAY_BEACONs. */
class GatewayTable(private val entryLifetimeMs: Long = 90_000) {
    private val entries = LinkedHashMap<NodeId, GatewayEntry>()

    /**
     * Records a beacon heard via [viaPeer] at [hopDistance]. Accepts it if it is new, shorter, a refresh of the
     * current route, or the current entry has expired. Returns true if the table changed in a way that matters
     * for routing (new gateway or different next hop/distance).
     */
    @Synchronized
    fun update(gatewayId: NodeId, hopDistance: Int, viaPeer: NodeId, now: Long, verified: Boolean = false, load: Double = 0.0): Boolean {
        val cur = entries[gatewayId]
        val fresh = GatewayEntry(gatewayId, hopDistance, viaPeer, now, now + entryLifetimeMs, verified, load)
        return when {
            cur == null || cur.expiresAt <= now -> { entries[gatewayId] = fresh; true }
            hopDistance < cur.hopDistance -> { entries[gatewayId] = fresh; true }
            viaPeer == cur.viaPeer -> { entries[gatewayId] = fresh; hopDistance != cur.hopDistance }
            else -> { entries[gatewayId] = cur.copy(load = load, verified = verified || cur.verified); false }
        }
    }

    /** A neighbour went away: routes through it are no longer usable. */
    @Synchronized
    fun removeVia(peer: NodeId): List<NodeId> {
        val gone = entries.values.filter { it.viaPeer == peer }.map { it.gatewayId }
        gone.forEach { entries.remove(it) }
        return gone
    }

    @Synchronized
    fun prune(now: Long): List<NodeId> {
        val gone = entries.values.filter { it.expiresAt <= now }.map { it.gatewayId }
        gone.forEach { entries.remove(it) }
        return gone
    }

    @Synchronized
    fun nearest(now: Long): GatewayEntry? =
        entries.values.filter { it.expiresAt > now }.minWithOrNull(compareBy({ it.hopDistance }, { -it.lastSeen }))

    /**
     * Where to send an Internet request: verified gateways first, then by distance plus load (a full queue
     * counts like three extra hops), so a crowd spreads over several gateways instead of piling onto one.
     */
    @Synchronized
    fun best(now: Long): GatewayEntry? =
        entries.values.filter { it.expiresAt > now }
            .minWithOrNull(compareBy<GatewayEntry>({ if (it.verified) 0 else 1 }, { it.hopDistance + 3 * it.load }, { -it.lastSeen }))

    @Synchronized
    fun all(now: Long): List<GatewayEntry> = entries.values.filter { it.expiresAt > now }.sortedBy { it.hopDistance }
}
