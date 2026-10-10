package dev.meshmind.core

import dev.meshmind.core.gateway.GatewayScheduler
import dev.meshmind.core.gateway.NeedCategory
import dev.meshmind.core.gateway.NeedContext
import dev.meshmind.core.gateway.RuleBasedNeedScorer
import dev.meshmind.core.gateway.SchedulerConfig
import dev.meshmind.core.model.InetKind
import dev.meshmind.core.model.InetResponse
import dev.meshmind.core.model.InetStatus
import dev.meshmind.core.security.Role
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The gateway's need-aware fair share: who goes first when one Internet link serves a crowd. */
@OptIn(ExperimentalCoroutinesApi::class)
class SchedulerTest {

    private class Rig(val test: TestScope, cfg: SchedulerConfig, val workMs: Long = 1_000) {
        val order = mutableListOf<String>()
        val results = LinkedHashMap<String, InetResponse>()
        // Own scope on the test scheduler (not backgroundScope): advanceUntilIdle must drive the workers' delays.
        val scope = CoroutineScope(StandardTestDispatcher(test.testScheduler) + SupervisorJob())
        val sched = GatewayScheduler(scope, { test.testScheduler.currentTime }, RuleBasedNeedScorer(), { cfg })

        fun ask(id: String, requester: String, text: String, kind: InetKind = InetKind.ASK, sos: Boolean = false,
                verified: Boolean = true, role: Role = Role.USER, sosAgoMs: Long? = null) {
            val ctx = NeedContext(requester, kind, text, -1, verified, role, sosAgoMs, 0, 0)
            sched.submit(id, ctx, sos, execute = {
                order += id
                delay(workMs)
                InetResponse(id, InetStatus.OK, 200, "text/plain", ByteArray(0), false, "ok", "G", emptyList(), 0)
            }, deliver = { results[id] = it })
        }
    }

    private fun rig(cfg: SchedulerConfig, workMs: Long = 1_000, block: suspend TestScope.(Rig) -> Unit) = runTest {
        val r = Rig(this, cfg, workMs)
        try { block(r) } finally { r.scope.cancel() }
    }

    @Test
    fun mostNeededGoesFirstAndEveryoneIsAnswered() = rig(SchedulerConfig(maxConcurrent = 1)) { r ->
        r.ask("busy", "Z", "weather today")          // occupies the only slot
        runCurrent()
        r.ask("leisure", "A", "cricket score")
        r.ask("info", "B", "history of this stadium")
        r.ask("transport", "C", "is the metro running")
        r.ask("health", "D", "nearest hospital")
        r.ask("emergency", "E", "my friend is unconscious and not breathing")
        advanceUntilIdle()
        assertEquals(listOf("busy", "emergency", "health", "transport", "info", "leisure"), r.order)
        assertEquals(6, r.results.size)
        val e = r.results.getValue("emergency")
        assertEquals(NeedCategory.EMERGENCY.label, e.needCategory)
        assertTrue(e.need >= 90 && e.needReasons.any { it.startsWith("about: emergency") }, e.needReasons.toString())
        assertTrue(r.results.getValue("leisure").queueWaitMs > r.results.getValue("emergency").queueWaitMs)
    }

    @Test
    fun cryingWolfUsesUpTheUrgentAllowance() = rig(SchedulerConfig(maxConcurrent = 1, urgentAllowance = 2)) { r ->
        r.ask("busy", "Z", "weather")
        runCurrent()
        repeat(5) { i -> r.ask("x$i", "ATTACKER", "URGENT help emergency cricket score $i") }
        r.ask("honest", "H", "where is the first aid tent")
        advanceUntilIdle()
        val pos = r.order.indexOf("honest")
        // Two "urgent" requests get through on the claim; after that the attacker is treated as normal (and
        // loses fair share), so the honest health request overtakes the remaining three.
        assertEquals(listOf("busy", "x0", "x1", "honest"), r.order.take(4), r.order.toString())
        assertEquals(3, r.order.size - pos - 1)
        assertTrue(r.results.getValue("x3").needReasons.any { it.contains("urgent allowance used") })
    }

