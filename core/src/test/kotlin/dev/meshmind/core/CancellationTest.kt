package dev.meshmind.core

import dev.meshmind.core.model.PacketState
import dev.meshmind.core.model.Priority
import dev.meshmind.core.model.StoredPacket
import dev.meshmind.core.routing.InMemoryPacketStore
import dev.meshmind.core.routing.PacketStore
import dev.meshmind.core.routing.Router
import dev.meshmind.core.transport.SimulatedNetwork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Like Room: every write suspends. */
private class SuspendingStore(private val inner: InMemoryPacketStore = InMemoryPacketStore()) : PacketStore by inner {
    override suspend fun upsert(packet: StoredPacket) { delay(5); inner.upsert(packet) }
    override suspend fun updateState(packetId: String, state: PacketState, at: Long) { delay(1); inner.updateState(packetId, state, at) }
}

@OptIn(ExperimentalCoroutinesApi::class)
class CancellationTest {
    /**
     * Regression (found on phones): the UI started a transfer from a screen-scoped coroutine, the user navigated away,
     * and cancellation stopped sendTransfer after 62 of 64 chunks, leaving a transfer that could never complete.
     */
    @Test
    fun cancellingTheCallerDoesNotTruncateATransfer() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        val net = SimulatedNetwork(latencyMs = 5, bytesPerSecond = 1_000_000)
        val clock = { testScheduler.currentTime }
        val a = Router("A", net.transport("A"), SuspendingStore(), scope, clock).also { it.start() }
        val b = Router("B", net.transport("B"), SuspendingStore(), scope, clock).also { it.start() }
        net.connect("A", "B")
        advanceUntilIdle()

        val data = Random(3).nextBytes(64 * 16 * 1024)
        val caller = launch { a.sendTransfer("B", "big.bin", data, Priority.LOW) }
        advanceTimeBy(40) // part-way through creating chunks
        caller.cancel() // user left the screen
        advanceUntilIdle()

        val outgoing = a.state.value.transfers.single { it.outgoing }
        assertEquals(64, outgoing.total)
        assertEquals(64, outgoing.done, "every chunk was created, sent and acknowledged")
        val incoming = b.state.value.transfers.single { !it.outgoing }
        assertTrue(incoming.completedAt != null)
        assertTrue(data.contentEquals(b.receivedFile(incoming.transferId)))
        scope.cancel()
    }
}
