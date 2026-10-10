package dev.meshmind.core.gateway

import dev.meshmind.core.model.Answer
import dev.meshmind.core.model.InetKind
import dev.meshmind.core.model.InetRequest
import dev.meshmind.core.model.InetResponse
import dev.meshmind.core.model.InetStatus
import dev.meshmind.core.model.NodeId
import dev.meshmind.core.model.WireCodec
import kotlinx.coroutines.CancellationException
import java.net.URI

/**
 * Gateway-side safety configuration. The gateway is a narrow relay, NOT an open proxy:
 * HTTPS only, allowlisted hosts only, small fixed request shapes, capped responses, rate limits,
 * and nothing happens unless the gateway's owner explicitly consented.
 */
data class GatewayConfig(
    /** Explicit user consent on the gateway phone. Without it every request is rejected. */
    val consent: Boolean = false,
    /** Hosts (and their subdomains) that HTTP_GET may reach. */
    val allowedDomains: Set<String> = DEFAULT_ALLOWLIST,
    /** Where WEBHOOK_POST messages go. The requester can never choose this URL. */
    val webhookUrl: String = "",
    val maxResponseBytes: Int = 16 * 1024,
    val maxUrlLength: Int = 512,
    val maxPostChars: Int = 1024,
    val perRequesterPerMinute: Int = 6,
    val globalPerMinute: Int = 30,
    val maxQueryChars: Int = 300,
) {
    companion object {
        val DEFAULT_ALLOWLIST = setOf(
            "example.com", "httpbin.org", "api.open-meteo.com", "wttr.in", "ntfy.sh", "webhook.site",
            "en.wikipedia.org", "api.github.com",
        )
    }
}

sealed class PolicyDecision {
    data object Allow : PolicyDecision()
    data class Reject(val reason: String) : PolicyDecision()
}

/** Fixed-window per-requester and global rate limiter. */
class RateLimiter(private val windowMs: Long = 60_000) {
    private val hits = HashMap<String, ArrayDeque<Long>>()

    @Synchronized
    fun tryAcquire(key: String, limit: Int, now: Long): Boolean {
        val q = hits.getOrPut(key) { ArrayDeque() }
        while (q.isNotEmpty() && q.first() <= now - windowMs) q.removeFirst()
        if (q.size >= limit) return false
        q.addLast(now)
        return true
    }
}

class GatewayPolicy(private val config: () -> GatewayConfig) {
    private val limiter = RateLimiter()

    /** Validation that does not consume rate-limit budget. */
    fun validate(request: InetRequest): PolicyDecision {
        val cfg = config()
        if (!cfg.consent) return PolicyDecision.Reject("gateway owner has not consented to relay Internet requests")
        return when (request.kind) {
            InetKind.HTTP_GET -> {
                if (request.body.isNotEmpty()) return PolicyDecision.Reject("GET must not carry a body")
                checkUrl(request.url, cfg)
            }
            InetKind.ASK -> {
                if (request.body.isBlank()) return PolicyDecision.Reject("empty question")
                if (request.body.length > cfg.maxQueryChars) return PolicyDecision.Reject("question too long (${request.body.length} > ${cfg.maxQueryChars} chars)")
                PolicyDecision.Allow
            }
            InetKind.READ -> {
                // The backend fetches the page, not the gateway phone, so any public HTTPS page is acceptable here.
                if (request.url.length > cfg.maxUrlLength) return PolicyDecision.Reject("URL too long (${request.url.length} > ${cfg.maxUrlLength})")
                val uri = runCatching { URI(request.url) }.getOrNull() ?: return PolicyDecision.Reject("malformed URL")
                if (!"https".equals(uri.scheme, ignoreCase = true) || uri.host.isNullOrEmpty()) return PolicyDecision.Reject("HTTPS only")
                if (uri.rawUserInfo != null) return PolicyDecision.Reject("credentials in URL not allowed")
                PolicyDecision.Allow
            }
            InetKind.WEBHOOK_POST -> {
                if (request.url.isNotEmpty()) return PolicyDecision.Reject("requester may not choose the webhook URL")
                if (request.body.isBlank()) return PolicyDecision.Reject("empty message")
                if (request.body.length > cfg.maxPostChars) {
                    return PolicyDecision.Reject("message too large (${request.body.length} > ${cfg.maxPostChars} chars)")
                }
                if (cfg.webhookUrl.isBlank()) return PolicyDecision.Reject("gateway has no webhook configured")
                checkUrl(cfg.webhookUrl, cfg.copy(allowedDomains = cfg.allowedDomains + hostOf(cfg.webhookUrl)))
            }
        }
    }

    /**
     * Admission at the gateway: validation plus the per-requester abuse cap. The global budget is not consumed
     * here; the [GatewayScheduler] spends it in need order.
     */
    fun admit(requester: NodeId, request: InetRequest, now: Long): PolicyDecision {
        val v = validate(request)
        if (v is PolicyDecision.Reject) return v
        val cfg = config()
        if (!limiter.tryAcquire("req:$requester", cfg.perRequesterPerMinute, now)) {
            return PolicyDecision.Reject("rate limit: max ${cfg.perRequesterPerMinute}/min per requester")
        }
        return PolicyDecision.Allow
    }

    fun check(requester: NodeId, request: InetRequest, now: Long): PolicyDecision {
        val a = admit(requester, request, now)
        if (a is PolicyDecision.Reject) return a
        val cfg = config()
        if (!limiter.tryAcquire("global", cfg.globalPerMinute, now)) {
            return PolicyDecision.Reject("rate limit: gateway busy (${cfg.globalPerMinute}/min)")
        }
        return PolicyDecision.Allow
    }

    private fun hostOf(url: String): String = runCatching { URI(url).host?.lowercase() ?: "" }.getOrDefault("")

