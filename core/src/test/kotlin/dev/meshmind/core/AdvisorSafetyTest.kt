package dev.meshmind.core

import dev.meshmind.core.model.Packet
import dev.meshmind.core.model.Priority
import dev.meshmind.core.routing.NextHopAdvisor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The safety envelope SAGE must live inside (Kotlin twin of research/sage tests' hostile model): whatever a
 * NextHopAdvisor does, CRITICAL/SOS routing is byte-for-byte the deterministic path, and ordinary traffic still flows.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AdvisorSafetyTest {

    /** Throws on every call and records whether it was ever shown a CRITICAL packet. */
    private class HostileAdvisor : NextHopAdvisor {
        var calls = 0
        var sawCritical = false
        override fun choose(packet: Packet, candidates: List<String>): List<String> {
            calls++
            if (packet.priority == Priority.CRITICAL) sawCritical = true
            throw IllegalStateException("hostile model")
        }
    }

    private data class SosRun(val oneWayMs: Long, val route: List<String>, val chunksBeforeSos: Int)

    /** Test-3-style scenario: SOS during a LOW bulk transfer on A↔B↔C↔D, with an optional advisor on every node. */
    private fun sosUnderLoad(advisorFor: ((String) -> NextHopAdvisor?)?, out: (SosRun) -> Unit) = meshTest(advisorFor = advisorFor) { sim ->
        sim.nodes("A", "B", "C", "D")
        sim.chain("A", "B", "C", "D")
        advanceUntilIdle()
        sim["A"].sendTransfer("D", "bulk.bin", ByteArray(10 * 16 * 1024) { it.toByte() })
        advanceTimeBy(250)
        val sos = sim["A"].sendSos("MEDICAL at Gate C", "D")
        advanceUntilIdle()
        val m = sim["D"].state.value.metrics.filter { it.side == "destination" }
        val s = m.single { it.packetId == sos.packetId }
        out(SosRun(s.oneWayMs, s.route, m.count { it.measuredAt < s.measuredAt && it.packetId != sos.packetId }))
    }

    @Test
    fun hostileAdvisorNeverSeesSosAndCannotChangeIt() {
        var baseline: SosRun? = null
        var hostileRun: SosRun? = null
        val hostile = HostileAdvisor()
        sosUnderLoad(null) { baseline = it }
        sosUnderLoad({ hostile }) { hostileRun = it }
        println("ADVISOR SAFETY: baseline $baseline, hostile $hostileRun, hostile calls ${hostile.calls}")
        assertEquals(baseline, hostileRun, "SOS latency, route and preemption are identical with a hostile advisor")
        assertFalse(hostile.sawCritical, "the advisor is never shown a CRITICAL packet")
        assertTrue(hostile.calls > 0, "it was consulted for ordinary chunks (and its crashes were contained)")
    }

    @Test
    fun emptyOrForeignAnswersFallBackToFlooding() {
        for (answer in listOf(emptyList(), listOf("Z-nobody"))) {
            meshTest(advisorFor = { NextHopAdvisor { _, _ -> answer } }) { sim ->
                sim.nodes("A", "B", "C")
                sim.chain("A", "B", "C")
                advanceUntilIdle()
                val t = sim["A"].sendText("C", "meet at gate")
                advanceUntilIdle()
                assertTrue(sim["C"].state.value.inbox.any { it.packetId == t.packetId }, "delivered despite advisor answer $answer")
            }
        }
    }

    @Test
    fun slowAdvisorIsSwitchedOff() {
        var calls = 0
        val slow = NextHopAdvisor { _, c -> calls++; Thread.sleep(8); c }
        meshTest(advisorFor = { slow }) { sim ->
            sim.nodes("A", "B", "C")
            sim.chain("A", "B", "C")
            advanceUntilIdle()
            repeat(10) { sim["A"].sendText("C", "msg $it") }
            advanceUntilIdle()
            assertEquals(10, sim["C"].state.value.inbox.size)
            assertTrue(calls <= 6, "after 3 slow calls per node the advisor is no longer consulted (calls=$calls)")
        }
    }
}
