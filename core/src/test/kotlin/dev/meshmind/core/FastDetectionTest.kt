package dev.meshmind.core

import dev.meshmind.core.model.AckPayload
import dev.meshmind.core.model.Frame
import dev.meshmind.core.model.Packet
import dev.meshmind.core.model.PacketState
import dev.meshmind.core.model.PacketType
import dev.meshmind.core.model.Priority
import dev.meshmind.core.model.StoredPacket
import dev.meshmind.core.model.WireCodec
import dev.meshmind.core.sim.MeshSimulation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Found in the 3-phone session physical_20261006_01: links formed ~15-25 s after start, yet A learned the gateway two
 * hops away only after ~71 s. The relay's link to A was busy with thousands of stale ACKs that C had restored from
 * earlier runs (ACKs are never acknowledged, so C kept replicating them for their 30 min lifetime), and the gateway
 * beacon (NORMAL) queued behind them (HIGH). Beacons #2-#4 never got through; #5 did, at 71 s.
 *
 * Model: the same A↔B↔C chain, C the gateway, C holding a backlog of old ACKs for A that A already has,
 * the app's 20 s beacon and 10 s housekeeping timers, phone-like ~15 ms per frame.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FastDetectionTest {

    private val backlog = 1_500

    /** Seeds C with [backlog] ACKs to A that A received long ago, then restarts both (like a cold app start). */
    private suspend fun MeshSimulation.seedStaleAcks(now: Long) {
        val a = node("A").store
        val c = node("C").store
        repeat(backlog) {
            val original = Packet.newId()
            val ack = AckPayload(original, PacketType.CHUNK, now - 300_000, 2, listOf("A", "B", "C"), false)
            val p = Packet(
                Packet.newId(), "C", "A", PacketType.ACK, Priority.HIGH, now - 300_000, now + 25 * 60_000, 8, 0,
                WireCodec.encodeAck(ack), listOf("C"), "CONTROL",
            )
            c.upsert(StoredPacket(p, PacketState.QUEUED, now - 300_000, origin = true))
            a.upsert(StoredPacket(p.copy(hopCount = 2, routeHistory = listOf("C", "B", "A")), PacketState.DELIVERED, now - 299_000, origin = false))
            a.addAcked(original, now - 299_000)
        }
        restart("A"); restart("C")
    }

    /** The app's timers (MeshRuntime): gateway beacon every 20 s, housekeeping tick every 10 s. */
    private fun TestScope.appTimers(sim: MeshSimulation) {
        backgroundScope.launch { while (true) { if (sim.node("C").isGateway) sim["C"].emitBeacon(); delay(20_000) } }
        backgroundScope.launch { while (true) { delay(10_000); sim.tickAll() } }
    }

    private suspend fun TestScope.waitFor(limitMs: Long, cond: () -> Boolean): Long? {
        val start = currentTime
        while (currentTime - start <= limitMs) {
            if (cond()) return currentTime - start
            advanceTimeBy(50)
        }
        return null
    }

    @Test
    fun gatewayIsLearnedWithinASecondOfTheLinkDespiteStaleBacklog() = meshTest(latencyMs = 15, bytesPerSecond = 100_000) { sim ->
        sim.nodes("A", "B", "C")
        sim.node("C").isGateway = true
        sim.seedStaleAcks(currentTime)
        appTimers(sim)

        sim.link("B", "C")           // C's backlog starts flowing into B
        advanceTimeBy(8_000)
        sim.link("A", "B")           // A joins later, like the phones
        val learned = waitFor(120_000) { sim["A"].state.value.nearestGateway?.gatewayId == "C" }
        println("FAST DETECTION: A learned gateway C ${learned}ms after the A-B link (backlog $backlog stale ACKs)")
        assertTrue(learned != null && learned <= 1_000, "A must learn the 2-hop gateway within 1 s of linking, took $learned ms")
        assertEquals(2, sim["A"].state.value.nearestGateway!!.hopDistance)
    }

    @Test
    fun freshMessageIsNotStuckBehindStaleAcks() = meshTest(latencyMs = 15, bytesPerSecond = 100_000) { sim ->
        sim.nodes("A", "B", "C")
        sim.seedStaleAcks(currentTime)
        appTimers(sim)
        sim.chain("A", "B", "C")
        advanceTimeBy(2_000)

        val text = sim["C"].sendText("A", "Meet me at Gate C")
        val took = waitFor(120_000) { sim["A"].state.value.inbox.any { it.packetId == text.packetId } }
        println("FAST DETECTION: fresh C->A text delivered after ${took}ms behind a stale backlog of $backlog ACKs")
        assertTrue(took != null && took <= 1_500, "a fresh message must not wait behind old ACKs, took $took ms")
    }

    /** Warm restart after heavy bulk history: finished chunks load as IDs only, yet are still recognised as duplicates. */
    @Test
    fun restartKeepsFinishedBulkOutOfMemoryButStillRejectsDuplicates() = meshTest { sim ->
        sim.nodes("A", "B")
        sim.link("A", "B")
        advanceTimeBy(1_000)
        val tid = sim["A"].sendTransfer("B", "photo.jpg", ByteArray(8 * 16 * 1024) { it.toByte() })
        advanceTimeBy(30_000)
        assertTrue(sim["B"].state.value.transfers.single { it.transferId == tid }.completedAt != null)

        val load = sim.node("B").store.loadStartup()
        assertTrue(load.packets.none { it.packet.type == PacketType.CHUNK }, "finished chunks are not loaded with payloads")
        assertEquals(8, load.finishedIds.count { id -> sim.node("B").store.snapshot()[id]?.packet?.type == PacketType.CHUNK })

        val chunk = sim.node("B").store.snapshot().values.first { it.packet.type == PacketType.CHUNK }.packet
        sim.cut("A", "B")
        sim.restart("B")
        sim.link("A", "B")
        advanceTimeBy(2_000)
        val before = sim["B"].state.value.stats.deliveredToMe
        // Re-inject an old chunk as if a relay re-sent it: B must not deliver it twice.
        sim.network.transport("A").send(sim.network.endpointOf("A", "B")!!, WireCodec.encode(Frame.Data(chunk)))
        advanceTimeBy(2_000)
        assertEquals(before, sim["B"].state.value.stats.deliveredToMe)
    }

    @Test
    fun sosStillBeatsBeacons()= meshTest(latencyMs = 15, bytesPerSecond = 100_000) { sim ->
        sim.nodes("A", "B", "C")
        sim.node("C").isGateway = true
        sim.seedStaleAcks(currentTime)
        sim.chain("A", "B", "C")
        advanceTimeBy(500)
        val sos = sim["C"].sendSos("MEDICAL", "A")
        sim["C"].emitBeacon()
        val took = waitFor(10_000) { sim["A"].state.value.inbox.any { it.packetId == sos.packetId } }
        println("FAST DETECTION: SOS C->A amid beacons + backlog delivered after ${took}ms")
        assertTrue(took != null && took <= 500, "SOS keeps absolute priority, took $took ms")
    }
}
