package dev.meshmind.core

import dev.meshmind.core.model.PacketType
import dev.meshmind.core.model.Priority
import dev.meshmind.core.routing.InMemoryPacketStore
import dev.meshmind.core.routing.Router
import dev.meshmind.core.routing.RouterConfig
import dev.meshmind.core.transport.SimulatedNetwork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Found on two real phones: Nearby reports SUCCESS before the bytes leave, so chunks pile up inside Nearby where the
 * router's priority queue can't reorder them; an SOS then waited ~1.7 s behind already-buffered chunks.
 * Model: SimulatedNetwork with early success, OnePlus↔S24-like throughput (~94 KB/s), 1 MB LOW transfer, SOS at 4 s.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FlowControlTest {
    private data class Outcome(val sosLatencyMs: Long, val chunksAfterSos: Int, val complete: Boolean)

    private suspend fun TestScope.run(window: Int): Outcome {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        val net = SimulatedNetwork(latencyMs = 20, bytesPerSecond = 94_000, earlySuccessScope = scope)
        val clock = { testScheduler.currentTime }
        val cfg = RouterConfig(bulkWindow = window)
        val a = Router("A", net.transport("A"), InMemoryPacketStore(), scope, clock, config = cfg).also { it.start() }
        val b = Router("B", net.transport("B"), InMemoryPacketStore(), scope, clock, config = cfg).also { it.start() }
        net.connect("A", "B")
        advanceUntilIdle()
        val tid = a.sendTransfer("B", "big.bin", Random(5).nextBytes(64 * 16 * 1024), Priority.LOW)
        advanceTimeBy(4_000)
        val sos = a.sendSos("MEDICAL", "B")
        advanceUntilIdle()
        val m = b.state.value.metrics.filter { it.side == "destination" }
        val sosM = m.single { it.packetId == sos.packetId }
        val after = m.count { it.type == PacketType.CHUNK && it.measuredAt > sosM.measuredAt }
        val complete = b.state.value.transfers.single { it.transferId == tid }.completedAt != null
        scope.cancel()
        return Outcome(sosM.oneWayMs, after, complete)
    }

    @Test
    fun bulkWindowKeepsSosFastWhenTransportBuffers() = runTest {
        val unbounded = run(window = Int.MAX_VALUE)
        val windowed = run(window = 2)
        println("FLOW CONTROL: no window -> SOS ${unbounded.sosLatencyMs} ms, ${unbounded.chunksAfterSos} chunks after it; " +
            "window=2 -> SOS ${windowed.sosLatencyMs} ms, ${windowed.chunksAfterSos} chunks after it")
        assertTrue(unbounded.sosLatencyMs > 5_000, "without flow control the SOS queues behind the transport buffer")
        assertTrue(windowed.sosLatencyMs < 700, "with the window the SOS waits for at most ~2 chunks")
        assertTrue(windowed.chunksAfterSos >= 30)
        assertTrue(windowed.complete && unbounded.complete, "both transfers still complete")
    }
}
