package dev.meshmind.core.security

import dev.meshmind.core.model.Answer
import dev.meshmind.core.model.NodeId
import dev.meshmind.core.model.Packet
import dev.meshmind.core.model.PacketType
import java.security.PublicKey

enum class TrustLevel {
    /** Certificate issued by a trusted MeshMind authority (phone-verified identity) and a valid signature. */
    VERIFIED,
    /** Valid signature under a self-signed certificate: untampered, but the sender's identity is unverified. */
    SELF_SIGNED,
    /** No signature at all (only accepted when the router is configured to allow it). */
    UNSIGNED,
    /** Bad signature, expired or foreign certificate, or a key that contradicts what we know about that node. */
    INVALID,
}

/** Who vouches for an answer. */
enum class AnswerTrust {
    /** Signed by a MeshMind authority (made by the backend). */
    AUTHORITY,
    /** Signed by a gateway phone whose identity is authority-verified. */
    VERIFIED_GATEWAY,
    /** Signed by a gateway phone with a self-signed identity: untampered, but who ran it is unverified. */
    GATEWAY,
    INVALID,
}

data class TrustResult(
    val level: TrustLevel,
    /** Role that is actually honoured: always USER unless the certificate is authority-issued. */
    val role: Role = Role.USER,
    val reason: String = "",
    val fingerprint: String? = null,
) {
    val accepted: Boolean get() = level == TrustLevel.VERIFIED || level == TrustLevel.SELF_SIGNED

    companion object {
        val UNSIGNED = TrustResult(TrustLevel.UNSIGNED, reason = "unsigned")
        fun invalid(reason: String) = TrustResult(TrustLevel.INVALID, reason = reason)
    }
}

/**
 * Verifies certificates, packet signatures and authority-signed content (answers, bulletins).
 *
 * Node-key pinning (trust on first use) stops a stranger from reusing a known node's ID with a different key:
 * the first key seen for a node ID wins unless an authority-issued certificate replaces it (and a newer
 * authority certificate replaces an older one, e.g. after a reinstall).
 */
