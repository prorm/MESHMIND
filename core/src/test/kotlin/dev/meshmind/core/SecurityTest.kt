package dev.meshmind.core

import dev.meshmind.core.gateway.LocalAuthority
import dev.meshmind.core.model.AckPayload
import dev.meshmind.core.model.Addresses
import dev.meshmind.core.model.Bulletin
import dev.meshmind.core.model.BulletinItem
import dev.meshmind.core.model.BulletinKind
import dev.meshmind.core.model.Frame
import dev.meshmind.core.model.Packet
import dev.meshmind.core.model.PacketType
import dev.meshmind.core.model.Priority
import dev.meshmind.core.model.SummaryPayload
import dev.meshmind.core.model.WireCodec
import dev.meshmind.core.security.Certificate
import dev.meshmind.core.security.NodeIdentity
import dev.meshmind.core.security.Role
import dev.meshmind.core.security.SoftwareSigner
import dev.meshmind.core.security.TrustLevel
import dev.meshmind.core.security.TrustStore
import dev.meshmind.core.security.signingBytes
import dev.meshmind.core.sim.MeshSimulation
import dev.meshmind.core.transport.TransportListener
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The trust layer against the attacks a crowd mesh actually invites: altered messages, spoofed senders, fake
 * delivery receipts that silence an SOS, fake broadcasts, and impersonated neighbours.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SecurityTest {

    /** A hostile phone speaking raw frames (not running our Router). */
    private class Attacker(val sim: MeshSimulation, val id: String, val identity: NodeIdentity = NodeIdentity.ephemeral(id, 0)) {
        val received = mutableListOf<ByteArray>()
        val transport = sim.network.transport(id).also {
            it.setListener(object : TransportListener {
                override suspend fun onLinkUp(endpointId: String) = Unit
                override suspend fun onLinkDown(endpointId: String) = Unit
                override suspend fun onReceived(endpointId: String, bytes: ByteArray) { received += bytes }
            })
        }

        suspend fun send(peer: String, frame: Frame) = transport.send(sim.network.endpointOf(id, peer)!!, WireCodec.encode(frame))

        suspend fun hello(peer: String, claim: String = id, signer: NodeIdentity = identity) {
            val unsigned = Frame.Hello(claim, WireCodec.PROTOCOL_VERSION, false, 0)
            send(peer, Frame.Hello(claim, WireCodec.PROTOCOL_VERSION, false, 0, signer.certBytes, signer.sign(unsigned.signedBytes())))
        }

        fun packet(type: PacketType, source: String, dest: String, payload: ByteArray, priority: Priority = Priority.NORMAL, signer: NodeIdentity = identity): Packet {
            val p = Packet(Packet.newId(), source, dest, type, priority, 0, 3_600_000, 8, 0, payload, listOf(source), "")
            return p.copy(signature = signer.sign(p.signingBytes()), cert = signer.certBytes)
        }
    }

    private fun TestScope.attacker(sim: MeshSimulation, id: String = "X") = Attacker(sim, id)

    @Test
    fun messageAlteredInTransitIsDroppedAtTheNextHop() = meshTest { sim ->
        sim.nodes("A", "B", "C")
        sim.chain("A", "B", "C")
        advanceUntilIdle()
        // B's radio path to C rewrites the meeting point but cannot re-sign it.
        sim.network.tamper = { from, to, bytes ->
            val f = runCatching { WireCodec.decode(bytes) }.getOrNull()
            if (from == "B" && to == "C" && f is Frame.Data && f.packet.type == PacketType.TEXT)
                WireCodec.encode(Frame.Data(f.packet.copy(payload = "Meet at Gate F".toByteArray()))) else bytes
        }
        sim["A"].sendText("C", "Meet at Gate C")
        advanceUntilIdle()
        assertTrue(sim["C"].state.value.inbox.none { it.text.contains("Gate") }, "altered text must never be shown")
        assertTrue(sim["C"].state.value.stats.rejectedUntrusted > 0)

        sim.network.tamper = null
        sim["A"].sendText("C", "Untouched")
        advanceUntilIdle()
        val m = sim["C"].state.value.inbox.single()
        assertEquals("Untouched", m.text)
        assertEquals(TrustLevel.SELF_SIGNED, m.trust)
    }

    @Test
    fun aForgedCopyRacingAheadCannotBlockTheGenuinePacket() = meshTest { sim ->
        sim.nodes("A", "B", "C")
        sim.link("A", "B")
        advanceUntilIdle()
        var captured: Packet? = null
        sim.network.onFrame = { from, _, bytes ->
            val f = runCatching { WireCodec.decode(bytes) }.getOrNull()
            if (from == "A" && f is Frame.Data && f.packet.type == PacketType.SOS) captured = f.packet
        }
        val sos = sim["A"].sendSos("trapped at Gate C")
        advanceUntilIdle()
        val real = requireNotNull(captured)
        // X saw the SOS go by and rushes an altered copy (same ID, same signature) to C first.
        val x = attacker(sim)
        sim.link("X", "C")
        x.hello("C")
        advanceUntilIdle()
        x.send("C", Frame.Data(real.copy(payload = "false alarm, ignore".toByteArray())))
        advanceUntilIdle()
        assertTrue(sim["C"].state.value.inbox.isEmpty())
        sim.link("B", "C")
        advanceUntilIdle()
        assertEquals("trapped at Gate C", sim["C"].state.value.inbox.single { it.packetId == sos.packetId }.text)
    }

    @Test
    fun spoofedSenderIsRejectedEvenWithAValidSignature() = meshTest { sim ->
        sim.nodes("A", "B", "C")
        sim.chain("A", "B", "C")
        advanceUntilIdle()
        val x = attacker(sim)
        sim.link("X", "B")
        x.hello("B")
        advanceUntilIdle()
        // 1) X's own certificate, but claiming to be A.
        x.send("B", Frame.Data(x.packet(PacketType.SOS, "A", Addresses.BROADCAST, "fake SOS 1".toByteArray(), Priority.CRITICAL)))
        // 2) A brand-new key with a self-signed certificate that names A: contradicts the key B already knows for A.
        val fakeA = NodeIdentity.ephemeral("A", 0)
        x.send("B", Frame.Data(x.packet(PacketType.SOS, "A", Addresses.BROADCAST, "fake SOS 2".toByteArray(), Priority.CRITICAL, signer = fakeA)))
        advanceUntilIdle()
        for (n in listOf("B", "C")) assertTrue(sim[n].state.value.inbox.none { it.text.startsWith("fake") }, "$n accepted a spoofed SOS")
        assertEquals(2, sim["B"].state.value.stats.rejectedUntrusted)

        sim["A"].sendSos("real one")
        advanceUntilIdle()
        assertTrue(sim["C"].state.value.inbox.any { it.text == "real one" })
    }

    @Test
    fun helloImpersonatingAnotherNodeIsRefused() = meshTest { sim ->
        sim.nodes("A", "B")
        sim.link("A", "B")
        advanceUntilIdle()
        val x = attacker(sim)
        sim.link("X", "B")
        x.hello("B", claim = "A") // X's certificate names X, not A
        advanceUntilIdle()
        val peers = sim["B"].state.value.peers.mapNotNull { it.nodeId }
        assertEquals(listOf("A"), peers, "the impersonating link must stay unidentified")
    }

    @Test
    fun forgedAckCannotStopAMessage() = meshTest { sim ->
        sim.nodes("A", "B", "C")
        sim.link("A", "B")
        advanceUntilIdle()
        val text = sim["A"].sendText("C", "for C only")
        advanceUntilIdle() // parked at B: C is not connected
        val x = attacker(sim)
        sim.link("X", "B")
        x.hello("B")
        advanceUntilIdle()
        val ack = AckPayload(text.packetId, PacketType.TEXT, 0, 2, listOf("A", "B", "C"), false)
        x.send("B", Frame.Data(x.packet(PacketType.ACK, "X", "A", WireCodec.encodeAck(ack), Priority.HIGH)))
        advanceUntilIdle()
        sim.link("B", "C")
        advanceUntilIdle()
        assertTrue(sim["C"].state.value.inbox.any { it.packetId == text.packetId }, "a forged ACK from X must not stop delivery to C")
    }

    @Test
    fun neighbourCannotSilenceAnSosByClaimingItWasDelivered() = meshTest { sim ->
        sim.nodes("A", "B", "C")
        sim.link("A", "B")
        advanceUntilIdle()
        val sos = sim["A"].sendSos("trapped near stage")
        advanceUntilIdle()
        val x = attacker(sim)
        sim.link("X", "B")
        x.hello("B")
        advanceUntilIdle()
        val summary = Packet(Packet.newId(), "X", "B", PacketType.SUMMARY, Priority.HIGH, 0, 60_000, 0, 0,
            WireCodec.encodeSummary(SummaryPayload(emptyList(), listOf(sos.packetId), emptyList())), listOf("X"), "CONTROL")
        x.send("B", Frame.Data(summary))
        advanceUntilIdle()
        sim.link("B", "C")
        advanceUntilIdle()
        assertTrue(sim["C"].state.value.inbox.any { it.packetId == sos.packetId }, "SOS must keep spreading")
    }

    @Test
    fun onlyAuthorityOrVerifiedOrganizersCanBroadcastBulletins() {
        val authority = LocalAuthority(SoftwareSigner.generate())
        meshTest(authority = authority, roleOf = { if (it == "ORG") Role.ORGANIZER else Role.USER }) { sim ->
            sim.nodes("ORG", "A", "B")
            sim.chain("ORG", "A", "B")
            advanceUntilIdle()

            // A verified ordinary user tries to post: refused locally by receivers (role USER).
            sim["A"].postNotice("Fire at Gate A!", "Run", 3)
            // An unenrolled stranger signs a board claiming to be official with its own key.
            val x = attacker(sim)
            sim.link("X", "A")
            x.hello("A")
            advanceUntilIdle()
            val now = 0L
            val items = listOf(BulletinItem(BulletinKind.ALERT, 3, "Evacuate now", "fake", "X", now, now + 600_000))
            val fakeKeyId = authority.keyId // claims the real authority's key id
            val fake = Bulletin("official", "Live info", 99, now, items, fakeKeyId, x.identity.sign(Bulletin.canonical("official", "Live info", 99, now, items, fakeKeyId)))
            x.send("A", Frame.Data(x.packet(PacketType.BULLETIN, "X", Addresses.BROADCAST, WireCodec.encodeBulletin(fake))))
            advanceUntilIdle()
            assertTrue(sim["B"].state.value.bulletins.isEmpty(), "B must not show bulletins from A (user role) or X (forged)")

            assertTrue(sim["ORG"].postNotice("Gate B closed", "Use Gates A or C", 2))
            advanceUntilIdle()
            val board = sim["B"].state.value.bulletins.single()
            assertEquals("ORG", board.bulletin.boardId)
            assertEquals(Role.ORGANIZER, board.signerRole)
            assertFalse(board.official)
            assertEquals("Gate B closed", board.bulletin.items.first().title)
        }
    }

    @Test
    fun certificatesAndPinsBehave() {
        val authority = SoftwareSigner.generate()
        val trust = TrustStore(listOf(authority.publicKey))
        val k1 = SoftwareSigner.generate()
        val k2 = SoftwareSigner.generate()
        val self1 = Certificate.selfSigned("N", k1, 0)
        assertEquals(TrustLevel.SELF_SIGNED, trust.evaluate(self1, 0).level)
        // Another self-signed key for the same name is a conflict...
        assertEquals(TrustLevel.INVALID, trust.evaluate(Certificate.selfSigned("N", k2, 0), 0).level)
        // ...but an authority-issued certificate takes over the name, and then the old self-signed key is refused.
        val issued = Certificate.issue(authority, "N", k2.publicKey, Role.RESPONDER, 10)
        val r = trust.evaluate(issued, 10)
        assertEquals(TrustLevel.VERIFIED, r.level)
        assertEquals(Role.RESPONDER, r.role)
        assertEquals(TrustLevel.INVALID, trust.evaluate(self1, 20).level)
        // Self-signed roles are never honoured; foreign authorities are not trusted; expiry is enforced.
        val rogue = SoftwareSigner.generate()
        assertEquals(TrustLevel.INVALID, trust.checkCertificate(Certificate.issue(rogue, "M", k1.publicKey, Role.ORGANIZER, 0), 0).level)
        val shortLived = Certificate.issue(authority, "P", k1.publicKey, Role.USER, 0, validityMs = 1_000)
        assertEquals(TrustLevel.INVALID, trust.checkCertificate(shortLived, 2 * 24 * 3_600_000L).level)
        assertEquals("N", Certificate.decode(issued.encode()).nodeId)
    }
}
