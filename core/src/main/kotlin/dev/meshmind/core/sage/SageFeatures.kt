package dev.meshmind.core.sage

import dev.meshmind.core.model.NodeId
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.ln1p
import kotlin.math.max
import kotlin.math.min

/** The packet-side inputs of one routing decision (times in seconds). */
data class PacketInfo(
    /** Research priority classes: 1 = control (HIGH), 2 = text (NORMAL), 3 = media/bulk (LOW, CHUNK). */
    val priorityClass: Int,
    val gatewayDest: Boolean,
    val sizeBytes: Int,
    val ageSec: Double,
    val lifetimeSec: Double,
    val replicas: Int,
    val hopsLeft: Int,
) {
    val remainingSec: Double get() = lifetimeSec - ageSec
}

/** Local resource state of a node at decision time. */
data class NodeLoad(val queueFrac: Double, val queueCount: Int, val battery: Double, val isGateway: Boolean)

/**
 * The 59 SAGE v1 features, in research/sage/sage/v1/state.py order: packet (10) | holder node view (22) |
 * peer node view (22) | pair (5). Each node view is computed from that node's own history ([NodeView]); for the
 * peer that is the summary it sent at handshake.
 */
object SageFeatures {
    val PACKET = listOf("p_media", "p_control", "p_text", "p_gateway_dest", "p_log_size", "p_age_frac",
        "p_remaining_log", "p_remaining_frac", "p_replicas_log", "ctx_bin_log")
    val NODE = listOf("hops_left", "rate_10m", "rate_1h", "rate_6h", "rate_24h", "uniq_10m", "uniq_1h", "uniq_24h",
        "since_contact_log", "queue_frac", "queue_count_log", "battery", "is_gateway",
        "d_since_log", "d_never", "d_count_log", "d_rate_1h", "d_rate_24h", "d_prophet_fast", "d_prophet_slow",
        "d_hour_routine", "d_expected_meets")
    val PAIR = listOf("pr_count_log", "pr_rate_1h", "pr_rate_24h", "pr_since_log", "pr_jaccard_1h")
    val NAMES = PACKET + NODE.map { "self_$it" } + NODE.map { "peer_$it" } + PAIR
    val N_PACKET = PACKET.size
    val N_NODE = NODE.size
    val N_PAIR = PAIR.size
    val COUNT = NAMES.size

    private val UNIQ_WINDOWS = doubleArrayOf(600.0, 3600.0, 86400.0)

    fun build(
        packet: PacketInfo, binSeconds: Double, destColumn: NodeId, t: Double,
        self: NodeView, selfLoad: NodeLoad, peer: NodeView, peerLoad: NodeLoad,
    ): FloatArray {
        val out = FloatArray(COUNT)
        packetView(packet, binSeconds).copyInto(out, 0)
        nodeView(self, destColumn, t, packet.hopsLeft, packet.remainingSec, selfLoad).copyInto(out, N_PACKET)
        nodeView(peer, destColumn, t, packet.hopsLeft - 1, packet.remainingSec, peerLoad).copyInto(out, N_PACKET + N_NODE)
        pairView(self, peer, t).copyInto(out, N_PACKET + 2 * N_NODE)
        return out
    }

    fun packetView(p: PacketInfo, binSeconds: Double): FloatArray = floatArrayOf(
        b(p.priorityClass == 3), b(p.priorityClass == 1), b(p.priorityClass == 2), b(p.gatewayDest),
        ln1p(p.sizeBytes.toDouble()).toFloat(),
        (p.ageSec / p.lifetimeSec).toFloat(),
        ln1p(max(p.remainingSec, 0.0)).toFloat(),
        (p.remainingSec / p.lifetimeSec).toFloat(),
        ln1p(p.replicas.toDouble()).toFloat(),
        ln(binSeconds).toFloat(),
    )

    fun nodeView(v: NodeView, dest: NodeId, t: Double, hopsLeft: Int, remaining: Double, load: NodeLoad): FloatArray {
        val out = FloatArray(N_NODE)
        out[0] = hopsLeft.toFloat()
        for (k in 0 until 4) out[1 + k] = ln1p(v.nodeRate[k].toDouble()).toFloat()
        val real = v.columnEntries().filter { it.key != ContactHistory.GATEWAY }
        for ((k, w) in UNIQ_WINDOWS.withIndex()) out[5 + k] = ln1p(real.count { t - it.value.lastMet <= w }.toDouble()).toFloat()
        out[8] = logSince(t - v.lastContact)
        out[9] = load.queueFrac.toFloat()
        out[10] = ln1p(load.queueCount.toDouble()).toFloat()
        out[11] = load.battery.toFloat()
        out[12] = b(load.isGateway)
        val c = v.column(dest)
        val since = t - (c?.lastMet ?: Double.NEGATIVE_INFINITY)
        out[13] = logSince(since)
        out[14] = b(!since.isFinite())
        out[15] = ln1p((c?.count ?: 0f).toDouble()).toFloat()
        out[16] = ln1p((c?.pairRate?.get(0) ?: 0f).toDouble()).toFloat()
        out[17] = ln1p((c?.pairRate?.get(1) ?: 0f).toDouble()).toFloat()
        out[18] = c?.prophet?.get(0) ?: 0f
        out[19] = c?.prophet?.get(1) ?: 0f
        // Daily routine: past bins with dest during the hours this packet will still be alive.
        val days = max(1, v.dayCount - 1)
        val startH = floor((t % 86400.0) / 3600.0).toInt()
        val span = ceil(remaining / 3600.0).coerceIn(1.0, 24.0)
        var routine = 0f
        if (c != null) for (h in 0 until 24) if (((h - startH) % 24 + 24) % 24 < span) routine += c.hour[h]
        out[20] = ln1p((routine / days).toDouble()).toFloat()
        val ratePerSec = (c?.pairRate?.get(1) ?: 0f) * ln(2.0) / ContactHistory.PAIR_HALF_LIVES[1]
        out[21] = ln1p(ratePerSec * remaining).toFloat()
        return out
    }

    fun pairView(self: NodeView, peer: NodeView, t: Double): FloatArray {
        val c = self.column(peer.nodeId)
        val na = self.columnEntries().filter { it.key != ContactHistory.GATEWAY && t - it.value.lastMet <= 3600 }.map { it.key }.toSet()
        val nb = peer.columnEntries().filter { it.key != ContactHistory.GATEWAY && t - it.value.lastMet <= 3600 }.map { it.key }.toSet()
        val union = (na + nb).size
        return floatArrayOf(
            ln1p((c?.count ?: 0f).toDouble()).toFloat(),
            ln1p((c?.pairRate?.get(0) ?: 0f).toDouble()).toFloat(),
            ln1p((c?.pairRate?.get(1) ?: 0f).toDouble()).toFloat(),
            logSince(t - (c?.lastMet ?: Double.NEGATIVE_INFINITY)),
            ((na intersect nb).size.toDouble() / max(union, 1)).toFloat(),
        )
    }

    private fun logSince(dt: Double): Float = ln1p(min(dt, ContactHistory.NEVER)).toFloat()
    private fun b(v: Boolean) = if (v) 1f else 0f
}
