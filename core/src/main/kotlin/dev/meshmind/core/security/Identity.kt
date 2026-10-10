package dev.meshmind.core.security

import dev.meshmind.core.model.NodeId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

enum class Role { USER, ORGANIZER, RESPONDER }

/**
 * Binds a node ID to a public key. Issued either by the MeshMind authority after phone verification
 * ([issuer] = the authority's key fingerprint), or by the node itself ([issuer] = [SELF]) when it never enrolled.
 * Self-signed certificates still prove the packet was not altered and that the sender holds the key, but
 * say nothing about who the sender is, and their [role] is never honoured.
 *
 * Deliberately carries no phone number: certificates are broadcast to everyone. The backend keeps the
 * nodeId ↔ phone mapping for accountability.
 */
class Certificate(
    val nodeId: NodeId,
    val publicKey: ByteArray,
    val role: Role,
    val phoneVerified: Boolean,
    val issuedAt: Long,
    val expiresAt: Long,
    val issuer: String,
    val signature: ByteArray,
) {
    val selfSigned: Boolean get() = issuer == SELF
    val fingerprint: String by lazy { Ec.fingerprint(publicKey) }

    fun signedBytes(): ByteArray = canonical(nodeId, publicKey, role, phoneVerified, issuedAt, expiresAt, issuer)

    fun encode(): ByteArray {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { o ->
            o.writeByte(1)
            o.writeUTF(nodeId); o.writeShort(publicKey.size); o.write(publicKey)
            o.writeByte(role.ordinal); o.writeBoolean(phoneVerified)
            o.writeLong(issuedAt); o.writeLong(expiresAt); o.writeUTF(issuer)
            o.writeShort(signature.size); o.write(signature)
        }
        return bos.toByteArray()
    }

    companion object {
        const val SELF = "self"
        private const val YEAR_MS = 365L * 24 * 3_600_000

        fun canonical(nodeId: NodeId, publicKey: ByteArray, role: Role, phoneVerified: Boolean, issuedAt: Long, expiresAt: Long, issuer: String) =
            Canon("meshmind-cert-v1").str(nodeId).b64(publicKey).str(role.name).flag(phoneVerified)
                .num(issuedAt).num(expiresAt).str(issuer).build()

        fun selfSigned(nodeId: NodeId, signer: Signer, now: Long): Certificate {
            val sig = signer.sign(canonical(nodeId, signer.publicKey, Role.USER, false, now, now + YEAR_MS, SELF))
            return Certificate(nodeId, signer.publicKey, Role.USER, false, now, now + YEAR_MS, SELF, sig)
        }

        /** What the backend does after OTP verification; also used in-process by tests and the simulator. */
        fun issue(authority: Signer, nodeId: NodeId, publicKey: ByteArray, role: Role, now: Long, validityMs: Long = YEAR_MS): Certificate {
            val issuer = Ec.fingerprint(authority.publicKey)
            val sig = authority.sign(canonical(nodeId, publicKey, role, true, now, now + validityMs, issuer))
            return Certificate(nodeId, publicKey, role, true, now, now + validityMs, issuer, sig)
        }

        fun decode(bytes: ByteArray): Certificate = DataInputStream(ByteArrayInputStream(bytes)).use { i ->
            require(i.readByte().toInt() == 1) { "unknown certificate version" }
            val nodeId = i.readUTF()
            val pub = ByteArray(i.readUnsignedShort()).also { i.readFully(it) }
            val role = Role.entries[i.readByte().toInt()]
            val phone = i.readBoolean()
            val issued = i.readLong()
            val expires = i.readLong()
            val issuer = i.readUTF()
            val sig = ByteArray(i.readUnsignedShort()).also { i.readFully(it) }
            Certificate(nodeId, pub, role, phone, issued, expires, issuer, sig)
        }
    }
}

/** This node's signing key plus the certificate that names it. */
class NodeIdentity(val signer: Signer, val cert: Certificate) {
    val nodeId: NodeId get() = cert.nodeId
    /** Attached to every packet this node originates, so any relay can verify it offline. */
    val certBytes: ByteArray = cert.encode()

    fun sign(data: ByteArray): ByteArray = signer.sign(data)

    companion object {
        /** Fresh key + self-signed certificate: the default for routers that were given no identity. */
        fun ephemeral(nodeId: NodeId, now: Long): NodeIdentity {
            val s = SoftwareSigner.generate()
            return NodeIdentity(s, Certificate.selfSigned(nodeId, s, now))
        }
    }
}
