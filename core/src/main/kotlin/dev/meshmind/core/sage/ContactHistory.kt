package dev.meshmind.core.sage

import dev.meshmind.core.model.Addresses
import dev.meshmind.core.model.NodeId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.math.floor
import kotlin.math.pow

/** What one node remembers about one contact column (a peer, or [GATEWAY] = "any live gateway"). */
class ColumnStats(
    var lastMet: Double = Double.NEGATIVE_INFINITY,
    var count: Float = 0f,
    /** Encounter-rate EWMAs, half-lives [ContactHistory.PAIR_HALF_LIVES]. */
    val pairRate: FloatArray = FloatArray(2),
    /** PRoPHET delivery predictability, fast and slow aging. */
    val prophet: FloatArray = FloatArray(2),
    /** Contact bins per hour of day. */
    val hour: FloatArray = FloatArray(24),
) {
    fun copy() = ColumnStats(lastMet, count, pairRate.copyOf(), prophet.copyOf(), hour.copyOf())
}

/** Read-only view of a node's contact history: a phone's own [ContactHistory], or a peer's decoded [summary]. */
interface NodeView {
    val nodeId: NodeId
    val nodeRate: FloatArray
    val lastContact: Double
    val dayCount: Int
    fun column(id: NodeId): ColumnStats?
    fun columnEntries(): Collection<Map.Entry<NodeId, ColumnStats>>
}

class NodeSummary(
    override val nodeId: NodeId,
    override val nodeRate: FloatArray,
    override val lastContact: Double,
    override val dayCount: Int,
    val columns: Map<NodeId, ColumnStats>,
) : NodeView {
    override fun column(id: NodeId) = columns[id]
    override fun columnEntries() = columns.entries
}

/**
 * One phone's own row of SAGE's contact history: the Kotlin twin of research/sage/sage/v1/state.py `NodeState`,
 * restricted to the phone itself (the research matrices hold every node because they simulate everyone).
 *
 * Time is in seconds on a "local" clock (epoch seconds + time-zone offset on phones) so hour-of-day and day features
 * mean local hours. Arithmetic mirrors the research code's float32 arrays so features match within 1e-4.
 * Contacts arrive as completed bins ([observeBin]); nothing here can see the future.
 */
