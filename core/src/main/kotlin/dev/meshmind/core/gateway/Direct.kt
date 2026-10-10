package dev.meshmind.core.gateway

import dev.meshmind.core.model.Answer
import dev.meshmind.core.model.AnswerKind
import dev.meshmind.core.model.AnswerSource
import dev.meshmind.core.model.Bulletin
import dev.meshmind.core.model.BulletinItem
import dev.meshmind.core.model.BulletinKind
import dev.meshmind.core.model.NodeId
import dev.meshmind.core.sage.MiniJson
import dev.meshmind.core.security.NodeIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.URL
import kotlin.math.roundToInt

data class HttpReply(val code: Int, val body: String, val headers: Map<String, String> = emptyMap())

/** The only way the direct gateway touches the network (injectable for tests). */
fun interface HttpCall {
    suspend fun call(method: String, url: String, headers: Map<String, String>, body: String?): HttpReply
}

/** JDK HttpURLConnection (works unchanged on Android). Never follows redirects; reads at most [maxBytes]. */
class JvmHttpCall(private val maxBytes: Int = 2_000_000, private val timeoutMs: Int = 30_000) : HttpCall {
    override suspend fun call(method: String, url: String, headers: Map<String, String>, body: String?): HttpReply = withContext(Dispatchers.IO) {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.instanceFollowRedirects = false
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) {
                c.doOutput = true
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            val out = ByteArrayOutputStream()
            (if (code >= 400) c.errorStream else c.inputStream)?.use { s ->
                val buf = ByteArray(8192)
                while (out.size() < maxBytes) {
                    val n = s.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, minOf(n, maxBytes - out.size()))
                }
            }
            val charset = Regex("charset=([\\w-]+)").find(c.contentType ?: "")?.groupValues?.get(1) ?: "UTF-8"
            HttpReply(code, String(out.toByteArray(), runCatching { charset(charset) }.getOrDefault(Charsets.UTF_8)),
                c.headerFields.filterKeys { it != null }.mapValues { it.value.joinToString(",") } + ("Content-Type" to (c.contentType ?: "")))
        } finally {
            c.disconnect()
        }
    }
}

/**
 * Answers questions on the gateway phone itself, with no MeshMind server: Gemini for questions, plain-text
 * extraction for pages. Answers are signed with the gateway's own key and carry its certificate, so every phone can
 * still check them and cache them; the UI says which gateway vouches for them.
 *
 * The API key lives on the gateway phone. Fine for demos and an event's own staff phones; for strangers' phones a
 * server keeps the key (see backend/).
 */
