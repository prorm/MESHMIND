package dev.meshmind.core.gateway

import dev.meshmind.core.model.Answer
import dev.meshmind.core.model.AnswerKind
import dev.meshmind.core.model.AnswerSource
import dev.meshmind.core.model.Bulletin
import dev.meshmind.core.model.BulletinItem
import dev.meshmind.core.model.BulletinKind
import dev.meshmind.core.model.NodeId
import dev.meshmind.core.sage.MiniJson
import dev.meshmind.core.security.Certificate
import dev.meshmind.core.security.Role
import dev.meshmind.core.security.b64
import dev.meshmind.core.security.unb64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class BackendException(message: String) : Exception(message)

/**
 * Client for the MeshMind backend (backend/ in this repo): enrollment, answers and bulletins. Only phones with
 * Internet talk to it: gateways (answers, bulletins) and phones enrolling while online. The base URL is set by
 * the phone's owner, so plain http is allowed for a laptop on the same Wi-Fi during demos (debug builds only
 * permit cleartext; release builds require https).
 */
class BackendClient(
    private val baseUrl: () -> String,
    private val connectTimeoutMs: Int = 8_000,
    private val readTimeoutMs: Int = 45_000,
) : AnswerService {

    data class Enrollment(val certificate: Certificate, val authorityKey: ByteArray, val message: String)

    suspend fun authorityKey(): ByteArray = (get("/v1/authority")["publicKey"] as String).unb64()

    /** Starts phone verification; in the backend's demo mode the OTP comes back in [String] (otherwise null). */
    suspend fun enrollStart(phone: String): String? =
        post("/v1/enroll/start", mapOf("phone" to phone))["demoCode"] as String?

    suspend fun enrollVerify(phone: String, code: String, nodeId: NodeId, publicKey: ByteArray, role: Role, organizerCode: String): Enrollment {
        val r = post("/v1/enroll/verify", mapOf(
            "phone" to phone, "code" to code, "nodeId" to nodeId, "publicKey" to publicKey.b64(),
            "role" to role.name, "organizerCode" to organizerCode,
        ))
        @Suppress("UNCHECKED_CAST")
        val cert = parseCertificate(r["certificate"] as Map<String, Any?>)
        return Enrollment(cert, (r["authorityKey"] as String).unb64(), r["message"] as String? ?: "")
    }

    override suspend fun ask(query: String, requester: NodeId): Answer =
        parseAnswer(post("/v1/ask", mapOf("query" to query, "requester" to requester)))

    override suspend fun read(url: String, requester: NodeId): Answer =
        parseAnswer(post("/v1/read", mapOf("url" to url, "requester" to requester)))

    suspend fun bulletin(lat: Double?, lon: Double?): Bulletin {
        val q = if (lat != null && lon != null) "?lat=$lat&lon=$lon" else ""
        return parseBulletin(get("/v1/bulletin$q"))
    }

    // ---- parsing ---------------------------------------------------------------------------

    internal fun parseCertificate(c: Map<String, Any?>) = Certificate(
        nodeId = c["nodeId"] as String,
        publicKey = (c["publicKey"] as String).unb64(),
        role = Role.valueOf(c["role"] as String),
        phoneVerified = c["phoneVerified"] as Boolean,
        issuedAt = (c["issuedAt"] as Double).toLong(),
        expiresAt = (c["expiresAt"] as Double).toLong(),
        issuer = c["issuer"] as String,
        signature = (c["signature"] as String).unb64(),
    )

    @Suppress("UNCHECKED_CAST")
    internal fun parseAnswer(m: Map<String, Any?>): Answer = Answer(
        kind = AnswerKind.valueOf(m["kind"] as String),
        query = m["query"] as String,
        text = m["text"] as String,
        sources = (m["sources"] as List<Map<String, Any?>>).map { AnswerSource(it["title"] as String, it["url"] as String) },
        createdAt = (m["createdAt"] as Double).toLong(),
        expiresAt = (m["expiresAt"] as Double).toLong(),
        provider = m["provider"] as String,
        keyId = m["keyId"] as String,
        signature = (m["signature"] as String).unb64(),
    )

    @Suppress("UNCHECKED_CAST")
    internal fun parseBulletin(m: Map<String, Any?>): Bulletin = Bulletin(
        boardId = m["boardId"] as String,
        title = m["title"] as String,
        seq = (m["seq"] as Double).toLong(),
        issuedAt = (m["issuedAt"] as Double).toLong(),
        items = (m["items"] as List<Map<String, Any?>>).map {
            BulletinItem(
                BulletinKind.valueOf(it["kind"] as String), (it["severity"] as Double).toInt(), it["title"] as String,
                it["body"] as String, it["source"] as String, (it["issuedAt"] as Double).toLong(), (it["expiresAt"] as Double).toLong(),
            )
        },
        keyId = m["keyId"] as String,
        signature = (m["signature"] as String).unb64(),
    )

    // ---- HTTP ------------------------------------------------------------------------------

    private suspend fun get(path: String): Map<String, Any?> = call("GET", path, null)
    private suspend fun post(path: String, body: Map<String, Any?>): Map<String, Any?> = call("POST", path, toJson(body))

    @Suppress("UNCHECKED_CAST")
    private suspend fun call(method: String, path: String, body: String?): Map<String, Any?> = withContext(Dispatchers.IO) {
        val base = baseUrl().trim().trimEnd('/')
        if (base.isEmpty()) throw BackendException("no backend URL configured")
        val c = URL(base + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.instanceFollowRedirects = false
            c.connectTimeout = connectTimeoutMs
            c.readTimeout = readTimeoutMs
            c.setRequestProperty("Accept", "application/json")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            val text = (if (code >= 400) c.errorStream else c.inputStream)?.use { s ->
                val out = ByteArrayOutputStream()
                s.copyTo(out)
                out.toString(Charsets.UTF_8.name())
            } ?: ""
            val parsed = runCatching { MiniJson.parse(text) as Map<String, Any?> }.getOrNull()
            if (code >= 400) throw BackendException(parsed?.get("error") as String? ?: "backend HTTP $code")
            parsed ?: throw BackendException("backend returned invalid JSON")
        } finally {
            c.disconnect()
        }
    }

    companion object {
        fun toJson(v: Any?): String = when (v) {
            null -> "null"
            is String -> buildString {
                append('"')
                v.forEach { ch ->
                    when {
                        ch == '"' -> append("\\\"")
                        ch == '\\' -> append("\\\\")
                        ch == '\n' -> append("\\n")
                        ch == '\r' -> append("\\r")
                        ch == '\t' -> append("\\t")
                        ch < ' ' -> append("\\u%04x".format(ch.code))
                        else -> append(ch)
                    }
                }
                append('"')
            }
            is Number, is Boolean -> v.toString()
            is Map<*, *> -> v.entries.joinToString(",", "{", "}") { toJson(it.key.toString()) + ":" + toJson(it.value) }
            is List<*> -> v.joinToString(",", "[", "]") { toJson(it) }
            else -> toJson(v.toString())
        }

        fun encodeQuery(s: String): String = URLEncoder.encode(s, "UTF-8")
    }
}
