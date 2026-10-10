package dev.meshmind.app.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import dev.meshmind.core.security.Certificate
import dev.meshmind.core.security.NodeIdentity
import dev.meshmind.core.security.SoftwareSigner
import dev.meshmind.core.security.b64
import dev.meshmind.core.security.unb64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * This phone's signing key, its certificate and the authorities it trusts.
 *
 * The EC signing key is used in software (signing every packet through the hardware keystore would add
 * milliseconds per packet under the router lock) but is stored encrypted with an AES-GCM key that lives in the
 * Android Keystore and never leaves it.
 */
class IdentityStore(context: Context) {
    private val prefs = context.getSharedPreferences("meshmind_identity", Context.MODE_PRIVATE)

    val signer: SoftwareSigner by lazy { loadOrCreateSigner() }

    /** Enrolled certificate if it still matches this node ID and key, else a fresh self-signed one. */
    fun identityFor(nodeId: String, now: Long): NodeIdentity {
        val cert = enrolledCertificate()?.takeIf { it.nodeId == nodeId && it.publicKey.contentEquals(signer.publicKey) && it.expiresAt > now }
        return NodeIdentity(signer, cert ?: Certificate.selfSigned(nodeId, signer, now))
    }

    fun enrolledCertificate(): Certificate? =
        prefs.getString("cert", null)?.let { runCatching { Certificate.decode(it.unb64()) }.getOrNull() }

    fun anchors(): List<ByteArray> = prefs.getStringSet("anchors", emptySet())!!.mapNotNull { runCatching { it.unb64() }.getOrNull() }

    fun saveEnrollment(cert: Certificate, authorityKey: ByteArray) {
        prefs.edit()
            .putString("cert", cert.encode().b64())
            .putStringSet("anchors", (prefs.getStringSet("anchors", emptySet())!! + authorityKey.b64()))
            .apply()
    }

    fun forgetEnrollment() {
        prefs.edit().remove("cert").remove("anchors").apply()
    }

    // ---- key storage -------------------------------------------------------------------------

    private fun loadOrCreateSigner(): SoftwareSigner {
        val pub = prefs.getString("pub", null)
        val wrapped = prefs.getString("priv", null)
        if (pub != null && wrapped != null) {
            runCatching { return SoftwareSigner.fromEncoded(unwrap(wrapped), pub.unb64()) }
                .onFailure { Log.w("MeshMind", "stored identity key unreadable; creating a new one", it) }
        }
        val s = SoftwareSigner.generate()
        prefs.edit().putString("pub", s.publicKey.b64()).putString("priv", wrap(s.privateKeyPkcs8)).remove("cert").apply()
        return s
    }

    private fun wrappingKey(): SecretKey? = runCatching {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(WRAP_ALIAS, null) as SecretKey?) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(WRAP_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
            generateKey()
        }
    }.onFailure { Log.w("MeshMind", "Android Keystore unavailable; identity key stored unwrapped in app-private storage", it) }.getOrNull()

    private fun wrap(plain: ByteArray): String {
        val key = wrappingKey() ?: return "raw:" + plain.b64()
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        return "gcm:" + c.iv.b64() + ":" + c.doFinal(plain).b64()
    }

    private fun unwrap(stored: String): ByteArray {
        if (stored.startsWith("raw:")) return stored.removePrefix("raw:").unb64()
        val (_, iv, data) = stored.split(":")
        val key = requireNotNull(wrappingKey()) { "keystore key missing" }
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv.unb64())) }
        return c.doFinal(data.unb64())
    }

    private companion object {
        const val WRAP_ALIAS = "meshmind_identity_wrap"
    }
}
