package dev.meshmind.core

import dev.meshmind.core.light.LightConsoleHttp
import kotlinx.coroutines.*
import java.net.Socket
import kotlin.test.*

class LightConsoleHttpTest {
    private fun scenario(block: (LightConsoleHttp) -> Unit) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val server = LightConsoleHttp(scope, mapOf("/" to ("text/html" to "console".toByteArray())), 0,
            state = { "{\"hosting\":false}" }, command = { "{\"ok\":true}" })
        try { block(server) } finally { server.close(); scope.cancel() }
    }
    private fun request(server: LightConsoleHttp, path: String, auth: Boolean = true,
        origin: String? = null, host: String = "127.0.0.1:${server.port}", body: String? = null, length: Int? = null): String =
        Socket("127.0.0.1", server.port).use { socket ->
            socket.soTimeout = 5000
            val headers = buildString {
                append("${if (body == null) "GET" else "POST"} $path HTTP/1.1\r\nHost: $host\r\n")
                if (auth) append("Authorization: Bearer ${server.token}\r\n")
                if (origin != null) append("Origin: $origin\r\n")
                if (body != null) append("Content-Type: application/json\r\nContent-Length: ${length ?: body.toByteArray().size}\r\n")
                append("\r\n"); if (body != null) append(body)
            }
            socket.getOutputStream().write(headers.toByteArray())
            socket.getInputStream().readBytes().toString(Charsets.UTF_8)
        }

    @Test fun pairedLoopbackBrowserCanReadStateAndIssueCommands() = scenario { server ->
        assertTrue(request(server, "/", auth = false).endsWith("console"))
        assertTrue(request(server, "/api/state").startsWith("HTTP/1.1 200"))
        assertTrue(request(server, "/api/command", body = "{\"action\":\"blackout\"}").endsWith("{\"ok\":true}"))
        assertTrue(request(server, "/api/state").contains("frame-ancestors 'none'"))
    }

    @Test fun missingPairingCrossOriginHostRebindingAndOversizedBodiesAreRefused() = scenario { server ->
        assertTrue(request(server, "/api/state", auth = false).startsWith("HTTP/1.1 401"))
        assertTrue(request(server, "/api/state", origin = "https://attacker.example").startsWith("HTTP/1.1 403"))
        assertTrue(request(server, "/api/state", host = "attacker.example:${server.port}").startsWith("HTTP/1.1 403"))
        assertTrue(request(server, "/api/command", body = "{}", length = 16_385).startsWith("HTTP/1.1 400"))
    }
}
