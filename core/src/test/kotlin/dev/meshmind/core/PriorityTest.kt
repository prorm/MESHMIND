package dev.meshmind.core

import dev.meshmind.core.ai.ClassificationInput
import dev.meshmind.core.ai.RuleBasedPriorityClassifier
import dev.meshmind.core.model.PacketType
import dev.meshmind.core.model.Priority
import dev.meshmind.core.routing.PacketPriorityQueue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PriorityTest {

    /**
     * Test 3: A↔B↔C↔D. A LOW 160 KB transfer (10 × 16 KB chunks) is in progress; a CRITICAL SOS A→D jumps ahead
     * of the queued chunks on every hop, is delivered, and the transfer then completes.
     */
    @Test
    fun sosPreemptsChunkedTransfer() = meshTest(latencyMs = 10, bytesPerSecond = 160_000) { sim ->
        sim.nodes("A", "B", "C", "D")
        sim.chain("A", "B", "C", "D")
        advanceUntilIdle()

        val data = Random(7).nextBytes(160 * 1024)
        val transferId = sim["A"].sendTransfer("D", "photo.jpg", data, Priority.LOW)
        advanceTimeBy(250) // ~2 chunks out of A
        val sos = sim["A"].sendSos("MEDICAL: person collapsed near stage", destination = "D")
        assertEquals(Priority.CRITICAL, sos.priority)
        advanceUntilIdle()

        val dMetrics = sim["D"].state.value.metrics.filter { it.side == "destination" }
        val sosAt = dMetrics.single { it.packetId == sos.packetId }.measuredAt
        val chunkTimes = dMetrics.filter { it.type == PacketType.CHUNK }.map { it.measuredAt }.sorted()
        assertEquals(10, chunkTimes.size, "all chunks eventually delivered")
        val chunksBeforeSos = chunkTimes.count { it < sosAt }
        println("PREEMPTION: SOS delivered at D at ${sosAt}ms (sent at 250ms) after $chunksBeforeSos/10 chunks; transfer finished at ${chunkTimes.last()}ms; " +
            "SOS route=${dMetrics.single { it.packetId == sos.packetId }.route}")
        assertTrue(chunksBeforeSos <= 4, "SOS overtook the queued chunks (only $chunksBeforeSos arrived first)")
        assertTrue(sosAt < chunkTimes.last(), "transfer resumed and finished after the SOS")

        // Reassembled byte-for-byte, and the sender saw every chunk acknowledged.
        assertTrue(data.contentEquals(sim["D"].receivedFile(transferId)))
        val out = sim["A"].state.value.transfers.single { it.transferId == transferId && it.outgoing }
        assertEquals(out.total, out.done)
        assertEquals("SOS", sim["D"].state.value.inbox.single().type.name)
    }

    /**
     * Control for test 3: identical scenario but the urgent message has the SAME priority as the bulk transfer.
     * The per-link queue is then FIFO and the message arrives after every chunk, which shows the preemption above
     * comes from the priority scheduler, not from timing luck.
     */
    @Test
    fun withoutPriorityDifferenceMessageWaitsBehindTransfer() = meshTest(latencyMs = 10, bytesPerSecond = 160_000) { sim ->
        sim.nodes("A", "B", "C", "D")
        sim.chain("A", "B", "C", "D")
        advanceUntilIdle()
        sim["A"].sendTransfer("D", "photo.jpg", Random(7).nextBytes(160 * 1024), Priority.NORMAL)
        advanceTimeBy(250)
        val msg = sim["A"].sendText("D", "ordinary note") // classified NORMAL
        assertEquals(Priority.NORMAL, msg.priority)
        advanceUntilIdle()
        val dMetrics = sim["D"].state.value.metrics.filter { it.side == "destination" }
        val msgAt = dMetrics.single { it.packetId == msg.packetId }.measuredAt
        val chunkTimes = dMetrics.filter { it.type == PacketType.CHUNK }.map { it.measuredAt }
        assertEquals(10, chunkTimes.count { it < msgAt }, "FIFO: all 10 chunks arrive before the same-priority message")
    }

    @Test
    fun queueOrdersByPriorityThenFifo() {
        val q = PacketPriorityQueue()
        q.offer("low1", Priority.LOW.rank)
        q.offer("low2", Priority.LOW.rank)
        q.offer("normal", Priority.NORMAL.rank)
        q.offer("sos", Priority.CRITICAL.rank)
        q.offer("hello", -2, ByteArray(1))
        assertEquals(false, q.offer("sos", Priority.CRITICAL.rank), "no duplicate entries")
        assertEquals(listOf("hello", "sos", "normal", "low1", "low2"), generateSequence { q.poll()?.key }.toList())
    }

    @Test
    fun classifierRules() {
        val c = RuleBasedPriorityClassifier()
        assertEquals(Priority.CRITICAL, c.classify(ClassificationInput(PacketType.TEXT, "hi", sosAction = true)).priority)
        assertEquals(Priority.CRITICAL, c.classify(ClassificationInput(PacketType.SOS, "")).priority)
        assertEquals(Priority.HIGH, c.classify(ClassificationInput(PacketType.TEXT, "Friend is injured and bleeding")).priority)
        assertEquals("COORDINATION", c.classify(ClassificationInput(PacketType.TEXT, "Meet me at Gate C")).label)
        assertEquals(Priority.LOW, c.classify(ClassificationInput(PacketType.TEXT, "x", sizeBytes = 50_000)).priority)
        assertEquals(Priority.LOW, c.classify(ClassificationInput(PacketType.CHUNK)).priority)
    }
}