class ContactHistory(
    override val nodeId: NodeId,
    val binSeconds: Double = 20.0,
    private val maxColumns: Int = 2048,
) : NodeView {
    private val columns = LinkedHashMap<NodeId, ColumnStats>()
    override val nodeRate = FloatArray(RATE_HALF_LIVES.size)
    override var lastContact = Double.NEGATIVE_INFINITY; private set
    private val days = sortedSetOf<Long>()
    override val dayCount: Int get() = days.size
    var now = Double.NEGATIVE_INFINITY; private set
    var binsObserved = 0L; private set

    override fun column(id: NodeId) = columns[id]
    override fun columnEntries() = columns.entries

    /** Ages every EWMA and PRoPHET value to [t]. A clock that moves backwards (phone time change) is ignored. */
    fun decayTo(t: Double) {
        if (!now.isFinite()) { now = t; return }
        val dt = t - now
        if (dt <= 0) return
        val pairF = FloatArray(PAIR_HALF_LIVES.size) { 0.5.pow(dt / PAIR_HALF_LIVES[it]).toFloat() }
        val rateF = FloatArray(RATE_HALF_LIVES.size) { 0.5.pow(dt / RATE_HALF_LIVES[it]).toFloat() }
        val prophetF = FloatArray(PROPHET_UNITS.size) { PROPHET_GAMMA.pow(dt / PROPHET_UNITS[it]).toFloat() }
        for (c in columns.values) {
            for (k in pairF.indices) c.pairRate[k] *= pairF[k]
            for (k in prophetF.indices) c.prophet[k] *= prophetF[k]
        }
        for (k in rateF.indices) nodeRate[k] *= rateF[k]
        now = t
    }

    /**
     * Records the COMPLETED bin [start, start + binSeconds): the peers seen in it (connected or discovered) and whether
     * any of them was an Internet gateway. [peerProphet] returns a peer's own PRoPHET values (from its summary) for
     * the transitive update; unknown peers contribute only the direct update.
     */
    fun observeBin(start: Double, peers: Collection<NodeId>, gatewayPeer: Boolean, peerProphet: (NodeId) -> NodeView? = { null }) {
        val end = start + binSeconds
        if (end < now) return
        decayTo(end)
        binsObserved++
        val real = peers.filter { it != nodeId && it != GATEWAY }.distinct()
        val cols = if (gatewayPeer) real + GATEWAY else real
        if (cols.isEmpty()) return
        val isNew = cols.associateWith { (columns[it]?.lastMet ?: Double.NEGATIVE_INFINITY) < start - 0.5 * binSeconds }
        val hour = floor((start % 86400.0) / 3600.0).toInt()
        for (id in cols) {
            val c = columns.getOrPut(id) { ColumnStats() }
            c.count += 1f
            for (k in c.pairRate.indices) c.pairRate[k] += 1f
            c.hour[hour] += 1f
        }
        days += floor(start / 86400.0).toLong()
        if (real.isNotEmpty()) {
            for (k in nodeRate.indices) nodeRate[k] += real.size.toFloat()
            lastContact = end
        }
        val newCols = cols.filter { isNew.getValue(it) }
        if (newCols.isNotEmpty()) {
            for (k in PROPHET_UNITS.indices) {
                for (id in newCols) {
                    val p = columns.getValue(id).prophet
                    p[k] = p[k] + (1f - p[k]) * PROPHET_INIT
                }
                for (v in newCols) {
                    if (v == GATEWAY) continue
                    val peer = peerProphet(v) ?: continue
                    val factor = columns.getValue(v).prophet[k] * PROPHET_BETA
                    for ((id, pc) in peer.columnEntries()) {
                        if (id == nodeId) continue
                        val cand = pc.prophet[k] * factor
                        val mine = columns[id]
                        if (mine == null) {
                            if (cand > 0f) columns[id] = ColumnStats().also { it.prophet[k] = cand }
                        } else if (cand > mine.prophet[k]) mine.prophet[k] = cand
                    }
                }
            }
        }
        for (id in cols) columns.getValue(id).lastMet = end
        evictIfNeeded()
    }

    private fun evictIfNeeded() {
        if (columns.size <= maxColumns) return
        columns.entries.filter { it.key != GATEWAY }.sortedBy { it.value.lastMet }
            .take(columns.size - maxColumns).forEach { columns.remove(it.key) }
    }

    /**
     * Bounded summary for the peer: node-level stats plus the gateway column and the [maxColumns] most recently met
     * columns. ~140 B per column; 96 columns stay well inside one 32 KB Nearby payload.
     */
    fun summary(maxColumns: Int = 96): NodeSummary {
        val chosen = LinkedHashMap<NodeId, ColumnStats>()
        columns[GATEWAY]?.let { chosen[GATEWAY] = it.copy() }
        columns.entries.filter { it.key != GATEWAY }.sortedByDescending { it.value.lastMet }
            .take(maxColumns).forEach { chosen[it.key] = it.value.copy() }
        return NodeSummary(nodeId, nodeRate.copyOf(), lastContact, dayCount, chosen)
    }

    // ---- persistence (survives app restarts: "phones remember who they met yesterday") ----

    fun encodeState(): ByteArray = ByteArrayOutputStream().also { bos ->
        DataOutputStream(bos).use { o ->
            o.writeByte(STATE_VERSION)
            o.writeUTF(nodeId)
            o.writeDouble(binSeconds)
            o.writeDouble(now)
            o.writeLong(binsObserved)
            o.writeInt(days.size); days.forEach { o.writeLong(it) }
            writeBody(o, nodeRate, lastContact, columns)
        }
    }.toByteArray()

    companion object {
        /** The virtual column for "any live Internet gateway" (research: column n). */
        const val GATEWAY: NodeId = Addresses.ANY_GATEWAY
        val RATE_HALF_LIVES = doubleArrayOf(600.0, 3600.0, 21600.0, 86400.0)
        val PAIR_HALF_LIVES = doubleArrayOf(3600.0, 86400.0)
        const val PROPHET_INIT = 0.75f
        const val PROPHET_BETA = 0.25f
        const val PROPHET_GAMMA = 0.98
        val PROPHET_UNITS = doubleArrayOf(60.0, 600.0)
        const val NEVER = 30 * 86400.0
        private const val STATE_VERSION = 1
        private const val SUMMARY_VERSION = 1
        private const val MAX_WIRE_COLUMNS = 256

        /** Restores a persisted history; returns null if the bytes are not a compatible state for [nodeId]. */
        fun decodeState(bytes: ByteArray, nodeId: NodeId): ContactHistory? = runCatching {
            DataInputStream(ByteArrayInputStream(bytes)).use { i ->
                require(i.readByte().toInt() == STATE_VERSION)
                require(i.readUTF() == nodeId)
                val h = ContactHistory(nodeId, i.readDouble())
                h.now = i.readDouble()
                h.binsObserved = i.readLong()
                repeat(i.readInt()) { h.days += i.readLong() }
                val body = readBody(i, Int.MAX_VALUE)
                body.rate.copyInto(h.nodeRate)
                h.lastContact = body.lastContact
                h.columns.putAll(body.columns)
                h
            }
        }.getOrNull()

        fun encodeSummary(s: NodeSummary): ByteArray = ByteArrayOutputStream().also { bos ->
            DataOutputStream(bos).use { o ->
                o.writeByte(SUMMARY_VERSION)
                o.writeUTF(s.nodeId)
                o.writeShort(s.dayCount.coerceAtMost(Short.MAX_VALUE.toInt()))
                writeBody(o, s.nodeRate, s.lastContact, s.columns)
            }
        }.toByteArray()

        /**
         * Decodes a peer's summary. Values are untrusted: anything non-finite or out of range is clipped to what
         * training saw, and the column count is bounded.
         */
        fun decodeSummary(bytes: ByteArray): NodeSummary = DataInputStream(ByteArrayInputStream(bytes)).use { i ->
            require(i.readByte().toInt() == SUMMARY_VERSION) { "unsupported SAGE summary version" }
            val id = i.readUTF()
            val days = i.readShort().toInt().coerceIn(0, 3650)
            val body = readBody(i, MAX_WIRE_COLUMNS)
            NodeSummary(id, body.rate, body.lastContact, days, body.columns)
        }

        private class Body(val rate: FloatArray, val lastContact: Double, val columns: LinkedHashMap<NodeId, ColumnStats>)

        private fun writeBody(o: DataOutputStream, rate: FloatArray, lastContact: Double, cols: Map<NodeId, ColumnStats>) {
            rate.forEach { o.writeFloat(it) }
            o.writeDouble(lastContact)
            o.writeInt(cols.size)
            for ((id, c) in cols) {
                o.writeUTF(id)
                o.writeDouble(c.lastMet)
                o.writeFloat(c.count)
                c.pairRate.forEach { o.writeFloat(it) }
                c.prophet.forEach { o.writeFloat(it) }
                c.hour.forEach { o.writeFloat(it) }
            }
        }

        private fun readBody(i: DataInputStream, maxCols: Int): Body {
            val rate = FloatArray(RATE_HALF_LIVES.size) { clipCount(i.readFloat()) }
            val lastContact = clipTime(i.readDouble())
            val n = i.readInt()
            require(n in 0..maxCols) { "too many columns: $n" }
            val cols = LinkedHashMap<NodeId, ColumnStats>()
            repeat(n) {
                val id = i.readUTF()
                val c = ColumnStats(
                    clipTime(i.readDouble()), clipCount(i.readFloat()),
                    FloatArray(PAIR_HALF_LIVES.size) { clipCount(i.readFloat()) },
                    FloatArray(PROPHET_UNITS.size) { i.readFloat().let { v -> if (v.isFinite()) v.coerceIn(0f, 1f) else 0f } },
                    FloatArray(24) { clipCount(i.readFloat()) },
                )
                cols[id] = c
            }
            return Body(rate, lastContact, cols)
        }

        private fun clipCount(v: Float) = if (v.isFinite()) v.coerceIn(0f, 1e7f) else 0f
        private fun clipTime(v: Double) = if (v.isNaN() || v == Double.POSITIVE_INFINITY) Double.NEGATIVE_INFINITY else v
    }
}