    private fun checkUrl(url: String, cfg: GatewayConfig): PolicyDecision {
        if (url.length > cfg.maxUrlLength) return PolicyDecision.Reject("URL too long (${url.length} > ${cfg.maxUrlLength})")
        val uri = runCatching { URI(url) }.getOrNull() ?: return PolicyDecision.Reject("malformed URL")
        if (!"https".equals(uri.scheme, ignoreCase = true)) return PolicyDecision.Reject("HTTPS only")
        if (uri.rawUserInfo != null) return PolicyDecision.Reject("credentials in URL not allowed")
        if (uri.port != -1 && uri.port != 443) return PolicyDecision.Reject("non-standard port not allowed")
        val host = uri.host?.lowercase() ?: return PolicyDecision.Reject("missing host")
        val allowed = cfg.allowedDomains.any { d -> host == d || host.endsWith(".$d") }
        return if (allowed) PolicyDecision.Allow else PolicyDecision.Reject("host '$host' is not on the gateway allowlist")
    }
}

data class HttpResult(
    val code: Int,
    val contentType: String,
    val body: ByteArray,
    val truncated: Boolean,
)

/** The only way the gateway touches the Internet. */
interface InternetExecutor {
    suspend fun get(url: String, maxBytes: Int): HttpResult
    suspend fun postText(url: String, text: String, maxBytes: Int): HttpResult
}

/**
 * Answers questions and page reads. Implemented by the MeshMind backend (search + AI, provider-agnostic); the
 * gateway only relays, so API keys never live on phones and every answer arrives authority-signed.
 */
interface AnswerService {
    suspend fun ask(query: String, requester: NodeId): Answer
    suspend fun read(url: String, requester: NodeId): Answer
}

/** Executes allowed requests and always produces a response (OK / REJECTED / ERROR) for the requester. */
class GatewayService(
    val selfId: NodeId,
    private val config: () -> GatewayConfig,
    private val executor: InternetExecutor,
    /** Consent AND validated connectivity (or the manual demo override). */
    val isActive: () -> Boolean,
    private val answers: () -> AnswerService? = { null },
) {
    val policy = GatewayPolicy(config)

    fun response(requestId: String, requestRoute: List<NodeId>, status: InetStatus, msg: String, now: Long) = InetResponse(
        requestId = requestId, status = status, httpCode = 0, contentType = "text/plain", body = ByteArray(0), truncated = false,
        message = msg, gatewayId = selfId, requestRoute = requestRoute, executedAt = now,
    )

    /** Upstream execution of an already admitted request (no policy or budget checks here). */
    suspend fun perform(
        requestId: String,
        requester: NodeId,
        request: InetRequest,
        requestRoute: List<NodeId>,
        now: () -> Long,
    ): InetResponse {
        fun resp(status: InetStatus, code: Int, msg: String, r: HttpResult? = null) = InetResponse(
            requestId = requestId, status = status, httpCode = code, contentType = r?.contentType ?: "text/plain",
            body = r?.body ?: ByteArray(0), truncated = r?.truncated ?: false, message = msg, gatewayId = selfId,
            requestRoute = requestRoute, executedAt = now(),
        )
        val cfg = config()
        return try {
            when (request.kind) {
                InetKind.ASK, InetKind.READ -> {
                    val svc = answers() ?: return resp(InetStatus.REJECTED, 0, "this gateway has no answer service configured")
                    val a = if (request.kind == InetKind.ASK) svc.ask(request.body, requester) else svc.read(request.url, requester)
                    InetResponse(
                        requestId = requestId, status = InetStatus.OK, httpCode = 200, contentType = InetResponse.ANSWER_CONTENT_TYPE,
                        body = WireCodec.encodeAnswer(a), truncated = false, message = "answered by ${a.provider}",
                        gatewayId = selfId, requestRoute = requestRoute, executedAt = now(),
                    )
                }
                InetKind.HTTP_GET -> executor.get(request.url, cfg.maxResponseBytes).let {
                    resp(InetStatus.OK, it.code, if (it.truncated) "body truncated to ${cfg.maxResponseBytes} bytes" else "ok", it)
                }
                InetKind.WEBHOOK_POST -> executor.postText(cfg.webhookUrl, request.body, cfg.maxResponseBytes).let {
                    resp(InetStatus.OK, it.code, if (it.truncated) "body truncated to ${cfg.maxResponseBytes} bytes" else "ok", it)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            resp(InetStatus.ERROR, 0, "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** Admission + execution in one step, without the scheduler (kept for direct callers). */
    suspend fun execute(
        requestId: String,
        requester: NodeId,
        request: InetRequest,
        requestRoute: List<NodeId>,
        now: () -> Long,
    ): InetResponse {
        val decision = policy.check(requester, request, now())
        if (decision is PolicyDecision.Reject) return response(requestId, requestRoute, InetStatus.REJECTED, decision.reason, now())
        return perform(requestId, requester, request, requestRoute, now)
    }
}

/** Deterministic stand-in for tests and the in-app simulator. */
class FakeInternetExecutor(
    private val responder: (String) -> String = { url -> "<html><title>Fake Internet</title>hello from $url</html>" },
) : InternetExecutor {
    val calls = mutableListOf<String>()

    override suspend fun get(url: String, maxBytes: Int): HttpResult {
        calls += "GET $url"
        val body = responder(url).toByteArray()
        return HttpResult(200, "text/html", body.copyOf(minOf(body.size, maxBytes)), body.size > maxBytes)
    }

    override suspend fun postText(url: String, text: String, maxBytes: Int): HttpResult {
        calls += "POST $url $text"
        return HttpResult(200, "text/plain", "accepted".toByteArray(), false)
    }
}
