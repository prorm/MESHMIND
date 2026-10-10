package dev.meshmind.core.light

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Collections
import java.util.Locale

/** Loopback-only USB bridge. It exposes no listener on Wi-Fi, Bluetooth, or the public Internet. */
class LightConsoleHttp(scope: CoroutineScope, private val assets: Map<String, Pair<String, ByteArray>>,
    requestedPort: Int = 8765, private val state: suspend () -> String,
    private val command: suspend (String) -> String) : AutoCloseable {
    private val server = ServerSocket(requestedPort, 8, InetAddress.getByName("127.0.0.1"))
    val port: Int = server.localPort
    val token: String = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    val url: String get() = "http://127.0.0.1:$port/#token=$token"
    private val clients = Collections.synchronizedSet(mutableSetOf<Socket>())
    private val permits = Semaphore(4)
    private val job = scope.launch(Dispatchers.IO) {
        while (isActive && !server.isClosed) {
            val socket = try { server.accept() } catch (e: Exception) { if (server.isClosed) break else throw e }
            socket.soTimeout = 3000
            if (!permits.tryAcquire()) { socket.close(); continue }
            clients.add(socket)
            launch {
                try { socket.use { handle(it) } } finally { clients.remove(socket); permits.release() }
            }
        }
    }

    private suspend fun handle(socket: Socket) {
        try {
            val input = socket.getInputStream()
            val first = line(input, 2048).split(' ')
            require(first.size == 3 && first[2] == "HTTP/1.1") { "Invalid request" }
            val method = first[0]; val path = first[1]
            val headers = mutableMapOf<String, String>()
            var headerBytes = 0
            while (true) {
                val line = line(input, 2048)
                headerBytes += line.length + 2
                require(headerBytes <= 8192) { "Headers too large" }
                if (line.isEmpty()) break
                val delimiter = line.indexOf(':')
                require(delimiter > 0)
                val key = line.substring(0, delimiter).lowercase(Locale.ROOT)
                require(key !in headers) { "Duplicate header" }
                headers[key] = line.substring(delimiter + 1).trim()
            }
            val allowedHosts = setOf("127.0.0.1:$port", "localhost:$port")
            if (headers["host"] !in allowedHosts || headers["origin"]?.let { it !in allowedHosts.map { h -> "http://$h" } } == true) {
                respond(socket, 403, "application/json", "{\"error\":\"Origin refused\"}".toByteArray()); return
            }
            if (method == "GET" && path in assets) {
                val (type, bytes) = assets.getValue(path)
                respond(socket, 200, type, bytes); return
            }
            val supplied = headers["authorization"]?.removePrefix("Bearer ") ?: ""
            if (!MessageDigest.isEqual(token.toByteArray(), supplied.toByteArray())) {
                respond(socket, 401, "application/json", "{\"error\":\"Enable laptop control on the host phone and use its pairing URL\"}".toByteArray()); return
            }
            require("transfer-encoding" !in headers) { "Chunked requests are not supported" }
            when {
                method == "GET" && path == "/api/state" -> respond(socket, 200, "application/json", state().toByteArray(Charsets.UTF_8))
                method == "POST" && path == "/api/command" -> {
                    require(headers["content-type"]?.substringBefore(';')?.trim() == "application/json") { "JSON required" }
                    val length = requireNotNull(headers["content-length"]?.toIntOrNull()) { "Content length required" }
                    require(length in 1..16_384) { "Body too large" }
                    val body = ByteArray(length)
                    var count = 0
                    while (count < length) { val n = input.read(body, count, length - count); require(n > 0); count += n }
                    respond(socket, 200, "application/json", command(body.toString(Charsets.UTF_8)).toByteArray(Charsets.UTF_8))
                }
                else -> respond(socket, 404, "application/json", "{\"error\":\"Unknown endpoint\"}".toByteArray())
            }
        } catch (e: CancellationException) { throw e }
        catch (e: IllegalArgumentException) {
            runCatching { respond(socket, 400, "application/json", "{\"error\":\"Invalid command or request\"}".toByteArray()) }
        } catch (e: Exception) {
            runCatching { respond(socket, 500, "application/json", "{\"error\":\"Controller unavailable\"}".toByteArray()) }
        }
    }

    private fun line(input: InputStream, maximum: Int): String {
        val result = StringBuilder()
        while (result.length <= maximum) {
            val byte = input.read(); require(byte >= 0)
            if (byte == 10) return result.toString().removeSuffix("\r")
            result.append(byte.toChar())
        }
        throw IllegalArgumentException("Line too long")
    }

    private fun respond(socket: Socket, status: Int, type: String, body: ByteArray) {
        val reason = mapOf(200 to "OK", 400 to "Bad Request", 401 to "Unauthorized", 403 to "Forbidden", 404 to "Not Found", 500 to "Server Error").getValue(status)
        val headers = "HTTP/1.1 $status $reason\r\nContent-Type: $type; charset=utf-8\r\nContent-Length: ${body.size}\r\n" +
            "Connection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\n" +
            "Content-Security-Policy: default-src 'self'; style-src 'self' 'unsafe-inline'; frame-ancestors 'none'\r\n\r\n"
        socket.getOutputStream().apply { write(headers.toByteArray(Charsets.US_ASCII)); write(body); flush() }
    }

    override fun close() {
        server.close(); synchronized(clients) { clients.toList().forEach { runCatching { it.close() } } }
        job.cancel()
    }
}
