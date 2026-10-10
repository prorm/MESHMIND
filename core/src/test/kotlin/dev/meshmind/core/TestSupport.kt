package dev.meshmind.core

import dev.meshmind.core.gateway.AnswerService
import dev.meshmind.core.gateway.FakeInternetExecutor
import dev.meshmind.core.gateway.InternetExecutor
import dev.meshmind.core.gateway.LocalAuthority
import dev.meshmind.core.gateway.SchedulerConfig
import dev.meshmind.core.security.Role
import dev.meshmind.core.routing.NextHopAdvisor
import dev.meshmind.core.sim.MeshSimulation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/**
 * Runs a scenario on virtual time: SimulatedNetwork delays (latency + size/bandwidth) are real coroutine delays,
 * so ordering effects like priority preemption are deterministic and measurable.
 *
 * Routers get their own scope on the test scheduler (not backgroundScope) so that advanceUntilIdle() really
 * drains every in-flight frame; it is cancelled at the end because sender loops wait forever for work.
 * Set MESH_TRACE=1 to print every node's log.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun meshTest(
    latencyMs: Long = 10,
    bytesPerSecond: Long = 160_000,
    internet: InternetExecutor = FakeInternetExecutor(),
    advisorFor: ((String) -> NextHopAdvisor?)? = null,
    authority: LocalAuthority? = null,
    roleOf: (String) -> Role = { Role.USER },
    answers: ((MeshSimulation) -> AnswerService)? = null,
    schedulerConfig: SchedulerConfig = SchedulerConfig(),
    block: suspend TestScope.(MeshSimulation) -> Unit,
) = runTest {
    val routerScope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
    val trace = System.getenv("MESH_TRACE") != null
    val sim = MeshSimulation(
        routerScope, { testScheduler.currentTime }, latencyMs, bytesPerSecond, internet,
        logEcho = if (trace) { id, msg -> println("[${testScheduler.currentTime}ms $id] $msg") } else null,
        advisorFor = advisorFor,
        authority = authority,
        roleOf = roleOf,
        schedulerConfig = schedulerConfig,
    )
    answers?.let { sim.answers = it(sim) }
    try {
        block(sim)
    } finally {
        routerScope.cancel()
    }
}

suspend fun MeshSimulation.nodes(vararg ids: String) = ids.forEach { addNode(it) }