class DirectAnswerService(
    private val identity: () -> NodeIdentity,
    private val apiKey: () -> String,
    private val model: () -> String = { DEFAULT_MODEL },
    private val context: String = "They are at a large event in India.",
    private val clock: () -> Long = System::currentTimeMillis,
    private val http: HttpCall = JvmHttpCall(),
) : AnswerService {
    @Volatile private var groundingOffUntil = 0L

    override suspend fun ask(query: String, requester: NodeId): Answer {
        val key = apiKey().trim()
        require(key.isNotEmpty()) { "no Gemini API key on this gateway" }
        var grounded = clock() >= groundingOffUntil
        var reply = gemini(query, grounded, key)
        if (reply.code == 401 || reply.code == 403) throw IllegalStateException("Gemini rejected the API key (HTTP ${reply.code})")
        if (reply.code != 200 && grounded) {
            // Free-tier keys get 429 for Google Search grounding: answer without it and stop trying for a while.
            groundingOffUntil = clock() + 15 * 60_000L
            grounded = false
            reply = gemini(query, false, key)
        }
        if (reply.code != 200) throw IllegalStateException("Gemini error ${reply.code}: ${reply.body.take(200)}")
        @Suppress("UNCHECKED_CAST")
        val cand = ((MiniJson.parse(reply.body) as Map<String, Any?>)["candidates"] as List<Map<String, Any?>>?)?.firstOrNull() ?: emptyMap()
        val blocked = cand["finishReason"] in setOf("SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST")
        @Suppress("UNCHECKED_CAST")
        val parts = ((cand["content"] as Map<String, Any?>?)?.get("parts") as List<Map<String, Any?>>?).orEmpty()
        var text = if (blocked) "I can't help with that request here. Try asking it differently." else
            parts.filter { it["thought"] != true }.joinToString("") { it["text"] as String? ?: "" }
        text = text.replace(Regex("\\s+"), " ").replace(Regex("[*_#`]+"), "").trim()
        if (text.length > MAX_ANSWER_CHARS) text = text.take(MAX_ANSWER_CHARS - 1).substringBeforeLast(' ') + "…"
        @Suppress("UNCHECKED_CAST")
        val chunks = ((cand["groundingMetadata"] as Map<String, Any?>?)?.get("groundingChunks") as List<Map<String, Any?>>?).orEmpty()
        val sources = chunks.mapNotNull { c ->
            @Suppress("UNCHECKED_CAST") val web = c["web"] as Map<String, Any?>? ?: return@mapNotNull null
            val uri = web["uri"] as String? ?: return@mapNotNull null
            AnswerSource(((web["title"] as String?) ?: "source").take(120), resolveRedirect(uri).take(500))
        }.take(3)
        val now = clock()
        val provider = model() + if (grounded) "" else " (no web search)"
        return sign(AnswerKind.ASK, query, text.ifEmpty { "No answer found." }, sources, now, if (grounded) 30 * 60_000L else 10 * 60_000L, provider)
    }

    override suspend fun read(url: String, requester: NodeId): Answer {
        val uri = URI(url)
        require(uri.scheme == "https" && !uri.host.isNullOrEmpty()) { "HTTPS URLs only" }
        require(publicHost(uri.host)) { "that host is not reachable from here" }
        val r = http.call("GET", url, mapOf("User-Agent" to "MeshMind-Gateway/0.2 (offline mesh relay)"), null)
        require(r.code in 200..299) { "page returned HTTP ${r.code}" }
        val (title, text) = PageText.extract(r.body)
        return sign(AnswerKind.READ, url, text.ifEmpty { "(no readable text on this page)" }, listOf(AnswerSource((title.ifEmpty { url }).take(120), url)),
            clock(), 60 * 60_000L, "gateway reader")
    }

    private suspend fun gemini(query: String, grounded: Boolean, key: String): HttpReply {
        val system = buildList {
            add(mapOf("text" to SYSTEM_PROMPT.replace("{context}", context)))
            if (!grounded) add(mapOf("text" to NO_SEARCH_RULE))
        }
        val body = buildMap<String, Any?> {
            put("systemInstruction", mapOf("parts" to system))
            put("contents", listOf(mapOf("role" to "user", "parts" to listOf(mapOf("text" to query)))))
            put("generationConfig", mapOf("maxOutputTokens" to 400, "temperature" to 0.3))
            if (grounded) put("tools", listOf(mapOf("google_search" to emptyMap<String, Any>())))
        }
        return http.call("POST", "https://generativelanguage.googleapis.com/v1beta/models/${model()}:generateContent",
            mapOf("Content-Type" to "application/json", "x-goog-api-key" to key), BackendClient.toJson(body))
    }

    /** Grounding links are Google redirect URLs; resolve once so phones can open the real page with Read. */
    private suspend fun resolveRedirect(uri: String): String {
        if (!uri.contains("grounding-api-redirect")) return uri
        return runCatching { http.call("HEAD", uri, emptyMap(), null).headers.entries.firstOrNull { it.key.equals("Location", true) }?.value }
            .getOrNull()?.takeIf { it.startsWith("https://") } ?: uri
    }

    private fun sign(kind: AnswerKind, query: String, text: String, sources: List<AnswerSource>, now: Long, ttlMs: Long, provider: String): Answer {
        val id = identity()
        val keyId = Bulletin.NODE_KEY_PREFIX + id.nodeId
        val unsigned = Answer(kind, query, text, sources, now, now + ttlMs, provider, keyId, ByteArray(0))
        return Answer(kind, query, text, sources, now, now + ttlMs, provider, keyId, id.sign(unsigned.signedBytes()), id.certBytes)
    }

    companion object {
        /** Fast free-tier model (measured ~1.4 s); 2.5-flash-lite is closed to new keys, 3.1-flash-lite took ~20 s. */
        const val DEFAULT_MODEL = "gemini-3.5-flash-lite"
        const val MAX_ANSWER_CHARS = 700

        val SYSTEM_PROMPT = """You answer questions from people whose phones have no Internet: they are at a packed event or in an area where the mobile network failed, and your answer travels back to them over a slow Bluetooth mesh of strangers' phones. {context}

Write at most 80 words of plain text: no markdown, no lists, no preamble. Lead with the direct answer, then one practical next step if useful. Prefer official and local sources and say how current the information is when it matters. If you are unsure or the information may be stale, say so briefly rather than guessing. If someone describes an emergency, tell them to press SOS in MeshMind and call 112 (or the local emergency number) as soon as any phone has signal."""

        const val NO_SEARCH_RULE = "You have NO web access and do not know today's date or any live status. Never say something is open, " +
            "running, closed or happening today, and never state a date as current. Give general, stable facts only (locations, usual " +
            "timings, what to do), and say plainly that you could not check live status."

        /** Blocks private, loopback and link-local addresses so a request can't probe the gateway's own network. */
        fun publicHost(host: String): Boolean = runCatching {
            InetAddress.getAllByName(host).none { it.isSiteLocalAddress || it.isLoopbackAddress || it.isLinkLocalAddress || it.isAnyLocalAddress || it.isMulticastAddress }
        }.getOrDefault(false)
    }
}

