package dev.meshmind.core.sage

import dev.meshmind.core.model.NodeId
import dev.meshmind.core.model.Packet
import dev.meshmind.core.model.PacketType
import dev.meshmind.core.model.Priority
import dev.meshmind.core.routing.LinkExtension
import dev.meshmind.core.routing.NextHopAdvisor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** One scored (packet, neighbour) opportunity: what SAGE would have done. */
data class ShadowDecision(
    val atMs: Long,
    val packetId: String,
    val type: PacketType,
    val peer: NodeId,
    val gainReplicate: Float,
    val uSelf: Float,
    val uPeer: Float,
    /** Eco mode would skip this copy (gain ≤ threshold). Shadow mode sends it anyway. */
    val wouldSkip: Boolean,
    val micros: Long,
)

data class ShadowStats(
    val enabled: Boolean = false,
    val modelLoaded: Boolean = false,
    val scored: Long = 0,
    val wouldSkip: Long = 0,
    val noPeerSummary: Long = 0,
    val errors: Long = 0,
    /** Opportunities dropped unscored because the scoring worker was backed up. */
    val dropped: Long = 0,
    val peersWithSummary: Int = 0,
    val summariesReceived: Long = 0,
    val binsObserved: Long = 0,
    val knownContacts: Int = 0,
    val p50Micros: Long = 0,
    val p95Micros: Long = 0,
    val lastError: String? = null,
)

/**
 * SAGE v1 in shadow mode. As the router's [NextHopAdvisor] it scores every ordinary (packet, neighbour)
 * opportunity and logs what SAGE would do, but always returns the candidates unchanged, so routing is exactly the
 * deterministic router's. (The router never shows it CRITICAL/SOS, ACKs or beacons; see Router.adviseLocked.)
 * As the router's [LinkExtension] it swaps contact summaries with neighbours at handshake: the peer half of
 * SAGE's features.
 *
 * [localSeconds] must be the same clock [observeBin] uses (local epoch seconds).
 */
