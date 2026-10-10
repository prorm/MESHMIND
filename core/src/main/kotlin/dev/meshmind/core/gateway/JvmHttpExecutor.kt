package dev.meshmind.core.gateway

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Real HTTPS executor using only the JDK (works unchanged on Android). Redirects are NOT followed, so an
 * allowlisted host cannot bounce the gateway to a non-allowlisted one; no caller-supplied headers exist.
 */
class JvmHttpExecutor(
    private val connectTimeoutMs: Int = 8_000,
    private val readTimeoutMs: Int = 10_000,
    private val userAgent: String = "MeshMind-Gateway/0.1",
) : InternetExecutor {

    override suspend fun get(url: String, maxBytes: Int): HttpResult = withContext(Dispatchers.IO) {
        val c = open(url)
        c.requestMethod = "GET"
        read(c, maxBytes)
    }

    override suspend fun postText(url: String, text: String, maxBytes: Int): HttpResult = withContext(Dispatchers.IO) {
        val c = open(url)
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
        c.outputStream.use { it.write(text.toByteArray()) }
        read(c, maxBytes)
    }

    private fun open(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        if (c.url.protocol != "https") throw IllegalArgumentException("HTTPS only")
        c.instanceFollowRedirects = false
        c.connectTimeout = connectTimeoutMs
        c.readTimeout = readTimeoutMs
        c.setRequestProperty("User-Agent", userAgent)
        c.setRequestProperty("Accept-Encoding", "identity")
        return c
    }

    private fun read(c: HttpURLConnection, maxBytes: Int): HttpResult = try {
        val code = c.responseCode
        val stream: InputStream? = if (code >= 400) c.errorStream else c.inputStream
        val out = ByteArrayOutputStream()
        var truncated = false
        stream?.use { s ->
            val buf = ByteArray(4096)
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                val room = maxBytes - out.size()
                if (n > room) { out.write(buf, 0, room); truncated = true; break }
                out.write(buf, 0, n)
            }
        }
        HttpResult(code, c.contentType ?: "", out.toByteArray(), truncated)
    } finally {
        c.disconnect()
    }
}
