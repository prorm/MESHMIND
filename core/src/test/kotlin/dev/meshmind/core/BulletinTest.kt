package dev.meshmind.core

import dev.meshmind.core.gateway.FakeAnswerService
import dev.meshmind.core.gateway.LocalAuthority
import dev.meshmind.core.model.PacketType
import dev.meshmind.core.security.SoftwareSigner
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Live info board: one gateway fetch, delivered to every phone, never forgeable. */
@OptIn(ExperimentalCoroutinesApi::class)
class BulletinTest {

    @Test
    fun officialBoardReachesEveryoneIncludingLateJoinersAndNewerReplacesOlder() {
        val authority = LocalAuthority(SoftwareSigner.generate())
        val feed = FakeAnswerService(authority, { 0L })
        meshTest(authority = authority) { sim ->
            sim.nodes("G", "A", "B", "C")
            sim.node("G").isGateway = true
            sim.chain("G", "A", "B")
            advanceUntilIdle()
            assertTrue(sim["G"].broadcastBulletin(feed.demoBulletin(1)))
            assertFalse(sim["G"].broadcastBulletin(feed.demoBulletin(1)), "same seq is not re-broadcast")
            advanceUntilIdle()
            for (n in listOf("A", "B")) {
                val b = sim[n].state.value.bulletins.single()
                assertTrue(b.official)
                assertEquals(2, b.bulletin.items.size)
            }
            // Bulletins need no ACKs (no reply storm from a whole crowd).
            assertTrue(sim["G"].state.value.metrics.none { it.type == PacketType.BULLETIN && it.side == "source" })

            sim.link("B", "C") // late joiner gets it through store-and-forward
            advanceUntilIdle()
            assertEquals(1, sim["C"].state.value.bulletins.single().bulletin.seq)

            sim["G"].broadcastBulletin(feed.demoBulletin(2))
            advanceUntilIdle()
            assertEquals(2, sim["C"].state.value.bulletins.single().bulletin.seq)
        }
    }
}
