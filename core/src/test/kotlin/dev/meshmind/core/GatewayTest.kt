package dev.meshmind.core

import dev.meshmind.core.gateway.FakeInternetExecutor
import dev.meshmind.core.gateway.GatewayConfig
import dev.meshmind.core.gateway.GatewayPolicy
import dev.meshmind.core.gateway.PolicyDecision
import dev.meshmind.core.model.InetKind
import dev.meshmind.core.model.InetRequest
import dev.meshmind.core.model.InetStatus
import dev.meshmind.core.model.PacketState
import dev.meshmind.core.model.Priority
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class GatewayTest {

    /** Test 6: A↔B↔C↔D(gateway). A's request reaches D over 3 hops, D fetches, the response returns D→C→B→A. */
    @Test
    fun requestReachesGatewayAndResponseReturns() {
        val internet = FakeInternetExecutor()
        meshTest(internet = internet) { sim ->
            sim.nodes("A", "B", "C", "D", "E")
            sim.node("D").isGateway = true
            sim.chain("A", "B", "C", "D")
            sim.link("B", "E") // side branch: must NOT receive the gateway-routed request
            advanceUntilIdle()
            sim["D"].emitBeacon()
            advanceUntilIdle()

            val gw = sim["A"].state.value.nearestGateway
            assertNotNull(gw)
            assertEquals("D", gw.gatewayId)
            assertEquals(3, gw.hopDistance)
            assertEquals("B", gw.viaPeer)

            val req = sim["A"].sendInternetRequest(InetRequest(InetKind.HTTP_GET, "https://example.com/", ""))
            advanceUntilIdle()

            val r = sim["A"].state.value.inet.single { it.requestId == req.packetId }
            val resp = assertNotNull(r.response)
            assertEquals(InetStatus.OK, resp.status)
            assertEquals(200, resp.httpCode)
            assertTrue(resp.bodyText.contains("hello from https://example.com/"))
            assertEquals(listOf("A", "B", "C", "D"), resp.requestRoute)
            assertEquals(listOf("D", "C", "B", "A"), r.responseRoute)
            assertNotNull(r.reachedGatewayAt)
            assertEquals(listOf("GET https://example.com/"), internet.calls, "executed exactly once, only at D")
            assertNull(sim.node("E").store.snapshot()[req.packetId], "request was routed toward the gateway, not flooded")
        }
    }

    /** Test 7: gateway link lost → request waits in the mesh; gateway returns → request completes. */
    @Test
    fun requestWaitsForGatewayToReturn() {
        val internet = FakeInternetExecutor()
        meshTest(internet = internet) { sim ->
            sim.nodes("A", "B", "C", "D")
            sim.node("D").isGateway = true
            sim.chain("A", "B", "C", "D")
            advanceUntilIdle()
            sim["D"].emitBeacon()
            advanceUntilIdle()

            sim.cut("C", "D")
            advanceUntilIdle()
            val req = sim["A"].sendInternetRequest(InetRequest(InetKind.WEBHOOK_POST, "", "SOS at Gate C"), sos = true)
            assertEquals(Priority.CRITICAL, req.priority)
            advanceUntilIdle()

            assertNull(sim["A"].state.value.inet.single().response)
            assertTrue(internet.calls.isEmpty())
            assertEquals(PacketState.QUEUED, sim.node("C").store.snapshot()[req.packetId]!!.state, "parked at C")

            advanceTimeBy(30_000)
            sim.tickAll()
            advanceUntilIdle()
            assertNull(sim["A"].state.value.inet.single().response, "still waiting while gateway is gone")

            sim.link("C", "D") // gateway is back: handshake + beacon + summary pull
            advanceUntilIdle()

            val resp = assertNotNull(sim["A"].state.value.inet.single().response)
            assertEquals(InetStatus.OK, resp.status)
            assertEquals(1, internet.calls.size)
            assertTrue(internet.calls.single().startsWith("POST https://ntfy.sh/meshmind-sim SOS at Gate C"))
        }
    }

    /** Test 8: non-allowlisted URL and oversize request are rejected by the gateway; nothing hits the Internet. */
    @Test
    fun gatewayRejectsDisallowedAndOversizeRequests() {
        val internet = FakeInternetExecutor()
        meshTest(internet = internet) { sim ->
            sim.nodes("A", "B", "D")
            sim.node("D").isGateway = true
            sim.chain("A", "B", "D")
            advanceUntilIdle()
            sim["D"].emitBeacon()
            advanceUntilIdle()

            val bad = sim["A"].sendInternetRequest(InetRequest(InetKind.HTTP_GET, "https://evil.test/steal", ""))
            val big = sim["A"].sendInternetRequest(InetRequest(InetKind.WEBHOOK_POST, "", "x".repeat(5_000)))
            val plain = sim["A"].sendInternetRequest(InetRequest(InetKind.HTTP_GET, "http://example.com/", ""))
            advanceUntilIdle()

            val results = sim["A"].state.value.inet.associateBy { it.requestId }
            for (id in listOf(bad.packetId, big.packetId, plain.packetId)) {
                assertEquals(InetStatus.REJECTED, results[id]!!.response!!.status)
            }
            assertTrue(results[bad.packetId]!!.response!!.message.contains("allowlist"))
            assertTrue(results[big.packetId]!!.response!!.message.contains("too large"))
            assertTrue(results[plain.packetId]!!.response!!.message.contains("HTTPS"))
            assertTrue(internet.calls.isEmpty())
        }
    }

    /** Responses larger than the cap are truncated (and flagged) rather than flooding the mesh. */
    @Test
    fun responseBodyIsCapped() {
        val internet = FakeInternetExecutor { "y".repeat(100_000) }
        meshTest(internet = internet) { sim ->
            sim.nodes("A", "D")
            sim.node("D").isGateway = true
            sim.link("A", "D")
            advanceUntilIdle()
            sim["D"].emitBeacon()
            advanceUntilIdle()
            sim["A"].sendInternetRequest(InetRequest(InetKind.HTTP_GET, "https://en.wikipedia.org/wiki/Mesh_networking", ""))
            advanceUntilIdle()
            val resp = sim["A"].state.value.inet.single().response!!
            assertEquals(16 * 1024, resp.body.size)
            assertTrue(resp.truncated)
        }
    }

    /** Without a gateway beacon the request floods; with two gateways only the first executes per gateway and A keeps one answer. */
    @Test
    fun firstGatewayResponseWins() {
        val internet = FakeInternetExecutor()
        meshTest(internet = internet) { sim ->
            sim.nodes("A", "G1", "G2")
            sim.node("G1").isGateway = true
            sim.node("G2").isGateway = true
            sim.link("A", "G1"); sim.link("A", "G2")
            advanceUntilIdle()
            // No beacons emitted on purpose → A floods to both.
            sim["A"].sendInternetRequest(InetRequest(InetKind.HTTP_GET, "https://example.com/", ""))
            advanceUntilIdle()
            val r = sim["A"].state.value.inet.single()
            assertNotNull(r.response)
            assertTrue(internet.calls.size in 1..2)
        }
    }

    @Test
    fun policyUnitChecks() {
        var cfg = GatewayConfig(consent = true, webhookUrl = "https://ntfy.sh/demo", perRequesterPerMinute = 2)
        val policy = GatewayPolicy { cfg }
        fun get(url: String) = InetRequest(InetKind.HTTP_GET, url, "")
        assertIs<PolicyDecision.Allow>(policy.validate(get("https://example.com/")))
        assertIs<PolicyDecision.Allow>(policy.validate(get("https://www.example.com/a?b=c")))
        assertIs<PolicyDecision.Reject>(policy.validate(get("https://example.com.evil.test/")))
        assertIs<PolicyDecision.Reject>(policy.validate(get("https://example.com:8443/")))
        assertIs<PolicyDecision.Reject>(policy.validate(get("https://user:pw@example.com/")))
        assertIs<PolicyDecision.Reject>(policy.validate(InetRequest(InetKind.HTTP_GET, "https://example.com/", "body")))
        assertIs<PolicyDecision.Reject>(policy.validate(InetRequest(InetKind.WEBHOOK_POST, "https://attacker.test/", "hi")))
        assertIs<PolicyDecision.Allow>(policy.check("A", get("https://example.com/"), 0))
        assertIs<PolicyDecision.Allow>(policy.check("A", get("https://example.com/"), 1))
        assertIs<PolicyDecision.Reject>(policy.check("A", get("https://example.com/"), 2), "per-requester rate limit")
        assertIs<PolicyDecision.Allow>(policy.check("B", get("https://example.com/"), 3))
        assertIs<PolicyDecision.Allow>(policy.check("A", get("https://example.com/"), 61_000), "window slides")
        cfg = cfg.copy(consent = false)
        assertIs<PolicyDecision.Reject>(policy.validate(get("https://example.com/")))
    }
}
