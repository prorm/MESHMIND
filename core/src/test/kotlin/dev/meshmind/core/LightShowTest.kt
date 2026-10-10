package dev.meshmind.core

import dev.meshmind.core.light.*
import dev.meshmind.core.model.*
import dev.meshmind.core.routing.InMemoryPacketStore
import dev.meshmind.core.routing.Router
import dev.meshmind.core.transport.SimulatedNetwork
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.math.abs
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class LightShowTest {
    private val offer = LightOffer("show-1", "A", LightPreset.RAINBOW, 110_000, 30_000, 8_000)

    @Test fun protocolRoundTripsAllControlsAndRejectsMalformedInput() {
        val messages = listOf(LightMessage.Offer(offer), LightMessage.Stop("show-1"), LightMessage.Discover,
            LightMessage.Ping("show-1", "token", 1), LightMessage.Pong("show-1", "token", 1, 2, 3), LightMessage.Ready("show-1"))
        messages.forEach { assertEquals(it, LightCodec.decode(LightCodec.encode(it))) }
        assertFails { LightCodec.decode(byteArrayOf(2, 99)) }
        assertFails { LightCodec.decode(byteArrayOf(2, 3, 0)) }
        assertFails { LightCodec.decode(ByteArray(513)) }
        assertFails { LightCodec.decode(byteArrayOf(1, 1)) }
        assertFails { offer.copy(periodMs = 0) }
        assertFails { offer.copy(durationMs = Long.MAX_VALUE) }
    }

    @Test fun fourTimestampSyncCompensatesForHostProcessingAndClockDifference() {
        val result = ClockSample.estimate(10_000, 910_050, 910_150, 10_200)!!
        assertEquals(900_000, result.offsetMs)
        assertEquals(100, result.roundTripMs)
        assertNull(ClockSample.estimate(100, 200, 190, 300))
        assertNull(ClockSample.estimate(100, 200, 400, 200))
        assertNull(ClockSample.estimate(0, 900_050, 900_050, 4_000))
    }

    @Test fun presetsRepeatWithoutDependingOnFrameCount() {
        LightPreset.entries.forEach { preset ->
            assertEquals(preset.colorAt(2345, 8000), preset.colorAt(18345, 8000))
            assertNotEquals(preset.colorAt(0, 8000), preset.colorAt(4000, 8000))
        }
    }

    private class Rig(val test: TestScope) {
        val scope = CoroutineScope(StandardTestDispatcher(test.testScheduler) + SupervisorJob())
        val net = SimulatedNetwork(30)
        val stores = listOf("A", "B", "C").associateWith { InMemoryPacketStore() }
        val clocks = mapOf("A" to 100_000L, "B" to 500_000L, "C" to 900_000L)
        val wallClocks = mapOf("A" to 1_000_000L, "B" to 3_000_000L, "C" to 7_000_000L)
        val routers = stores.mapValues { (id, store) -> Router(id, net.transport(id), store, scope,
            clock = { test.testScheduler.currentTime + wallClocks.getValue(id) },
            monotonicClock = { mono(id) }) }
        val lights = routers.mapValues { (id, router) -> LightShowController(router, scope) { mono(id) } }
        fun mono(id: String) = test.testScheduler.currentTime + clocks.getValue(id)
        suspend fun start() {
            routers.values.forEach { it.start() }
            net.connect("A", "B"); net.connect("B", "C")
            test.advanceTimeBy(300); test.runCurrent()
        }
        suspend fun settle(ms: Long = 1000) { test.advanceTimeBy(ms); test.runCurrent() }
        suspend fun hostAndJoin() {
            lights.getValue("A").host(LightPreset.RAINBOW, 30_000)
            settle()
            lights.getValue("C").join(lights.getValue("C").state.value.offers.single().showId)
            settle(3000)
        }
        fun close() { scope.cancel() }
    }

    private fun scenario(block: suspend Rig.() -> Unit) = runTest {
        val rig = Rig(this)
        try { rig.start(); rig.block() } finally { rig.close() }
    }

    @Test fun twoHopSyncHandlesDifferentBootAndWallClocksWithoutPlayingOnRelays() = scenario {
        hostAndJoin()
        val host = lights.getValue("A").state.value
        val guest = lights.getValue("C").state.value
        assertTrue(abs(guest.clock!!.offsetMs + 800_000) <= 5)
        assertTrue(abs(host.positionAt(mono("A"))!! - guest.positionAt(mono("C"))!!) <= 5)
        assertNull(lights.getValue("B").state.value.show, "relaying is not consent to playback")
        assertEquals(1, host.readyCount)
        stores.values.forEach { assertTrue(it.snapshot().isEmpty(), "light controls never persist") }
        routers.values.forEach { assertTrue(it.state.value.inbox.isEmpty()) }
    }

    @Test fun lateJoinUsesCurrentPhaseAndLeaveDoesNotAutomaticallyRejoin() = scenario {
        lights.getValue("A").host(LightPreset.SUNSET, 30_000)
        settle(18_000)
        val guest = lights.getValue("C")
        assertNull(guest.state.value.show)
        guest.join(guest.state.value.offers.single().showId)
        settle(2000)
        assertTrue(guest.state.value.positionAt(mono("C"))!! > 9_000, "late join doesn't restart the show")
        guest.leave(); settle(4000)
        assertNull(guest.state.value.show)
        assertTrue(guest.state.value.offers.isNotEmpty())
    }

    @Test fun hostStopClearsPlaybackAndCannotBeUndoneByDelayedAdvertisement() = scenario {
        hostAndJoin()
        val old = lights.getValue("A").state.value.show!!
        lights.getValue("A").leave(); settle()
        assertNull(lights.getValue("C").state.value.show)
        routers.getValue("A").sendLightControl(Addresses.BROADCAST, LightCodec.encode(LightMessage.Offer(old)))
        settle()
        assertTrue(lights.getValue("C").state.value.offers.isEmpty())
    }

    @Test fun stopBeforeOfferIsRememberedAndNonHostCannotStopJoinedShow() = scenario {
        routers.getValue("A").sendLightControl(Addresses.BROADCAST, LightCodec.encode(LightMessage.Stop(offer.showId)), true)
        settle()
        routers.getValue("A").sendLightControl(Addresses.BROADCAST, LightCodec.encode(LightMessage.Offer(offer)))
        settle()
        assertTrue(lights.getValue("C").state.value.offers.isEmpty())
        hostAndJoin()
        val show = lights.getValue("C").state.value.show!!
        routers.getValue("B").sendLightControl(Addresses.BROADCAST, LightCodec.encode(LightMessage.Stop(show.showId)), true)
        settle()
        assertEquals(show, lights.getValue("C").state.value.show)
    }

    @Test fun bufferedShowFinishesAfterDisconnectAndNeverRunsWithoutClockSync() = scenario {
        hostAndJoin()
        net.disconnect("B", "C")
        settle(12_000)
        assertTrue(lights.getValue("C").state.value.positionAt(mono("C"))!! > 0)
        settle(30_000)
        assertNull(lights.getValue("C").state.value.show)
        assertEquals("Show finished. Choose another show or host your own.", lights.getValue("C").state.value.note)
        net.connect("B", "C"); settle()
        lights.getValue("A").host(LightPreset.PULSE)
        settle()
        val show = lights.getValue("C").state.value.offers.single()
        net.disconnect("B", "C")
        lights.getValue("C").join(show.showId)
        settle(16_000)
        assertNull(lights.getValue("C").state.value.positionAt(mono("C")))
        assertNull(lights.getValue("C").state.value.show)
        assertTrue(lights.getValue("C").state.value.note.startsWith("Could not synchronize"))
    }

    @Test fun sosStillPreemptsBulkWhileLightControlsAreActive() = scenario {
        hostAndJoin()
        lights.getValue("A").program(listOf(LightCue(0, LightScene(effect = LightEffect.SPIRAL))))
        routers.getValue("A").sendTransfer("C", "load.bin", ByteArray(1024 * 1024), Priority.LOW)
        settle(300)
        val sos = routers.getValue("A").sendSos("MEDICAL SOS", "C")
        settle(25_000)
        val metrics = routers.getValue("C").state.value.metrics
        val delivered = metrics.single { it.packetId == sos.packetId }
        assertEquals(Priority.CRITICAL, delivered.priority)
        assertTrue(delivered.measuredAt < metrics.filter { it.type == PacketType.CHUNK }.maxOf { it.measuredAt })
    }

    @Test fun scoreIsScheduledAcrossHopsAndLateJoinRecoversTheActiveProgram() = scenario {
        lights.getValue("A").host(LightPreset.RAINBOW, 60_000)
        val program = lights.getValue("A").program(listOf(LightCue(0, LightScene(effect = LightEffect.RIPPLE))))
        settle(16_000)
        val guest = lights.getValue("C")
        guest.join(guest.state.value.offers.single().showId); settle(3000)
        assertEquals(program, guest.state.value.programs.single())
        lights.getValue("A").assign("C", LightSeat(2, 100, 750)); settle()
        assertEquals(LightSeat(2, 100, 750), guest.state.value.seat)
        assertEquals(2, lights.getValue("A").state.value.participants.single().seat.group)
        assertEquals(program.revision, lights.getValue("A").state.value.participants.single().revision)
    }

    @Test fun blackoutSupersedesPendingCueAndRestoresWithoutStaleRevisionRevival() = scenario {
        hostAndJoin(); settle(7000)
        val host = lights.getValue("A"); val guest = lights.getValue("C")
        val original = host.program(listOf(LightCue(0, LightScene(effect = LightEffect.WAVE))))
        assertFailsWith<IllegalArgumentException> { host.program(listOf(LightCue(0, LightScene()))) }
        host.blackout(); settle(4000)
        assertEquals(0xFF000000.toInt(), guest.state.value.colorAt(mono("C"), "C"))
        routers.getValue("A").sendLightControl(Addresses.BROADCAST, LightCodec.encode(LightMessage.Program(original))); settle()
        assertEquals(0xFF000000.toInt(), guest.state.value.colorAt(mono("C"), "C"))
        host.blackout(); settle(3000); host.restore(); settle(4500)
        assertNotEquals(0xFF000000.toInt(), guest.state.value.colorAt(mono("C"), "C"))
    }

    @Test fun programCannotBeInjectedByNonHostAndGroupsControlActualPlayback() = scenario {
        hostAndJoin(); settle(7000)
        val show = lights.getValue("A").state.value.show!!
        val forged = LightProgram(show.showId, 500, mono("A"), listOf(LightCue(0, LightScene(primary = 0xFF0000))))
        routers.getValue("B").sendLightControl(Addresses.BROADCAST, LightCodec.encode(LightMessage.Program(forged)))
        settle(); assertTrue(lights.getValue("C").state.value.programs.isEmpty())
        lights.getValue("A").program(listOf(LightCue(0, LightScene(effect = LightEffect.SOLID, primary = 0xFF8066, groups = 4))))
        settle(4500)
        assertEquals(0xFF000000.toInt(), lights.getValue("C").state.value.colorAt(mono("C"), "C"))
        lights.getValue("A").assign("C", LightSeat(group = 2)); settle()
        assertNotEquals(0xFF000000.toInt(), lights.getValue("C").state.value.colorAt(mono("C"), "C"))
        lights.getValue("C").leave(); settle()
        assertTrue(lights.getValue("A").state.value.participants.isEmpty())
    }
}
