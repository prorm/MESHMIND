package dev.meshmind.core

import dev.meshmind.core.gateway.AnswerCache
import dev.meshmind.core.gateway.FakeAnswerService
import dev.meshmind.core.gateway.LocalAuthority
import dev.meshmind.core.model.InetResponse
import dev.meshmind.core.model.InetStatus
import dev.meshmind.core.routing.GatewayTable
import dev.meshmind.core.security.SoftwareSigner
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** "Ask": questions answered through the mesh, signed by the authority, cached along the way. */
@OptIn(ExperimentalCoroutinesApi::class)
class AskTest {
    private val authority = LocalAuthority(SoftwareSigner.generate())
    private lateinit var service: FakeAnswerService

    private fun askTest(answerAuthority: LocalAuthority = authority, block: suspend kotlinx.coroutines.test.TestScope.(dev.meshmind.core.sim.MeshSimulation) -> Unit) =
        meshTest(authority = authority, answers = { _ ->
            FakeAnswerService(answerAuthority, { 0L }) { q -> "Metro Line 2 is running every 8 minutes. (re: $q)" }
                .also { service = it }
        }, block = block)

    @Test
    fun questionIsAnsweredVerifiedAndExplained() = askTest { sim ->
        sim.nodes("A", "B", "G")
        sim.node("G").isGateway = true
        sim.chain("A", "B", "G")
        advanceUntilIdle()
        sim["G"].emitBeacon()
        advanceUntilIdle()

        val id = sim["A"].sendAsk("Is the metro running?")
        advanceUntilIdle()
        val r = sim["A"].state.value.inet.single { it.requestId == id }
        val a = assertNotNull(r.answer)
        assertTrue(r.answerVerified)
        assertTrue(a.text.startsWith("Metro Line 2"))
        assertEquals(InetResponse.SERVED_UPSTREAM, r.servedFrom)
        assertEquals("Transport", r.response!!.needCategory)
        assertTrue(r.response!!.need in 1..100)
        assertEquals(listOf("A", "B", "G"), r.response!!.requestRoute)
        assertEquals(1, service.calls.size)
    }

    @Test
    fun repeatedQuestionsNeverReachTheInternetTwice() = askTest { sim ->
        sim.nodes("A", "B", "G", "D", "E")
        sim.node("G").isGateway = true
        sim.chain("A", "B", "G")
        sim.link("D", "B")
        advanceUntilIdle()
        sim["G"].emitBeacon()
        advanceUntilIdle()

        sim["A"].sendAsk("Is the metro running?")
        advanceUntilIdle()
        assertEquals(1, service.calls.size)

        // D's request passes B, which carried the first answer: B replies itself.
        val d = sim["D"].sendAsk("metro running now")
        advanceUntilIdle()
        val rd = sim["D"].state.value.inet.single { it.requestId == d }
        assertEquals(InetResponse.SERVED_RELAY_CACHE, rd.servedFrom)
        assertTrue(rd.answerVerified)
        assertEquals(1L, sim["B"].state.value.relayCacheAnswers)

        // E arrives later, next to the gateway: the gateway's own cache answers, no upstream call, still need-scored.
        sim.link("E", "G")
        advanceUntilIdle()
        val e = sim["E"].sendAsk("is metro running?")
        advanceUntilIdle()
        val re = sim["E"].state.value.inet.single { it.requestId == e }
        assertEquals(InetResponse.SERVED_GATEWAY_CACHE, re.servedFrom)
        assertEquals("Transport", re.response!!.needCategory)

        // A asks again: answered on the phone itself.
        val again = sim["A"].sendAsk("IS THE METRO RUNNING")
        val ra = sim["A"].state.value.inet.single { it.requestId == again }
        assertEquals(InetResponse.SERVED_LOCAL_CACHE, ra.servedFrom)

        assertEquals(1, service.calls.size, "four askers, one Internet call")
        assertEquals(1L, sim["G"].state.value.gatewayQueue!!.fromCache)
    }

    @Test
    fun answerNotSignedByTheAuthorityIsShownUnverifiedAndNeverCached() {
        val rogue = LocalAuthority(SoftwareSigner.generate())
        askTest(answerAuthority = rogue) { sim ->
            sim.nodes("A", "B", "G")
            sim.node("G").isGateway = true
            sim.chain("A", "B", "G")
            advanceUntilIdle()
            sim["G"].emitBeacon()
            advanceUntilIdle()
            val id = sim["A"].sendAsk("Is the metro running?")
            advanceUntilIdle()
            val r = sim["A"].state.value.inet.single { it.requestId == id }
            assertEquals(InetStatus.OK, r.response!!.status)
            assertNotNull(r.answer)
            assertFalse(r.answerVerified)
            for (n in listOf("A", "B", "G")) assertEquals(0, sim[n].state.value.answerCacheSize, "$n cached an unverified answer")
        }
    }

    @Test
    fun readReturnsSignedPlainText() = askTest { sim ->
        sim.nodes("A", "G")
        sim.node("G").isGateway = true
        sim.link("A", "G")
        advanceUntilIdle()
        sim["G"].emitBeacon()
        advanceUntilIdle()
        val id = sim["A"].sendRead("https://en.wikipedia.org/wiki/Mesh_networking")
        val bad = sim["A"].sendRead("http://insecure.example/")
        advanceUntilIdle()
        val r = sim["A"].state.value.inet.single { it.requestId == id }
        assertTrue(r.answerVerified)
        assertTrue(r.answer!!.text.startsWith("Readable text"))
        assertEquals(InetStatus.REJECTED, sim["A"].state.value.inet.single { it.requestId == bad }.response!!.status)
    }

    @Test
    fun normalisationAndGatewayChoice() {
        assertEquals(AnswerCache.normalize("Is the metro running?"), AnswerCache.normalize("metro RUNNING now!!"))
        assertTrue(AnswerCache.normalize("nearest hospital") != AnswerCache.normalize("nearest police"))
        val t = GatewayTable()
        t.update("BUSY", 1, "BUSY", 0, verified = true, load = 1.0)
        t.update("CALM", 2, "X", 0, verified = true, load = 0.0)
        t.update("ROGUE", 1, "ROGUE", 0, verified = false, load = 0.0)
        assertEquals("CALM", t.best(1)!!.gatewayId, "a full nearby gateway loses to a calm one a hop further; unverified last")
    }
}
