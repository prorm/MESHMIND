package dev.meshmind.core

import dev.meshmind.core.gateway.BackendClient
import dev.meshmind.core.model.WireCodec
import dev.meshmind.core.sage.MiniJson
import dev.meshmind.core.security.Role
import dev.meshmind.core.security.TrustLevel
import dev.meshmind.core.security.TrustStore
import dev.meshmind.core.security.unb64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Signatures made by the Python backend (backend/tools/make_vectors.py) verify on the phones: same canonical
 * bytes, same ECDSA encoding. If this fails after touching either Canon, regenerate the vectors and compare.
 */
class BackendInteropTest {
    @Suppress("UNCHECKED_CAST")
    private val vectors = MiniJson.parse(javaClass.getResource("/backend_vectors.json")!!.readText()) as Map<String, Any?>
    private val client = BackendClient({ "" })
    private val trust = TrustStore(listOf((vectors["authorityKey"] as String).unb64()))

    @Suppress("UNCHECKED_CAST")
    private fun obj(k: String) = vectors[k] as Map<String, Any?>

    @Test
    fun certificateIssuedByPythonVerifies() {
        val cert = client.parseCertificate(obj("certificate"))
        val r = trust.checkCertificate(cert, cert.issuedAt + 1_000)
        assertEquals(TrustLevel.VERIFIED, r.level, r.reason)
        assertEquals(Role.ORGANIZER, r.role)
        assertEquals("Riya-1a2b", cert.nodeId)
        assertEquals(TrustLevel.INVALID, TrustStore().checkCertificate(cert, cert.issuedAt).level, "no anchor, no trust")
    }

    @Test
    fun answerAndBulletinSignedByPythonVerifyAndSurviveTheWire() {
        val a = client.parseAnswer(obj("answer"))
        assertTrue(trust.verifyAuthority(a.signedBytes(), a.signature, a.keyId))
        val wire = WireCodec.decodeAnswer(WireCodec.encodeAnswer(a))
        assertTrue(trust.verifyAuthority(wire.signedBytes(), wire.signature, wire.keyId), "unicode text must round-trip byte-exact")
        val forged = dev.meshmind.core.model.Answer(a.kind, a.query, a.text + "!", a.sources, a.createdAt, a.expiresAt, a.provider, a.keyId, a.signature)
        assertFalse(trust.verifyAuthority(forged.signedBytes(), forged.signature, forged.keyId))

        val b = client.parseBulletin(obj("bulletin"))
        assertTrue(trust.verifyAuthority(b.signedBytes(), b.signature, b.keyId))
        assertEquals(2, b.items.size)
    }
}