    @Test
    fun sosSkipsTheQueueAndTheBudget() = rig(SchedulerConfig(maxConcurrent = 1, perMinute = 2)) { r ->
        r.ask("a", "A", "weather"); r.ask("b", "B", "weather tomorrow"); r.ask("c", "C", "weather sunday")
        advanceTimeBy(3_000); runCurrent()
        assertEquals(listOf("a", "b"), r.order, "budget of 2/min spent")
        r.ask("sos", "S", "SOS from S: trapped near stage", kind = InetKind.WEBHOOK_POST, sos = true)
        runCurrent()
        assertEquals("sos", r.order.last(), "SOS must not wait for the budget window")
        assertTrue(r.results.isEmpty() || "c" !in r.results)
        advanceUntilIdle()
        assertTrue("c" in r.results, "the budget refills after a minute")
        assertTrue(r.results.getValue("sos").needReasons.first().startsWith("SOS: skips the queue"))
    }

    @Test
    fun waitingRaisesPrioritySoLowNeedIsNotStarved() = rig(SchedulerConfig(maxConcurrent = 1), workMs = 2_000) { r ->
        r.ask("busy", "Z", "weather")
        runCurrent()
        r.ask("leisure", "L", "cricket score")
        // A steady stream of general-info questions from many phones, one every 2 s.
        for (i in 0 until 90) {
            advanceTimeBy(2_000)
            r.ask("info$i", "P$i", "what time does the show end $i")
        }
        advanceUntilIdle()
        val leisureAt = r.order.indexOf("leisure")
        assertTrue(leisureAt in 1 until r.order.size - 1, "leisure must be served before the stream ends: ${r.order}")
        assertEquals(InetStatus.OK, r.results.getValue("leisure").status)
    }

    @Test
    fun overflowDefersTheLowestNeedAndExplainsWhy() = rig(SchedulerConfig(maxConcurrent = 1, maxQueue = 2)) { r ->
        r.ask("busy", "Z", "weather")
        runCurrent()
        r.ask("health", "A", "nearest hospital")
        r.ask("leisure", "B", "movie trailer")
        r.ask("safety", "C", "which exit gates are open")
        runCurrent()
        assertEquals(InetStatus.DEFERRED, r.results.getValue("leisure").status)
        assertTrue(r.results.getValue("leisure").message.contains("busy"))
        advanceUntilIdle()
        assertEquals(listOf("busy", "health", "safety"), r.order)
    }

    @Test
    fun scorerExplainsItself() {
        val s = RuleBasedNeedScorer()
        fun score(text: String, kind: InetKind = InetKind.ASK, verified: Boolean = true, role: Role = Role.USER, sos: Long? = null, battery: Int = -1) =
            s.score(NeedContext("N", kind, text, battery, verified, role, sos, 0, 2))
        assertEquals(NeedCategory.HEALTH, score("Where is the nearest hospital?").category)
        assertEquals(NeedCategory.SAFETY, score("Which gates are open").category)
        assertEquals(NeedCategory.LEISURE, score("ipl score").category)
        assertEquals(NeedCategory.INFO, score("history of the venue").category)
        // Leisure never masks a more urgent topic in the same question.
        assertEquals(NeedCategory.HEALTH, score("doctor near the cricket stadium").category)
        val responder = score("road status", role = Role.RESPONDER)
        assertTrue(responder.value > score("road status").value && responder.reasons.any { it.contains("responder") })
        assertTrue(score("road status", verified = false).value < score("road status").value)
        assertTrue(score("road status", sos = 60_000).reasons.any { it.contains("SOS") })
        assertTrue(score("road status", battery = 7).reasons.any { it.contains("battery") })
    }
}
