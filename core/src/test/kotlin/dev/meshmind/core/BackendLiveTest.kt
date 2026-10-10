package dev.meshmind.core

import dev.meshmind.core.gateway.BackendClient
import dev.meshmind.core.gateway.BackendException
import dev.meshmind.core.security.Certificate
import dev.meshmind.core.security.NodeIdentity
import dev.meshmind.core.security.Role
import dev.meshmind.core.security.SoftwareSigner
import dev.meshmind.core.security.TrustLevel
import dev.meshmind.core.security.TrustStore
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Against a running backend started with --demo-otp (skipped unless MESHMIND_BACKEND_URL is set):
 *
 *     backend/.venv/Scripts/python -m meshmind_backend.server --demo-otp --provider demo
 *     MESHMIND_BACKEND_URL=http://127.0.0.1:8787 ./gradlew :core:test --tests '*BackendLiveTest*'
 */
class BackendLiveTest {
    private val url = System.getenv("MESHMIND_BACKEND_URL")

    @Test
    fun enrollAskReadAndBulletinAgainstARealBackend(): Unit = runBlocking {
        assumeTrue("MESHMIND_BACKEND_URL not set", !url.isNullOrBlank())
        val client = BackendClient({ url!! })
        val key = SoftwareSigner.generate()
        val nodeId = "Live-" + key.fingerprint.take(4)
        val phone = "+9199" + (10_000_000 + (System.nanoTime() % 89_999_999)).toString().take(8)

        val code = client.enrollStart(phone) ?: error("backend must run with --demo-otp for this test")
        assertFailsWith<BackendException> { client.enrollVerify(phone, "000000".takeIf { it != code } ?: "111111", nodeId, key.publicKey, Role.USER, "") }
        val e = client.enrollVerify(phone, code, nodeId, key.publicKey, Role.USER, "")
        val trust = TrustStore(listOf(e.authorityKey))
        assertEquals(TrustLevel.VERIFIED, trust.evaluate(e.certificate, System.currentTimeMillis()).level)
        assertEquals(nodeId, NodeIdentity(key, e.certificate).nodeId)
        assertTrue(Certificate.decode(e.certificate.encode()).signature.contentEquals(e.certificate.signature))

        val a = client.ask("Is the metro running?", nodeId)
        assertTrue(trust.verifyAuthority(a.signedBytes(), a.signature, a.keyId), "answer signature from the live backend")
        println("LIVE ask -> [${a.provider}] ${a.text} sources=${a.sources.map { it.url }}")

        val b = client.bulletin(null, null)
        assertTrue(trust.verifyAuthority(b.signedBytes(), b.signature, b.keyId))
        println("LIVE bulletin -> ${b.title} seq=${b.seq} items=${b.items.map { it.title }}")

        assertFailsWith<BackendException> { client.read("https://127.0.0.1/secret", nodeId) }
        Unit
    }
}
