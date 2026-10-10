package dev.meshmind.core

import dev.meshmind.core.model.Frame
import dev.meshmind.core.model.WireCodec
import dev.meshmind.core.sim.MeshSimulation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Found in the 3-phone session physical_20261009_02 (mini relay_recovery #4): after B restarted, A's side of the new
 * Nearby connection came up ~0.7 s before B's. A's HELLO failed four times and was dropped; B then discarded every
 * frame from A as "data before HELLO", and the queued text never left A within 120 s.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HandshakeRecoveryTest {

    private fun isHello(bytes: ByteArray) = runCatching { WireCodec.decode(bytes) is Frame.Hello }.getOrDefault(false)

    private suspend fun TestScope.deliveredWithin(sim: MeshSimulation, limitMs: Long): Long? {
        val text = sim["A"].sendText("C", "queued while the link was half-open")
        val start = currentTime
        while (currentTime - start <= limitMs) {
            if (sim["C"].state.value.inbox.any { it.packetId == text.packetId }) return currentTime - start
            advanceTimeBy(50)
        }
        return null
    }

    @Test
    fun helloThatFailsWhileThePeerIsNotReadyIsRetriedUntilItGoesThrough() = meshTest { sim ->
        sim.nodes("A", "B", "C")
        sim.link("B", "C")
        advanceTimeBy(1_000)
        var peerReady = false
        sim.network.refuseSend = { from, to, _ -> !peerReady && from == "A" && to == "B" }
        sim.link("A", "B")
        advanceTimeBy(3_000)   // longer than the old 4 x 500 ms retry budget
        peerReady = true
        val took = deliveredWithin(sim, 30_000)
        println("HANDSHAKE: text over a link whose first HELLO sends failed delivered after ${took}ms")
        assertTrue(took != null && took <= 5_000, "link must recover once the peer accepts, took $took ms")
    }

    @Test
    fun lostHelloIsResentWhenThePeerNeverAnswersWithASummary() = meshTest { sim ->
        sim.nodes("A", "B", "C")
        sim.link("B", "C")
        advanceTimeBy(1_000)
        var lose = true
        sim.network.loseFrame = { from, to, bytes -> lose && from == "A" && to == "B" && isHello(bytes) }
        sim.link("A", "B")
        advanceTimeBy(500)
        lose = false   // the HELLO already vanished; only a re-send can complete the handshake
        val took = deliveredWithin(sim, 30_000)
        println("HANDSHAKE: text after a silently lost HELLO delivered after ${took}ms")
        assertTrue(took != null && took <= 5_000, "watchdog must re-send HELLO, took $took ms")
    }
}
