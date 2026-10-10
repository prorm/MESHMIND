package dev.meshmind.core

import dev.meshmind.core.sage.ContactHistory
import dev.meshmind.core.sage.MiniJson
import dev.meshmind.core.sage.NodeLoad
import dev.meshmind.core.sage.PacketInfo
import dev.meshmind.core.sage.SageFeatures
import dev.meshmind.core.sage.SageNet
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Parity with the research implementation. golden.json (research/sage/scripts/export_kotlin.py) holds a synthetic
 * 6-node contact history with a flickering gateway, 256 decision queries, the features research/sage/sage/v1/state.py
 * computed for them and the ONNX model's outputs. Here every node runs its own ContactHistory (as on phones), the peer
 * view goes through the wire summary, and everything must match within 1e-4.
 */
class SageGoldenTest {
    private val tol = 1e-4

    @Suppress("UNCHECKED_CAST")
    private val golden: Map<String, Any?> by lazy {
        val text = javaClass.getResourceAsStream("/sage/golden.json")!!.use { it.readBytes().toString(Charsets.UTF_8) }
        MiniJson.parse(text) as Map<String, Any?>
    }

    @Suppress("UNCHECKED_CAST")
    private val queries get() = golden["queries"] as List<Map<String, Any?>>

    private fun Any?.d() = (this as Double)
    private fun Any?.i() = (this as Double).toInt()
    @Suppress("UNCHECKED_CAST")
    private fun Any?.dl() = (this as List<Any?>).map { it as Double }

    @Test
    fun kotlinForwardPassMatchesOnnx() {
        val net = SageNet.loadBundled()
        var worst = 0.0
        for (q in queries) {
            val x = q["features"].dl().map { it.toFloat() }.toFloatArray()
            val y = net.predict(x)
            q["outputs"].dl().forEachIndexed { k, e -> worst = maxOf(worst, abs(y[k] - e)) }
        }
        println("SAGE GOLDEN: Kotlin vs ONNX outputs, max abs error $worst over ${queries.size} rows")
        assertTrue(worst < tol, "max abs error $worst")
    }

    @Test
    fun perPhoneHistoriesReproduceResearchFeatures() {
        val n = golden["n"].i()
        val bin = golden["bin_seconds"].d()
        val gwNode = golden["gateway_node"].i()
        assertEquals(SageFeatures.NAMES, golden["feature_names"])
        val net = SageNet.loadBundled()
        val h = List(n) { ContactHistory(it.toString(), bin) }
        val byBin = queries.groupBy { it["bin"].i() }
        var worstFeature = 0.0; var worstName = ""; var worstOut = 0.0; var checked = 0

        @Suppress("UNCHECKED_CAST")
        val bins = golden["bins"] as List<List<Any?>>
        for ((k, b) in bins.withIndex()) {
            val start = b[0].d()
            val pairs = (b[1] as List<List<Double>>).map { it[0].toInt() to it[1].toInt() }
            val gwUp = b[2] as Boolean
            h.forEach { it.decayTo(start + bin) }
            val snap = h.map { it.summary(maxColumns = 10_000) }           // peers' PRoPHET rows before this bin
            for (node in 0 until n) {
                val partners = pairs.mapNotNull { (x, y) -> if (x == node) y else if (y == node) x else null }
                val gatewayPeer = gwUp && gwNode in partners
                h[node].observeBin(start, partners.map { it.toString() }, gatewayPeer) { id -> snap[id.toInt()] }
            }
            for (q in byBin[k].orEmpty()) {
                val a = q["a"].i(); val bNode = q["b"].i(); val dest = q["dest"].i()
                val qf = q["queue_frac"].dl(); val qc = q["queue_count"].dl(); val bat = q["battery"].dl()
                val gw = q["is_gateway"].dl()
                val packet = PacketInfo(q["prio"].i(), dest == n, q["size"].i(), q["age"].d(), q["lifetime"].d(),
                    q["replicas"].i(), q["hops_left"].i())
                // The peer's view arrives over the wire, as on phones.
                val peer = ContactHistory.decodeSummary(ContactHistory.encodeSummary(h[bNode].summary()))
                val x = SageFeatures.build(packet, bin, if (dest == n) ContactHistory.GATEWAY else dest.toString(), q["t"].d(),
                    h[a], NodeLoad(qf[0], qc[0].toInt(), bat[0], gw[0] == 1.0), peer, NodeLoad(qf[1], qc[1].toInt(), bat[1], gw[1] == 1.0))
                q["features"].dl().forEachIndexed { j, e ->
                    val err = abs(x[j] - e)
                    if (err > worstFeature) { worstFeature = err; worstName = SageFeatures.NAMES[j] }
                }
                val y = net.predict(x)
                q["outputs"].dl().forEachIndexed { j, e -> worstOut = maxOf(worstOut, abs(y[j] - e)) }
                checked++
            }
        }
        println("SAGE GOLDEN: $checked queries, worst feature error $worstFeature ($worstName), worst output error $worstOut")
        assertEquals(queries.size, checked)
        assertTrue(worstFeature < tol, "feature $worstName differs by $worstFeature")
        assertTrue(worstOut < tol, "outputs differ by $worstOut")
    }

    @Test
    fun historySurvivesPersistence() {
        val h = ContactHistory("A-1234")
        for (k in 0 until 500) h.observeBin(1_000_000.0 + k * 20, if (k % 3 == 0) listOf("B-1", "C-2") else emptyList(), k % 7 == 0)
        val back = ContactHistory.decodeState(h.encodeState(), "A-1234")!!
        assertEquals(h.now, back.now)
        assertEquals(h.dayCount, back.dayCount)
        assertEquals(h.column("B-1")!!.prophet.toList(), back.column("B-1")!!.prophet.toList())
        assertEquals(null, ContactHistory.decodeState(h.encodeState(), "Z-9999"), "a renamed phone starts fresh")
    }
}
