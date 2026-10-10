package dev.meshmind.core

import dev.meshmind.core.model.InetKind
import dev.meshmind.core.model.InetRequest
import dev.meshmind.core.model.InetStatus
import dev.meshmind.core.model.PacketType
import dev.meshmind.core.model.Priority
import dev.meshmind.core.routing.TrackStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The final demo, end to end, on A ●──● B ●──● C ●──● D(gateway), with Bluetooth-like links
 * (40 ms per frame + 64 KB/s). Prints a metrics table. These are SIMULATED numbers, not phone measurements.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SuccessCriteriaTest {
    @Test
    fun finalDemoScenario() = meshTest(latencyMs = 40, bytesPerSecond = 64_000) { sim ->
        val rows = mutableListOf<String>()
        sim.nodes("A", "B", "C", "D")
        sim.node("D").isGateway = true
        sim.chain("A", "B", "C", "D")
        advanceUntilIdle()
        sim["D"].emitBeacon()
        advanceUntilIdle()

        // 1. A → C text
        val t1 = sim["A"].sendText("C", "Meet me at Gate C")
        advanceUntilIdle()
        val tr1 = sim["A"].state.value.tracked.single { it.packetId == t1.packetId }
        assertEquals(TrackStatus.DELIVERED, tr1.status)
        assertEquals(listOf("A", "B", "C"), tr1.deliveredRoute)
        rows += row("1 text A→C", tr1.deliveredRoute.joinToString("→"), tr1.hopCount, tr1.oneWayMs, tr1.rttMs)

        // 2. C disconnects; A sends again; B stores; C returns 30 s later.
        sim.cut("B", "C")
        advanceUntilIdle()
        val t2 = sim["A"].sendText("C", "Meet me at Gate C (again)")
        advanceUntilIdle()
        assertTrue(sim["C"].state.value.inbox.size == 1)
        advanceTimeBy(30_000)
        sim.link("B", "C")
        advanceUntilIdle()
        val tr2 = sim["A"].state.value.tracked.single { it.packetId == t2.packetId }
        assertEquals(TrackStatus.DELIVERED, tr2.status)
        assertEquals(2, sim["C"].state.value.inbox.size)
        rows += row("2 store&fwd (C away 30 s)", tr2.deliveredRoute.joinToString("→"), tr2.hopCount, tr2.oneWayMs, tr2.rttMs)

        // 3. Internet via mesh
        val req = sim["A"].sendInternetRequest(InetRequest(InetKind.HTTP_GET, "https://example.com/", ""))
        advanceUntilIdle()
        val r = sim["A"].state.value.inet.single { it.requestId == req.packetId }
        val resp = assertNotNull(r.response)
        assertEquals(InetStatus.OK, resp.status)
        rows += row(
            "3 GET via gateway D", "${resp.requestRoute.joinToString("→")}→🌐→${r.responseRoute.joinToString("→")}",
            resp.requestRoute.size - 1 + r.responseRoute.size - 1, null, r.completedAt!! - r.sentAt,
        )

        // 4. LOW 256 KB transfer A→D, SOS mid-transfer.
        val start = testScheduler.currentTime
        sim["A"].sendTransfer("D", "photo.jpg", Random(1).nextBytes(256 * 1024), Priority.LOW)
        advanceTimeBy(1_500)
        val sos = sim["A"].sendSos("MEDICAL: person collapsed near stage", "D")
        advanceUntilIdle()
        val d = sim["D"].state.value.metrics.filter { it.side == "destination" }
        val sosM = d.single { it.packetId == sos.packetId }
        val chunks = d.filter { it.type == PacketType.CHUNK && it.measuredAt >= start }.map { it.measuredAt }.sorted()
        assertEquals(16, chunks.size)
        val before = chunks.count { it < sosM.measuredAt }
        assertTrue(before < 16)
        val trSos = sim["A"].state.value.tracked.single { it.packetId == sos.packetId }
        rows += row("4 SOS during 256 KB LOW", sosM.route.joinToString("→"), sosM.hops, sosM.oneWayMs, trSos.rttMs)
        rows += "   transfer: 16 chunks; $before arrived before the SOS, ${16 - before} after; transfer took ${chunks.last() - start} ms"

        println("\n=== Final demo in simulation (40 ms/frame, 64 KB/s per link) ===")
        println(String.format(java.util.Locale.US, "%-28s %-34s %-5s %-9s %s", "scenario", "route", "hops", "one-way", "rtt/round-trip"))
        rows.forEach { println(it) }
    }

    private fun row(name: String, route: String, hops: Int, oneWay: Long?, rtt: Long?) =
        String.format(java.util.Locale.US, "%-28s %-34s %-5d %-9s %s", name, route, hops, oneWay?.let { "$it ms" } ?: "-", rtt?.let { "$it ms" } ?: "-")
}