class TrustStore(
    anchors: Collection<ByteArray> = emptyList(),
    /**
     * Tolerance for certificate validity windows. Generous on purpose: after hours without network time a phone's
     * clock can drift, and a wrong clock must never lock a phone out of the mesh (or its SOS out of delivery).
     */
    private val maxSkewMs: Long = 24 * 3_600_000L,
    private val maxPins: Int = 20_000,
) {
    private data class Pin(val fingerprint: String, val verified: Boolean, val issuedAt: Long)

    private val anchors = LinkedHashMap<String, PublicKey>()
    private val pins = object : LinkedHashMap<NodeId, Pin>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<NodeId, Pin>?) = size > maxPins
    }
    /** Certificate signature check results, keyed by the certificate's signature bytes. */
    private val certChecks = object : LinkedHashMap<String, String?>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String?>?) = size > 4_000
    }
    private val keys = object : LinkedHashMap<String, PublicKey>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PublicKey>?) = size > 4_000
    }

    init { anchors.forEach { addAnchor(it) } }

    @Synchronized
    fun addAnchor(publicKey: ByteArray): String {
        val fp = Ec.fingerprint(publicKey)
        anchors[fp] = Ec.publicKey(publicKey)
        certChecks.clear()
        return fp
    }

    @Synchronized
    fun anchorFingerprints(): List<String> = anchors.keys.toList()

    @Synchronized
    fun hasAnchor(): Boolean = anchors.isNotEmpty()

    @Synchronized
    fun forgetPins() = pins.clear()

    /** Checks the certificate itself (issuer signature + validity window) without pinning. */
    @Synchronized
    fun checkCertificate(cert: Certificate, now: Long): TrustResult {
        if (now < cert.issuedAt - maxSkewMs) return TrustResult.invalid("certificate not yet valid")
        if (now > cert.expiresAt + maxSkewMs) return TrustResult.invalid("certificate expired")
        val cacheKey = cert.signature.b64()
        val failure = if (certChecks.containsKey(cacheKey)) certChecks[cacheKey] else {
            val issuerKey = if (cert.selfSigned) runCatching { key(cert.publicKey) }.getOrNull() else anchors[cert.issuer]
            val r = when {
                issuerKey == null -> "issuer ${cert.issuer} is not a trusted authority"
                !Ec.verify(issuerKey, cert.signedBytes(), cert.signature) -> "certificate signature invalid"
                else -> null
            }
            certChecks[cacheKey] = r
            r
        }
        if (failure != null) return TrustResult.invalid(failure)
        return if (cert.selfSigned) TrustResult(TrustLevel.SELF_SIGNED, Role.USER, "self-signed", cert.fingerprint)
        else TrustResult(TrustLevel.VERIFIED, cert.role, "issued by ${cert.issuer}", cert.fingerprint)
    }

    /** [checkCertificate] plus key pinning for [Certificate.nodeId]. */
    @Synchronized
    fun evaluate(cert: Certificate, now: Long): TrustResult {
        val r = checkCertificate(cert, now)
        if (!r.accepted) return r
        val verified = r.level == TrustLevel.VERIFIED
        val pin = pins[cert.nodeId]
        if (pin != null && pin.fingerprint != cert.fingerprint) {
            val replaces = verified && (!pin.verified || cert.issuedAt > pin.issuedAt)
            if (!replaces) return TrustResult.invalid("node ${cert.nodeId} is already known with key ${pin.fingerprint}")
        }
        if (pin == null || pin.fingerprint != cert.fingerprint || (verified && !pin.verified)) {
            pins[cert.nodeId] = Pin(cert.fingerprint, verified, cert.issuedAt)
        }
        return r
    }

    /** Verifies an originated packet: its certificate must name the packet's source and sign its immutable fields. */
    fun verifyPacket(p: Packet, now: Long): TrustResult {
        val certBytes = p.cert ?: return TrustResult.UNSIGNED
        val sig = p.signature ?: return TrustResult.UNSIGNED
        val cert = runCatching { Certificate.decode(certBytes) }.getOrElse { return TrustResult.invalid("undecodable certificate") }
        if (cert.nodeId != p.sourceId) return TrustResult.invalid("certificate names ${cert.nodeId}, packet claims ${p.sourceId}")
        val r = evaluate(cert, now)
        if (!r.accepted) return r
        val k = synchronized(this) { key(cert.publicKey) }
        return if (Ec.verify(k, p.signingBytes(), sig)) r else TrustResult.invalid("packet signature invalid (altered in transit?)")
    }

    /** Content signed by a MeshMind authority (answers, official bulletins). [keyId] is the signer's fingerprint. */
    fun verifyAuthority(data: ByteArray, signature: ByteArray, keyId: String): Boolean {
        val k = synchronized(this) { anchors[keyId] } ?: return false
        return Ec.verify(k, data, signature)
    }

    /** Checks an answer's signature: the authority's, or the gateway node's (via the certificate it carries). */
    fun verifyAnswer(a: Answer, now: Long): AnswerTrust {
        val node = a.nodeSigner ?: return if (verifyAuthority(a.signedBytes(), a.signature, a.keyId)) AnswerTrust.AUTHORITY else AnswerTrust.INVALID
        val cert = a.signerCert?.let { runCatching { Certificate.decode(it) }.getOrNull() } ?: return AnswerTrust.INVALID
        if (cert.nodeId != node) return AnswerTrust.INVALID
        val r = evaluate(cert, now)
        if (!r.accepted || !verifyNode(a.signedBytes(), a.signature, cert.publicKey)) return AnswerTrust.INVALID
        return if (r.level == TrustLevel.VERIFIED) AnswerTrust.VERIFIED_GATEWAY else AnswerTrust.GATEWAY
    }

    /** Content signed directly by a node (e.g. an organizer's notice); [publicKey] comes from its certificate. */
    fun verifyNode(data: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean {
        val k = runCatching { synchronized(this) { key(publicKey) } }.getOrNull() ?: return false
        return Ec.verify(k, data, signature)
    }

    private fun key(encoded: ByteArray): PublicKey = keys.getOrPut(encoded.b64()) { Ec.publicKey(encoded) }
}

/**
 * The fields a packet's signature covers: everything set by the origin. TTL, hop count and route history change
 * on every hop and are excluded (as Bitchat excludes TTL); priority and label are covered, so relays cannot
 * upgrade a packet's priority.
 */
fun Packet.signingBytes(): ByteArray {
    // Light-show controls get a fresh local lifetime on every relay (phone wall clocks are unrelated), so their
    // timestamps are not covered; the controller's own revision numbers order them.
    val light = type == PacketType.LIGHT_SHOW
    return Canon("meshmind-packet-v2").str(packetId).str(sourceId).str(destinationId).str(type.name).str(priority.name)
        .num(if (light) 0 else createdAt).num(if (light) 0 else expiresAt).bin(payload).str(label).build()
}