class SageShadow(
    private val selfId: NodeId,
    val history: ContactHistory,
    private val net: SageNet?,
    private val localLoad: () -> NodeLoad,
    private val localSeconds: () -> Double,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val enabled: () -> Boolean = { true },
    val ecoThreshold: Float = 0.005f,
    private val maxDecisions: Int = 2_000,
    private val maxContactRows: Int = 20_000,
    /**
     * Where scoring runs. null = inline (tests). On phones a debug-build score costs 1-3 ms per neighbour, which inside
     * the router's lock tripped its 5 ms advisor guard after the first decision, so the app scores on a worker
     * thread and [choose] returns at once: shadow mode then adds no latency to any routing decision.
     */
    private val scoreOn: ((() -> Unit) -> Unit)? = null,
) : NextHopAdvisor, LinkExtension {

    private class PeerEntry(val summary: NodeSummary, val load: NodeLoad, val receivedAtMs: Long)

    private val lock = Any()
    private val peers = object : LinkedHashMap<NodeId, PeerEntry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<NodeId, PeerEntry>?) = size > 256
    }
    private val replicas = object : LinkedHashMap<String, Int>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?) = size > 5_000
    }
    private val decisions = ArrayDeque<ShadowDecision>()
    private val contacts = ArrayDeque<String>()
    private val latencies = ArrayDeque<Long>()
    private var stats = ShadowStats(modelLoaded = net != null)
    private val _stats = MutableStateFlow(stats)
    val statsFlow: StateFlow<ShadowStats> = _stats.asStateFlow()

    // ---- NextHopAdvisor: score, log, never change the answer --------------------------------------------------

    override fun choose(packet: Packet, candidates: List<NodeId>): List<NodeId> {
        if (!enabled() || packet.priority == Priority.CRITICAL) return candidates
        val nowMs = clockMs()
        val run = scoreOn
        if (run == null) score(packet, candidates, nowMs) else run { score(packet, candidates, nowMs) }
        return candidates
    }

    /** Called by the app's worker when its queue is full and an opportunity is dropped unscored. */
    fun noteDropped() = synchronized(lock) { stats = stats.copy(dropped = stats.dropped + 1); _stats.value = stats }

    /** Runs a few predictions so the first real scores are not measured cold (class loading, JIT). */
    fun warmUp(rounds: Int = 200) {
        val n = net ?: return
        val x = FloatArray(SageFeatures.COUNT)
        repeat(rounds) { n.predict(x) }
    }

    private fun score(packet: Packet, candidates: List<NodeId>, nowMs: Long) {
        synchronized(lock) {
            try {
                val info = PacketInfo(
                    priorityClass = if (packet.type == PacketType.CHUNK || packet.priority == Priority.LOW) 3
                        else if (packet.priority == Priority.HIGH) 1 else 2,
                    gatewayDest = packet.isForAnyGateway,
                    sizeBytes = packet.payload.size,
                    ageSec = ((nowMs - packet.createdAt) / 1000.0).coerceAtLeast(0.0),
                    lifetimeSec = ((packet.expiresAt - packet.createdAt) / 1000.0).coerceAtLeast(1.0),
                    replicas = replicas[packet.packetId] ?: 0,
                    hopsLeft = packet.ttl,
                )
                val dest = if (packet.isForAnyGateway) ContactHistory.GATEWAY else packet.destinationId
                val t = localSeconds()
                val load = localLoad()
                for (peer in candidates) {
                    val p = peers[peer]
                    if (p == null) { stats = stats.copy(noPeerSummary = stats.noPeerSummary + 1); continue }
                    if (net == null) continue
                    val oneNs = System.nanoTime()
                    val x = SageFeatures.build(info, history.binSeconds, dest, t, history, load, p.summary, p.load)
                    val y = net.predict(x)
                    if (y.any { !it.isFinite() } || x.any { !it.isFinite() }) {
                        stats = stats.copy(errors = stats.errors + 1, lastError = "non-finite SAGE value")
                        continue
                    }
                    val gain = y[SageNet.GAIN_REPLICATE]
                    val skip = gain <= ecoThreshold
                    val micros = (System.nanoTime() - oneNs) / 1_000
                    record(ShadowDecision(nowMs, packet.packetId, packet.type, peer, gain, y[SageNet.U_SELF], y[SageNet.U_PEER], skip, micros))
                    stats = stats.copy(scored = stats.scored + 1, wouldSkip = stats.wouldSkip + if (skip) 1 else 0)
                }
                replicas[packet.packetId] = (replicas[packet.packetId] ?: 0) + candidates.size
            } catch (e: Exception) {
                stats = stats.copy(errors = stats.errors + 1, lastError = e.message ?: e.javaClass.simpleName)
            }
            publish()
        }
    }

    private fun record(d: ShadowDecision) {
        decisions.addLast(d)
        while (decisions.size > maxDecisions) decisions.removeFirst()
        latencies.addLast(d.micros)
        while (latencies.size > 512) latencies.removeFirst()
    }

    // ---- LinkExtension: contact summaries ---------------------------------------------------------------------

    override fun onPeerIdentified(peer: NodeId): ByteArray? {
        if (!enabled()) return null
        val load = localLoad()
        val summary = synchronized(lock) { history.summary() }
        return ByteArrayOutputStream().also { bos ->
            DataOutputStream(bos).use { o ->
                o.writeByte(WIRE_VERSION)
                o.writeFloat(load.queueFrac.toFloat())
                o.writeInt(load.queueCount)
                o.writeFloat(load.battery.toFloat())
                o.writeBoolean(load.isGateway)
                val s = ContactHistory.encodeSummary(summary)
                o.writeInt(s.size)
                o.write(s)
            }
        }.toByteArray()
    }

    override fun onFrame(peer: NodeId, payload: ByteArray) {
        DataInputStream(ByteArrayInputStream(payload)).use { i ->
            require(i.readByte().toInt() == WIRE_VERSION) { "unsupported SAGE frame version" }
            val qf = i.readFloat().let { if (it.isFinite()) it.coerceIn(0f, 1f) else 0f }
            val qc = i.readInt().coerceIn(0, 100_000)
            val bat = i.readFloat().let { if (it.isFinite()) it.coerceIn(0f, 1f) else 1f }
            val gw = i.readBoolean()
            val n = i.readInt()
            require(n in 1..(64 * 1024)) { "bad SAGE summary length" }
            val bytes = ByteArray(n).also { i.readFully(it) }
            val summary = ContactHistory.decodeSummary(bytes)
            require(summary.nodeId == peer) { "summary claims to be ${summary.nodeId}, link is $peer" }
            synchronized(lock) {
                peers[peer] = PeerEntry(summary, NodeLoad(qf.toDouble(), qc, bat.toDouble(), gw), clockMs())
                stats = stats.copy(summariesReceived = stats.summariesReceived + 1)
                publish()
            }
        }
    }

    // ---- contact bins (driven by the app every binSeconds) ----------------------------------------------------

    /** Records a completed contact bin: everyone seen (connected or discovered), and whether one was a gateway. */
    fun observeBin(start: Double, seen: Collection<NodeId>, gatewayPeer: Boolean) = synchronized(lock) {
        history.observeBin(start, seen, gatewayPeer) { id -> peers[id]?.summary }
        val s = start.toLong()
        seen.filter { it != selfId }.distinct().forEach { contacts.addLast("$s,$selfId,$it,0") }
        if (gatewayPeer) contacts.addLast("$s,$selfId,${ContactHistory.GATEWAY},1")
        while (contacts.size > maxContactRows) contacts.removeFirst()
        publish()
    }

    /** Pseudonymous telemetry for the research replay: contact bins and shadow decisions. No message content. */
    fun exportCsv(): String = synchronized(lock) {
        val sb = StringBuilder()
        sb.appendLine("# sage_contacts v1 bin_seconds=${history.binSeconds}")
        sb.appendLine("bin_start_local_s,self,peer,gateway")
        contacts.forEach { sb.appendLine(it) }
        sb.appendLine("# sage_decisions v1 eco_threshold=$ecoThreshold")
        sb.appendLine("at_ms,packet,type,peer,gain_replicate,u_self,u_peer,would_skip,micros")
        decisions.forEach { d ->
            sb.appendLine("${d.atMs},${d.packetId.take(8)},${d.type},${d.peer},${d.gainReplicate},${d.uSelf},${d.uPeer},${d.wouldSkip},${d.micros}")
        }
        sb.toString()
    }

    /** Persistable snapshot of the contact history (taken under the same lock as every update). */
    fun encodeHistory(): ByteArray = synchronized(lock) { history.encodeState() }

    fun recentDecisions(n: Int = 50): List<ShadowDecision> = synchronized(lock) { decisions.toList().takeLast(n) }

    private fun publish() {
        val sorted = latencies.sorted()
        fun pct(q: Double) = if (sorted.isEmpty()) 0 else sorted[((sorted.size - 1) * q).toInt()]
        stats = stats.copy(
            enabled = enabled(), peersWithSummary = peers.size, binsObserved = history.binsObserved,
            knownContacts = history.columnEntries().count { it.value.lastMet.isFinite() },
            p50Micros = pct(0.5), p95Micros = pct(0.95),
        )
        _stats.value = stats
    }

    companion object {
        private const val WIRE_VERSION = 1
    }
}
