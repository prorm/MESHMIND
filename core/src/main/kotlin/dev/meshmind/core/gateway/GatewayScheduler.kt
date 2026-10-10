package dev.meshmind.core.gateway

import dev.meshmind.core.model.InetKind
import dev.meshmind.core.model.InetResponse
import dev.meshmind.core.model.InetStatus
import dev.meshmind.core.model.NodeId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class SchedulerConfig(
    /** Upstream calls in flight at once. */
    val maxConcurrent: Int = 2,
    /** Upstream calls per rolling minute (the gateway owner's data budget). */
    val perMinute: Int = 30,
    /** Requests held waiting; beyond this the lowest-priority one is deferred. */
    val maxQueue: Int = 40,
    /** A request still waiting after this long is answered DEFERRED rather than left hanging. */
    val maxWaitMs: Long = 180_000,
    /** Priority points gained per second of waiting (so nobody starves), capped. */
    val ageingPerSecond: Double = 0.5,
    val ageingCap: Double = 60.0,
    /** Priority points lost per request already served to the same phone in the window, capped (fair share). */
    val fairnessPenalty: Double = 8.0,
    val fairnessCap: Double = 40.0,
    /** Emergency-looking requests per phone per window before they are treated as normal. */
    val urgentAllowance: Int = 2,
    /** SOS webhook posts per phone per window that skip the queue entirely. */
    val sosAllowance: Int = 3,
    val windowMs: Long = 10 * 60_000L,
)

/** One queued request, as shown on the gateway owner's screen. */
data class QueueItemView(
    val requestId: String,
    val requester: NodeId,
    val kind: InetKind,
    val preview: String,
    val need: Int,
    val priority: Int,
    val category: NeedCategory,
    val reasons: List<String>,
    val waitedMs: Long,
    val running: Boolean,
    val bypass: Boolean,
)

data class GatewayQueueSnapshot(
    val items: List<QueueItemView> = emptyList(),
    val capacity: Int = 0,
    val served: Long = 0,
    val deferred: Long = 0,
    val fromCache: Long = 0,
    val usedThisMinute: Int = 0,
    val perMinute: Int = 0,
    /** What people asked about in the last window: the seed of the organizer's crowd sensor. */
    val demand: Map<NeedCategory, Int> = emptyMap(),
    /** Served requests, newest first: who got what, with which score, after how long. */
    val recent: List<QueueItemView> = emptyList(),
)

/**
 * Need-aware fair-share queue in front of a gateway's Internet connection.
 *
 * Effective priority = need × 100 + ageing − fair-share penalty. The need score decides how big a share of the
 * link a request gets, not whether it gets one: waiting raises priority, and phones that were just served drop
 * back. SOS webhook posts (within an allowance) skip the queue. Every decision keeps its reasons.
 */