/** Minimal readable-text extraction (the backend's reader, ported): drop scripts/navigation, keep paragraphs. */
object PageText {
    private val skip = Regex("(?is)<(script|style|noscript|nav|footer|header|svg|form|aside|iframe)\\b.*?</\\1\\s*>")
    private val block = Regex("(?i)<(p|div|br|li|h[1-6]|tr|section|article)\\b[^>]*>")
    private val tag = Regex("(?s)<[^>]+>")
    private val title = Regex("(?is)<title[^>]*>(.*?)</title>")
    private val entities = mapOf("&amp;" to "&", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"", "&#39;" to "'", "&apos;" to "'", "&nbsp;" to " ")

    fun extract(html: String, maxChars: Int = 6000): Pair<String, String> {
        val t = title.find(html)?.groupValues?.get(1)?.let(::unescape)?.trim().orEmpty()
        var s = html.replace(Regex("(?is)<head\\b.*?</head>"), " ").replace(skip, " ").replace(block, "\n").replace(tag, " ")
        s = unescape(s)
        val text = s.lines().map { it.replace(Regex("[ \\t\\r\\u000B\\f]+"), " ").trim() }.filter { it.length > 1 }.joinToString("\n")
            .replace(Regex("\n{3,}"), "\n\n").trim()
        return t to if (text.length > maxChars) text.take(maxChars - 1).substringBeforeLast(' ') + "…" else text
    }

    private fun unescape(s: String): String {
        var r = s
        entities.forEach { (k, v) -> r = r.replace(k, v) }
        return r.replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value }
    }
}

/** Weather for the live-info board straight from Open-Meteo (no key needed), as the backend does. */
object OpenMeteo {
    private val codes = mapOf(
        0 to "clear sky", 1 to "mainly clear", 2 to "partly cloudy", 3 to "overcast", 45 to "fog", 48 to "fog",
        51 to "light drizzle", 53 to "drizzle", 55 to "heavy drizzle", 61 to "light rain", 63 to "rain", 65 to "heavy rain",
        80 to "rain showers", 81 to "rain showers", 82 to "violent rain showers", 95 to "thunderstorm",
        96 to "thunderstorm with hail", 99 to "severe thunderstorm with hail",
    )

    suspend fun weather(lat: Double, lon: Double, now: Long, http: HttpCall = JvmHttpCall()): List<BulletinItem> {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f".format(java.util.Locale.US, lat, lon) +
            "&current=temperature_2m,weather_code,wind_speed_10m&hourly=precipitation_probability&forecast_hours=6&timezone=auto"
        val r = http.call("GET", url, emptyMap(), null)
        require(r.code == 200) { "Open-Meteo HTTP ${r.code}" }
        @Suppress("UNCHECKED_CAST")
        val d = MiniJson.parse(r.body) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val cur = d["current"] as Map<String, Any?>
        val code = (cur["weather_code"] as Double?)?.toInt() ?: -1
        @Suppress("UNCHECKED_CAST")
        val hourly = d["hourly"] as Map<String, Any?>?
        @Suppress("UNCHECKED_CAST")
        val times = (hourly?.get("time") as List<String>?).orEmpty()
        @Suppress("UNCHECKED_CAST")
        val probs = (hourly?.get("precipitation_probability") as List<Double?>?).orEmpty()
        val rain = times.zip(probs).firstOrNull { (_, p) -> p != null && p >= 50 }?.let { (t, p) -> " Rain likely around ${t.takeLast(5)} (${p!!.toInt()}%)." } ?: ""
        val temp = (cur["temperature_2m"] as Double).roundToInt()
        val wind = ((cur["wind_speed_10m"] as Double?) ?: 0.0).roundToInt()
        return listOf(BulletinItem(
            BulletinKind.WEATHER, if (code >= 95) 2 else if (rain.isNotEmpty()) 1 else 0,
            "$temp°C, ${codes[code] ?: "weather"}", "Wind $wind km/h.$rain", "Open-Meteo", now, now + 90 * 60_000L,
        ))
    }
}
