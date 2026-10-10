package dev.meshmind.core

import dev.meshmind.core.model.Frame
import dev.meshmind.core.model.Packet
import dev.meshmind.core.model.PacketType
import dev.meshmind.core.model.Priority
import dev.meshmind.core.model.WireCodec
import dev.meshmind.core.sage.ContactHistory
import dev.meshmind.core.sage.NodeLoad
import dev.meshmind.core.sage.SageNet
import dev.meshmind.core.sage.SageShadow
import dev.meshmind.core.sim.MeshSimulation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** SAGE in shadow mode on simulated phones: it scores real decisions, swaps summaries, and changes nothing. */
@OptIn(ExperimentalCoroutinesApi::class)
class SageShadowTest {
    private val net by lazy { SageNet.loadBundled() }
    private val base = 1_791_000_000.0

    private fun TestScope.shadowFor(id: String) = SageShadow(
        id, ContactHistory(id), net,
        localLoad = { NodeLoad(0.1, 3, 0.8, false) },
        localSeconds = { base + currentTime / 1000.0 },
        clockMs = { currentTime },
    )

    /** The app's 20 s contact-bin timer: every node records which neighbours it is linked to. */
    private fun TestScope.contactBins(sim: MeshSimulation) = backgroundScope.launch {
        while (true) {
            val start = base + currentTime / 1000.0
            delay(20_000)
            sim.nodes.values.forEach { n ->
                (n.advisor as? SageShadow)?.observeBin(start, n.router.state.value.connectedPeers, false)
            }
        }
    }

    private class Outcome(val delivered: List<String>, val sosLatency: Long, val chunksDone: Boolean)

    private suspend fun TestScope.scenario(sim: MeshSimulation): Outcome {
        sim.nodes("A", "B", "C", "D")
        sim.chain("A", "B", "C", "D")
        advanceTimeBy(65_000)                              // a few contact bins of history, summaries exchanged
        sim.tickAll()
        val texts = (1..5).map { sim["A"].sendText("D", "meet at gate $it") }
        sim["A"].sendTransfer("D", "photo.jpg", ByteArray(6 * 16 * 1024) { it.toByte() })
        advanceTimeBy(300)
        val sos = sim["A"].sendSos("MEDICAL", "D")
        advanceTimeBy(30_000)
        val d = sim["D"].state.value
        return Outcome(
            texts.map { t -> if (d.inbox.any { it.packetId == t.packetId }) "ok" else "missing" },
            d.metrics.single { it.packetId == sos.packetId && it.side == "destination" }.oneWayMs,
            d.transfers.single().completedAt != null,
        )
    }

    @Test
    fun shadowScoresDecisionsButRoutingIsUnchanged() {
        var plain: Outcome? = null
        meshTest { sim -> plain = scenario(sim) }
        var shadow: Outcome? = null
        val shadows = mutableListOf<SageShadow>()
        meshTestWithShadows(shadows) { sim -> contactBins(sim); shadow = scenario(sim) }
        val p = plain!!; val s = shadow!!
        assertEquals(p.delivered, s.delivered)
        assertEquals(p.sosLatency, s.sosLatency, "SOS latency identical with SAGE in shadow mode")
        assertEquals(p.chunksDone, s.chunksDone)
        val stats = shadows.map { it.statsFlow.value }
        println("SAGE SHADOW: " + stats.joinToString { "scored=${it.scored} skip=${it.wouldSkip} noSummary=${it.noPeerSummary} peers=${it.peersWithSummary} errors=${it.errors} p50=${it.p50Micros}us" })
        assertTrue(stats.sumOf { it.scored } > 0, "SAGE scored real decisions")
        assertTrue(stats.all { it.errors == 0L })
        assertTrue(stats.all { it.summariesReceived > 0 }, "every node received a neighbour summary")
        val decisions = shadows.flatMap { it.recentDecisions() }
        assertTrue(decisions.all { it.gainReplicate in 0f..1f && it.uSelf in 0f..1f })
        assertTrue(shadows.first().exportCsv().contains("bin_start_local_s,self,peer,gateway"))
    }

    private fun meshTestWithShadows(out: MutableList<SageShadow>, block: suspend TestScope.(MeshSimulation) -> Unit) {
        lateinit var scope: TestScope
        meshTest(advisorFor = { id -> with(scope) { shadowFor(id) }.also { out += it } }) { sim -> scope = this; block(sim) }
    }

    /** On phones scoring runs on a worker: choose() must answer at once and score later, unchanged. */
    @Test
    fun offThreadScoringReturnsImmediatelyAndScoresLater() {
        val queued = mutableListOf<() -> Unit>()
        val load = { NodeLoad(0.1, 3, 0.8, false) }
        val a = SageShadow("A-1", ContactHistory("A-1"), net, load, { base }, clockMs = { 1_000L }, scoreOn = { queued += it })
        val b = SageShadow("B-2", ContactHistory("B-2"), net, load, { base })
        a.onFrame("B-2", b.onPeerIdentified("A-1")!!)
        a.warmUp(5)
        val p = Packet(Packet.newId(), "A-1", "D-4", PacketType.TEXT, Priority.NORMAL, 0, 600_000, 8, 0,
            "hi".toByteArray(), listOf("A-1"), "CHAT")
        assertEquals(listOf("B-2"), a.choose(p, listOf("B-2")))
        assertEquals(0, a.statsFlow.value.scored, "nothing scored on the caller's thread")
        assertEquals(1, queued.size)
        queued.single()()
        assertEquals(1, a.statsFlow.value.scored)
        assertEquals(0, a.statsFlow.value.errors)
    }

    @Test
    fun extensionFrameRoundTripsAndSpoofedSummaryIsRejected() {
        val payload = byteArrayOf(1, 2, 3, 4)
        val f = WireCodec.decode(WireCodec.encode(Frame.Extension(payload)))
        assertContentEquals(payload, (f as Frame.Extension).payload)

        val a = SageShadow("A-1", ContactHistory("A-1"), null, { NodeLoad(0.0, 0, 1.0, false) }, { 0.0 })
        val b = SageShadow("B-2", ContactHistory("B-2"), null, { NodeLoad(0.0, 0, 1.0, false) }, { 0.0 })
        b.onFrame("A-1", a.onPeerIdentified("B-2")!!)
        assertEquals(1, b.statsFlow.value.summariesReceived)
        assertFailsWith<IllegalArgumentException> { b.onFrame("C-3", a.onPeerIdentified("B-2")!!) }
    }
}