class GatewayScheduler(
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val scorer: NeedScorer = RuleBasedNeedScorer(),
    private val config: () -> SchedulerConfig = { SchedulerConfig() },
    private val onChange: () -> Unit = {},
) {
    private class Job(
        val requestId: String,
        val requester: NodeId,
        val kind: InetKind,
        val preview: String,
        val score: NeedScore,
        val submittedAt: Long,
        val bypass: Boolean,
        val execute: suspend () -> InetResponse,
        val deliver: suspend (InetResponse) -> Unit,
    ) {
        var startedAt: Long? = null
    }

    private val lock = Any()
    private val queue = ArrayList<Job>()
    private val running = ArrayList<Job>()
    private val budget = ArrayDeque<Long>()
    private val servedAt = HashMap<NodeId, ArrayDeque<Long>>()
    private val urgentAt = HashMap<NodeId, ArrayDeque<Long>>()
    private val sosAt = HashMap<NodeId, ArrayDeque<Long>>()
    private val demand = ArrayDeque<Pair<Long, NeedCategory>>()
    private val recent = ArrayDeque<QueueItemView>()
    private var served = 0L
    private var deferred = 0L
    private var fromCache = 0L
    private val wake = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch { loop() }
    }

    /**
     * Scores [ctx] and queues the request. [execute] performs the upstream call; [deliver] sends the response
     * (always called exactly once, with OK/ERROR from [execute] or DEFERRED from the scheduler).
     */
    fun submit(
        requestId: String,
        ctx: NeedContext,
        sosFlag: Boolean,
        execute: suspend () -> InetResponse,
        deliver: suspend (InetResponse) -> Unit,
    ) {
        val now = clock()
        val cfg = config()
        val evicted: Job?
        val rejectedSelf: Job?
        synchronized(lock) {
            val urgentUsed = window(urgentAt, ctx.requester, now, cfg).size
            val score = scorer.score(ctx.copy(urgentUsed = urgentUsed, urgentAllowance = cfg.urgentAllowance))
            if (score.urgentClaim && urgentUsed < cfg.urgentAllowance) window(urgentAt, ctx.requester, now, cfg).addLast(now)
            val sosWin = window(sosAt, ctx.requester, now, cfg)
            val bypass = sosFlag && ctx.kind == InetKind.WEBHOOK_POST && sosWin.size < cfg.sosAllowance
            if (bypass) sosWin.addLast(now)
            val reasons = if (bypass) listOf("SOS: skips the queue (${sosWin.size}/${cfg.sosAllowance} in 10 min)") + score.reasons else score.reasons
            val job = Job(requestId, ctx.requester, ctx.kind, ctx.text.take(80), score.copy(reasons = reasons), now, bypass, execute, deliver)
            demand.addLast(now to score.category)
            queue += job
            evicted = if (queue.count { !it.bypass } > cfg.maxQueue) queue.filter { !it.bypass }.minBy { priority(it, now, cfg) } else null
            evicted?.let { queue.remove(it); deferred++ }
            rejectedSelf = evicted?.takeIf { it === job }
        }
        evicted?.let { e ->
            scope.launch {
                e.deliver(deferredResponse(e, if (e === rejectedSelf) "gateway busy: ${cfg.maxQueue} more urgent requests are waiting; try again shortly"
                else "gateway busy: replaced by more urgent requests; try again shortly"))
            }
        }
        wake.trySend(Unit)
        onChange()
    }

    /** Counts demand that never needed the queue (answered from the gateway's cache). */
    fun recordCacheHit(ctx: NeedContext) {
        val score = scorer.score(ctx.copy(urgentUsed = 0, urgentAllowance = Int.MAX_VALUE))
        synchronized(lock) { demand.addLast(clock() to score.category); fromCache++ }
        onChange()
    }

    /** Score without queueing: used to label cache hits with the same explanation. */
    fun scoreOnly(ctx: NeedContext): NeedScore = scorer.score(ctx.copy(urgentUsed = 0, urgentAllowance = Int.MAX_VALUE))

    val queueLength: Int get() = synchronized(lock) { queue.size }
    val capacity: Int get() = config().maxQueue

    fun snapshot(): GatewayQueueSnapshot = synchronized(lock) {
        val now = clock()
        val cfg = config()
        prune(now, cfg)
        val view = { j: Job, run: Boolean ->
            QueueItemView(j.requestId, j.requester, j.kind, j.preview, (j.score.value * 100).toInt(), priority(j, now, cfg).toInt(),
                j.score.category, j.score.reasons + dynamicReasons(j, now, cfg), now - j.submittedAt, run, j.bypass)
        }
        GatewayQueueSnapshot(
            items = running.map { view(it, true) } + queue.sortedByDescending { priority(it, now, cfg) }.map { view(it, false) },
            capacity = cfg.maxQueue,
            served = served,
            deferred = deferred,
            fromCache = fromCache,
            usedThisMinute = budget.size,
            perMinute = cfg.perMinute,
            demand = demand.groupingBy { it.second }.eachCount(),
            recent = recent.toList(),
        )
    }

    private suspend fun loop() {
        while (true) {
            val sleep = step()
            if (sleep == null) wake.receive() else withTimeoutOrNull(sleep.coerceAtLeast(1)) { wake.receive() }
        }
    }

    /** Dispatches whatever may run now; returns ms until the next time-based event, or null if none. */
    private fun step(): Long? {
        val now = clock()
        val cfg = config()
        val toStart = ArrayList<Job>()
        val expired = ArrayList<Job>()
        var nextEvent: Long? = null
        synchronized(lock) {
            prune(now, cfg)
            queue.filter { !it.bypass && now - it.submittedAt >= cfg.maxWaitMs }.forEach { queue.remove(it); expired += it; deferred++ }
            // SOS posts never wait for a slot or for budget.
            queue.filter { it.bypass }.forEach { queue.remove(it); start(it, now); toStart += it }
            while (queue.isNotEmpty() && running.count { !it.bypass } < cfg.maxConcurrent) {
                if (budget.size >= cfg.perMinute) {
                    nextEvent = budget.first() + 60_000 - now
                    break
                }
                val job = queue.maxBy { priority(it, now, cfg) }
                queue.remove(job)
                start(job, now)
                budget.addLast(now)
                toStart += job
            }
            queue.minOfOrNull { it.submittedAt + cfg.maxWaitMs - now }?.let { t -> nextEvent = minOf(nextEvent ?: t, t) }
        }
        expired.forEach { e -> scope.launch { e.deliver(deferredResponse(e, "waited ${cfg.maxWaitMs / 1000}s without a free slot; try again")) } }
        toStart.forEach { job -> scope.launch { run(job) } }
        if (toStart.isNotEmpty() || expired.isNotEmpty()) onChange()
        return nextEvent
    }

    private fun start(job: Job, now: Long) {
        job.startedAt = now
        running += job
        servedAt.getOrPut(job.requester) { ArrayDeque() }.addLast(now)
    }

    private suspend fun run(job: Job) {
        val response = try {
            job.execute()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        val now = clock()
        val cfg = config()
        val wait = (job.startedAt ?: now) - job.submittedAt
        synchronized(lock) {
            running.remove(job)
            served++
            recent.addFirst(QueueItemView(job.requestId, job.requester, job.kind, job.preview, (job.score.value * 100).toInt(),
                priority(job, job.startedAt ?: now, cfg).toInt(), job.score.category, job.score.reasons, wait, false, job.bypass))
            while (recent.size > 20) recent.removeLast()
        }
        val base = response ?: errorResponse(job, "gateway failed while executing the request")
        job.deliver(base.copy(queueWaitMs = wait, need = (job.score.value * 100).toInt(), needCategory = job.score.category.label, needReasons = job.score.reasons))
        wake.trySend(Unit)
        onChange()
    }

    private fun priority(j: Job, now: Long, cfg: SchedulerConfig): Double {
        if (j.bypass) return 1_000.0
        val ageing = minOf(cfg.ageingCap, (now - j.submittedAt) / 1000.0 * cfg.ageingPerSecond)
        val servedRecently = servedAt[j.requester]?.count { now - it < cfg.windowMs && it < (j.startedAt ?: Long.MAX_VALUE) } ?: 0
        val fairness = minOf(cfg.fairnessCap, servedRecently * cfg.fairnessPenalty)
        return j.score.value * 100 + ageing - fairness
    }

    private fun dynamicReasons(j: Job, now: Long, cfg: SchedulerConfig): List<String> = buildList {
        val waited = (now - j.submittedAt) / 1000
        if (waited >= 2) add("+ waiting ${waited}s")
        val servedRecently = servedAt[j.requester]?.count { now - it < cfg.windowMs } ?: 0
        if (servedRecently > 0) add("− fair share: $servedRecently served to this phone in 10 min")
    }

    private fun window(map: HashMap<NodeId, ArrayDeque<Long>>, node: NodeId, now: Long, cfg: SchedulerConfig): ArrayDeque<Long> {
        val q = map.getOrPut(node) { ArrayDeque() }
        while (q.isNotEmpty() && now - q.first() >= cfg.windowMs) q.removeFirst()
        return q
    }

    private fun prune(now: Long, cfg: SchedulerConfig) {
        while (budget.isNotEmpty() && now - budget.first() >= 60_000) budget.removeFirst()
        while (demand.isNotEmpty() && now - demand.first().first >= cfg.windowMs) demand.removeFirst()
        for (m in listOf(servedAt, urgentAt, sosAt)) {
            m.values.forEach { q -> while (q.isNotEmpty() && now - q.first() >= cfg.windowMs) q.removeFirst() }
            m.entries.removeIf { it.value.isEmpty() }
        }
    }

    private fun deferredResponse(j: Job, msg: String) = InetResponse(
        requestId = j.requestId, status = InetStatus.DEFERRED, httpCode = 0, contentType = "text/plain", body = ByteArray(0),
        truncated = false, message = msg, gatewayId = "", requestRoute = emptyList(), executedAt = clock(),
        queueWaitMs = clock() - j.submittedAt, need = (j.score.value * 100).toInt(), needCategory = j.score.category.label, needReasons = j.score.reasons,
    )

    private fun errorResponse(j: Job, msg: String) = InetResponse(
        requestId = j.requestId, status = InetStatus.ERROR, httpCode = 0, contentType = "text/plain", body = ByteArray(0),
        truncated = false, message = msg, gatewayId = "", requestRoute = emptyList(), executedAt = clock(),
    )
}
