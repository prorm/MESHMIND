package dev.meshmind.core.security

import java.io.ByteArrayOutputStream
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * ECDSA P-256 / SHA-256 through plain JCA. Chosen over Ed25519 (what Bitchat uses) because it needs no extra
 * dependency on Android API 26+ and on the JVM, and the backend can produce identical signatures with Python's
 * `cryptography`. Public keys travel as X.509 SubjectPublicKeyInfo DER, signatures as DER.
 */
object Ec {
    private const val SIG_ALG = "SHA256withECDSA"

    fun generate(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    fun publicKey(encoded: ByteArray): PublicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encoded))

    fun privateKey(pkcs8: ByteArray): PrivateKey = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(pkcs8))

    fun sign(key: PrivateKey, data: ByteArray): ByteArray =
        Signature.getInstance(SIG_ALG).run { initSign(key); update(data); sign() }

    fun verify(key: PublicKey, data: ByteArray, signature: ByteArray): Boolean = try {
        Signature.getInstance(SIG_ALG).run { initVerify(key); update(data); verify(signature) }
    } catch (e: Exception) {
        false
    }

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    /** Short, human-comparable key id: first 8 bytes of SHA-256(public key), hex. */
    fun fingerprint(publicKey: ByteArray): String = sha256(publicKey).take(8).joinToString("") { "%02x".format(it) }
}

/** Something that can sign as one identity. On phones the private key never leaves the app's encrypted storage. */
interface Signer {
    val publicKey: ByteArray
    fun sign(data: ByteArray): ByteArray
}

class SoftwareSigner(private val keyPair: KeyPair) : Signer {
    override val publicKey: ByteArray = keyPair.public.encoded
    val privateKeyPkcs8: ByteArray get() = keyPair.private.encoded
    val fingerprint: String = Ec.fingerprint(publicKey)

    override fun sign(data: ByteArray): ByteArray = Ec.sign(keyPair.private, data)

    companion object {
        fun generate() = SoftwareSigner(Ec.generate())
        fun fromEncoded(privatePkcs8: ByteArray, publicX509: ByteArray) =
            SoftwareSigner(KeyPair(Ec.publicKey(publicX509), Ec.privateKey(privatePkcs8)))
    }
}

/**
 * Canonical byte string that signatures cover. Every field is written as `<byte length>:<bytes>;`, so field
 * boundaries are unambiguous and the encoding is trivial to reproduce outside Kotlin (backend/canon.py).
 * Backend-signed objects (certificates, answers, bulletins) only use [str]/[num]/[b64].
 */
class Canon(tag: String) {
    private val out = ByteArrayOutputStream()

    init { str(tag) }

    fun str(s: String) = bin(s.toByteArray(Charsets.UTF_8))
    fun num(n: Long) = str(n.toString())
    fun num(n: Int) = str(n.toString())
    fun flag(b: Boolean) = str(if (b) "1" else "0")
    fun b64(b: ByteArray) = str(Base64.getEncoder().encodeToString(b))

    fun bin(b: ByteArray): Canon {
        out.write("${b.size}:".toByteArray(Charsets.US_ASCII))
        out.write(b)
        out.write(';'.code)
        return this
    }

    fun build(): ByteArray = out.toByteArray()
}

fun ByteArray.b64(): String = Base64.getEncoder().encodeToString(this)
fun String.unb64(): ByteArray = Base64.getDecoder().decode(this)
