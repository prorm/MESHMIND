package dev.meshmind.core

import dev.meshmind.core.gateway.DirectAnswerService
import dev.meshmind.core.gateway.HttpCall
import dev.meshmind.core.gateway.HttpReply
import dev.meshmind.core.gateway.OpenMeteo
import dev.meshmind.core.gateway.PageText
import dev.meshmind.core.model.BulletinItem
import dev.meshmind.core.model.BulletinKind
import dev.meshmind.core.model.InetResponse
import dev.meshmind.core.security.AnswerTrust
import dev.meshmind.core.security.NodeIdentity
import dev.meshmind.core.security.TrustStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** No MeshMind server: the gateway phone answers with Gemini itself and signs with its own key. */
@OptIn(ExperimentalCoroutinesApi::class)
class DirectGatewayTest {

    /** Gemini stand-in: refuses grounding like a free-tier key, answers ungrounded. */
    private class FakeGemini : HttpCall {
        val bodies = mutableListOf<String>()
        override suspend fun call(method: String, url: String, headers: Map<String, String>, body: String?): HttpReply {
            if (url.contains("open-meteo")) return HttpReply(200, """{"current":{"temperature_2m":24.4,"weather_code":3,"wind_speed_10m":12.0},
                "hourly":{"time":["2026-10-10T16:00","2026-10-10T17:00"],"precipitation_probability":[20,70]}}""")
            if (url.startsWith("https://example.org")) return HttpReply(200, "<html><head><title>Gate info</title><script>x()</script></head>" +
                "<body><nav>menu</nav><p>Gate A &amp; C open.</p><p>Gate B closed.</p></body></html>")
            bodies += body ?: ""
            if (body?.contains("google_search") == true) return HttpReply(429, """{"error":{"code":429}}""")
            return HttpReply(200, """{"candidates":[{"content":{"parts":[{"text":"**Bowring Hospital** is about\n 2 km away."}]}}]}""")
        }
    }

    @Test
    fun gatewayAnswersSignsAndRelaysCacheWithoutAnyServer() {
        val fake = FakeGemini()
        meshTest { sim ->
            sim.nodes("A", "B", "G", "D")
            val gw = sim.node("G")
            gw.isGateway = true
            sim.answers = DirectAnswerService({ gw.identity }, { "test-key" }, clock = { 0L }, http = fake)
            sim.chain("A", "B", "G")
            sim.link("D", "B")
            advanceUntilIdle()
            sim["G"].emitBeacon()
            advanceUntilIdle()

            val id = sim["A"].sendAsk("Where is the nearest hospital?")
            advanceUntilIdle()
            val r = sim["A"].state.value.inet.single { it.requestId == id }
            assertEquals(AnswerTrust.GATEWAY, r.answerTrust, "self-signed gateway: valid signature, unverified identity")
            assertTrue(r.answerVerified)
            assertEquals("Bowring Hospital is about 2 km away.", r.answer!!.text)
            assertTrue(r.answer!!.provider.endsWith("(no web search)"))
            assertEquals(2, fake.bodies.size, "one refused grounded call, one ungrounded")
            assertTrue(fake.bodies[1].contains("NO web access"), "ungrounded answers get the no-live-data rule")

            // Grounding is remembered as unavailable: the next question goes straight to the ungrounded call.
            val d = sim["D"].sendAsk("nearest hospital")
            advanceUntilIdle()
            assertEquals(InetResponse.SERVED_RELAY_CACHE, sim["D"].state.value.inet.single { it.requestId == d }.servedFrom)
            assertEquals(2, fake.bodies.size, "B answered D from its cache: no Gemini call")
        }
    }

    @Test
    fun tamperedGatewayAnswerIsRejected() = runBlocking {
        val g = NodeIdentity.ephemeral("G", 0)
        val svc = DirectAnswerService({ g }, { "k" }, clock = { 0L }, http = FakeGemini())
        val a = svc.ask("q", "A")
        val trust = TrustStore()
        assertEquals(AnswerTrust.GATEWAY, trust.verifyAnswer(a, 0))
        val forged = dev.meshmind.core.model.Answer(a.kind, a.query, "Go to Gate B", a.sources, a.createdAt, a.expiresAt, a.provider, a.keyId, a.signature, a.signerCert)
        assertEquals(AnswerTrust.INVALID, trust.verifyAnswer(forged, 0))
        val impostor = NodeIdentity.ephemeral("H", 0) // signs as "G" with someone else's certificate
        val wrongCert = dev.meshmind.core.model.Answer(a.kind, a.query, a.text, a.sources, a.createdAt, a.expiresAt, a.provider, a.keyId, a.signature, impostor.certBytes)
        assertEquals(AnswerTrust.INVALID, trust.verifyAnswer(wrongCert, 0))
    }

    @Test
    fun gatewayMayShareWeatherButNotAlerts() = meshTest { sim ->
        sim.nodes("G", "A", "B")
        sim.node("G").isGateway = true
        sim.chain("G", "A", "B")
        advanceUntilIdle()
        sim["G"].emitBeacon()
        advanceUntilIdle()
        val items = OpenMeteo.weather(12.97, 77.59, 0, FakeGemini())
        assertEquals("24°C, overcast", items.single().title)
        assertTrue(items.single().body.contains("Rain likely around 17:00 (70%)"))
        assertTrue(sim["G"].broadcastGatewayWeather(items))
        assertFalse(sim["G"].broadcastGatewayWeather(items), "unchanged weather is not re-broadcast")
        assertFalse(sim["G"].broadcastGatewayWeather(listOf(BulletinItem(BulletinKind.ALERT, 3, "Evacuate", "", "G", 0, 600_000))),
            "a plain gateway cannot broadcast alerts")
        advanceUntilIdle()
        val b = sim["B"].state.value.bulletins.single()
        assertTrue(b.viaGateway)
        assertEquals("G/weather", b.bulletin.boardId)
    }

    @Test
    fun pageTextDropsScriptsAndNavigation() {
        val (title, text) = PageText.extract("<html><head><title>Gate info</title><script>x()</script></head>" +
            "<body><nav>menu</nav><p>Gate A &amp; C open.</p><p>Gate B closed.</p></body></html>")
        assertEquals("Gate info", title)
        assertEquals("Gate A & C open.\nGate B closed.", text)
        assertFalse(DirectAnswerService.publicHost("127.0.0.1"))
        assertFalse(DirectAnswerService.publicHost("192.168.1.10"))
    }
}
