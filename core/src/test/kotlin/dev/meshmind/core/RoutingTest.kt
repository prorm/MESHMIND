package dev.meshmind.core

import dev.meshmind.core.model.Addresses
import dev.meshmind.core.model.PacketState
import dev.meshmind.core.model.PacketType
import dev.meshmind.core.routing.RouterConfig
import dev.meshmind.core.routing.TrackStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RoutingTest {

    /** Test 1: A↔B↔C, A→C travels A→B→C and the source sees the ACKed route. */
    @Test
    fun multiHopTextFollowsPathAndIsAcknowledged() = meshTest { sim ->
        sim.nodes("A", "B", "C")
        sim.chain("A", "B", "C")
        advanceUntilIdle()

        val p = sim["A"].sendText("C", "Meet me at Gate C")
        advanceUntilIdle()

        val inbox = sim["C"].state.value.inbox
        assertEquals(1, inbox.size)
        assertEquals("Meet me at Gate C", inbox[0].text)
        assertEquals(listOf("A", "B", "C"), inbox[0].route)
        assertEquals(2, inbox[0].hopCount)

        val tracked = sim["A"].state.value.tracked.single { it.packetId == p.packetId }
        assertEquals(TrackStatus.DELIVERED, tracked.status)
        assertEquals(listOf("A", "B", "C"), tracked.deliveredRoute)
        assertEquals(listOf("B"), tracked.firstHops)
        assertTrue(tracked.rttMs!! > 0)
        // B relayed; nobody else delivered it.
        assertTrue(sim["B"].state.value.inbox.isEmpty())
        assertTrue(sim["B"].state.value.stats.packetsForwarded >= 1, "B forwarded the text")
    }

    /** Test 4: the same packet reaches D via B and via C; D processes it exactly once. */
    @Test
    fun duplicateViaTwoPeersIsProcessedOnce() = meshTest { sim ->
        sim.nodes("A", "B", "C", "D")
        sim.link("A", "B"); sim.link("A", "C"); sim.link("B", "D"); sim.link("C", "D")
        advanceUntilIdle()

        sim["A"].sendText("D", "diamond")
        advanceUntilIdle()

        val d = sim["D"].state.value
        assertEquals(1, d.inbox.size, "delivered exactly once")
        assertEquals(1L, d.stats.deliveredToMe)
        assertTrue(d.stats.duplicatesRejected >= 1, "second copy rejected as duplicate")
    }

    /** Test 5: a loop A↔B↔C↔A terminates (dedupe) and broadcasts are delivered once per node. */
    @Test
    fun loopDoesNotPropagateForever() = meshTest { sim ->
        sim.nodes("A", "B", "C")
        sim.link("A", "B"); sim.link("B", "C"); sim.link("C", "A")
        advanceUntilIdle()
        val framesBefore = sim.network.framesDelivered

        sim["A"].sendText(Addresses.BROADCAST, "loop test")
        advanceUntilIdle() // would never return if the packet circulated forever

        assertEquals(1, sim["B"].state.value.inbox.size)
        assertEquals(1, sim["C"].state.value.inbox.size)
        val dataFrames = sim.network.framesDelivered - framesBefore
        // 1 broadcast over at most 4 directed transmissions + per-recipient ACKs; anything unbounded fails here.
        assertTrue(dataFrames <= 12, "bounded traffic, was $dataFrames frames")
        val dups = listOf("A", "B", "C").sumOf { sim[it].state.value.stats.duplicatesRejected }
        assertTrue(dups >= 1, "loop copies were rejected")
    }

    /** TTL bounds hop count even without dedupe help: on a 12-node line the packet stops after 8 hops. */
    @Test
    fun ttlLimitsHops() = meshTest { sim ->
        val ids = (0..11).map { "N$it" }
        ids.forEach { sim.addNode(it) }
        sim.chain(*ids.toTypedArray())
        advanceUntilIdle()

        sim["N0"].sendText("N11", "far away")
        advanceUntilIdle()

        assertTrue(sim["N11"].state.value.inbox.isEmpty(), "beyond TTL")
        // N8 received it with TTL 0 and refused to forward.
        assertEquals(1L, sim["N8"].state.value.stats.ttlExhausted)
        assertNull(sim.node("N9").store.snapshot().values.firstOrNull { it.packet.type == PacketType.TEXT })
    }

    /** Test 2: A↔B, C absent. B stores the packet; when B↔C connects the SUMMARY exchange delivers it. */
    @Test
    fun storeAndForwardDeliversWhenPeerAppears() = meshTest { sim ->
        sim.nodes("A", "B", "C")
        sim.link("A", "B")
        advanceUntilIdle()

        val p = sim["A"].sendText("C", "Meet me at Gate C")
        advanceUntilIdle()
        assertTrue(sim["C"].state.value.inbox.isEmpty())
        assertEquals(PacketState.QUEUED, sim.node("B").store.snapshot()[p.packetId]!!.state)

        advanceTimeBy(60_000)
        sim.link("B", "C")
        advanceUntilIdle()

        val inbox = sim["C"].state.value.inbox
        assertEquals(1, inbox.size)
        assertEquals(listOf("A", "B", "C"), inbox[0].route)
        assertTrue(inbox[0].latencyMs >= 60_000, "latency includes the time it sat on B")
        // ACK came back, so B stopped replicating and A shows DELIVERED.
        assertEquals(PacketState.DELIVERED, sim.node("B").store.snapshot()[p.packetId]!!.state)
        assertEquals(TrackStatus.DELIVERED, sim["A"].state.value.tracked.single { it.packetId == p.packetId }.status)
    }

    /** Store-and-forward survives a relay restart because the buffer is persisted. */
    @Test
    fun storedPacketSurvivesRelayRestart() = meshTest { sim ->
        sim.nodes("A", "B", "C")
        sim.link("A", "B")
        advanceUntilIdle()
        sim["A"].sendText("C", "persist me")
        advanceUntilIdle()
        sim.cut("A", "B")
        advanceUntilIdle()

        sim.restart("B")
        sim.link("B", "C")
        advanceUntilIdle()

        assertEquals("persist me", sim["C"].state.value.inbox.single().text)
    }

    /** Expired packets are not delivered, even if a path appears later. */
    @Test
    fun expiredPacketIsNotDelivered() = meshTest { sim ->
        sim.nodes("A", "B", "C")
        sim.link("A", "B")
        advanceUntilIdle()
        sim["A"].sendText("C", "stale soon")
        advanceUntilIdle()

        advanceTimeBy(RouterConfig().messageLifetimeMs + 1)
        sim.tickAll()
        sim.link("B", "C")
        advanceUntilIdle()

        assertTrue(sim["C"].state.value.inbox.isEmpty())
        assertTrue(sim["B"].state.value.stats.expired >= 1)
    }

    /** ACKs stop replication: once delivered, a late-connecting node is not handed the packet. */
    @Test
    fun ackStopsReplication() = meshTest { sim ->
        sim.nodes("A", "B", "C", "X")
        sim.chain("A", "B", "C")
        advanceUntilIdle()
        val p = sim["A"].sendText("C", "done")
        advanceUntilIdle()

        sim.link("B", "X")
        advanceUntilIdle()

        assertNull(sim.node("X").store.snapshot()[p.packetId], "X never received an already-delivered packet")
    }
}
