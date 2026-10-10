package dev.meshmind.app.sim

import dev.meshmind.core.gateway.FakeAnswerService
import dev.meshmind.core.gateway.FakeInternetExecutor
import dev.meshmind.core.gateway.LocalAuthority
import dev.meshmind.core.gateway.SchedulerConfig
import dev.meshmind.core.security.Role
import dev.meshmind.core.security.SoftwareSigner
import dev.meshmind.core.model.InetKind
import dev.meshmind.core.model.InetRequest
import dev.meshmind.core.model.NodeId
import dev.meshmind.core.model.Priority
import dev.meshmind.core.routing.MeshLogger
import dev.meshmind.core.routing.MeshSnapshot
import dev.meshmind.core.sim.MeshSimulation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

data class SimEdge(val a: NodeId, val b: NodeId, val up: Boolean)

data class SimUiState(
    val nodes: List<NodeId> = emptyList(),
    val edges: List<SimEdge> = emptyList(),
    val gateways: Set<NodeId> = emptySet(),
    val snapshots: Map<NodeId, MeshSnapshot> = emptyMap(),
    val log: List<String> = emptyList(),
    /** Path to highlight in the graph plus a caption. */
    val highlight: List<NodeId> = emptyList(),
    val caption: String = "",
)

/**
 * Runs five real Routers (A–E) over SimulatedTransport inside the app, in real time with radio-like delays
 * (150 ms latency, 48 KB/s per link) so you can watch store-and-forward, gateway routing and SOS preemption.
 */
class SimulationController {
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()
    private lateinit var sim: MeshSimulation
    /** In-process stand-in for the MeshMind backend: issues the nodes' certificates and signs answers. */
    private val authority = LocalAuthority(SoftwareSigner.generate())
    private lateinit var simAnswers: FakeAnswerService
    private var bulletinSeq = 0L
    private val _state = MutableStateFlow(SimUiState())
    val state: StateFlow<SimUiState> = _state.asStateFlow()
    private val logLines = ArrayDeque<String>()
    private var pump: Job? = null

    val ids = listOf("A", "B", "C", "D", "E")
    val possibleEdges = listOf("A" to "B", "B" to "C", "C" to "D", "D" to "E", "A" to "C", "B" to "D")

    init { reset() }

    fun reset() {
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        synchronized(logLines) { logLines.clear() }
        scope.launch {
            lock.withLock {
                simAnswers = FakeAnswerService(authority, System::currentTimeMillis, latencyMs = 1_500) { q -> simulatedAnswer(q) }
                sim = MeshSimulation(
                    scope, System::currentTimeMillis, latencyMs = 150, bytesPerSecond = 48_000,
                    internet = FakeInternetExecutor { url -> "<html><title>Simulated Internet</title><p>Fetched $url via the mesh gateway.</p></html>" },
                    logEcho = { id, msg -> addLog("${MeshLogger.formatTime(System.currentTimeMillis())} $id: $msg") },
                    authority = authority,
                    roleOf = { if (it == "D") Role.ORGANIZER else Role.USER },
                    answers = simAnswers,
                    // One upstream call at a time so the fair-share queue is visible in the demo.
                    schedulerConfig = SchedulerConfig(maxConcurrent = 1),
                )
                ids.forEach { sim.addNode(it) }
                sim.chain(*ids.toTypedArray())
            }
            addLog("simulation ready: A-B-C-D-E line; toggle edges and gateways above")
        }
        pump?.cancel()
        pump = scope.launch {
            var n = 0
            while (isActive) {
                delay(500)
                lock.withLock {
                    if (::sim.isInitialized) {
                        if (n % 4 == 0) sim.tickAll()
                        if (n % 10 == 0) sim.beaconAll()
                        publish()
                    }
                }
                n++
            }
        }
    }

    private fun addLog(line: String) = synchronized(logLines) {
        logLines.addFirst(line)
        while (logLines.size > 400) logLines.removeLast()
    }

    /** Must be called with [lock] held (node map is mutated under it). */
    private fun publish(highlight: List<NodeId>? = null, caption: String? = null) {
        if (!::sim.isInitialized) return
        val up = sim.network.edges().map { if (it.first < it.second) it else it.second to it.first }.toSet()
        val snaps = sim.nodes.mapValues { it.value.router.state.value }
        val prev = _state.value
        val (hl, cap) = if (highlight != null) highlight to (caption ?: "") else autoHighlight(snaps) ?: (prev.highlight to prev.caption)
        _state.value = SimUiState(
            nodes = ids,
            edges = possibleEdges.map { (a, b) -> SimEdge(a, b, (a to b) in up) },
            gateways = sim.nodes.values.filter { it.isGateway }.map { it.id }.toSet(),
            snapshots = snaps,
            log = synchronized(logLines) { logLines.toList() },
            highlight = hl,
            caption = cap,
        )
    }

    /** Highlights the most recent completed delivery or Internet round trip seen at node A. */
    private fun autoHighlight(snaps: Map<NodeId, MeshSnapshot>): Pair<List<NodeId>, String>? {
        val a = snaps["A"] ?: return null
        val inet = a.inet.firstOrNull { it.response != null }
        val tracked = a.tracked.firstOrNull { it.deliveredRoute.isNotEmpty() }
        val inetAt = inet?.completedAt ?: 0
        val trAt = tracked?.let { it.createdAt + (it.rttMs ?: 0) } ?: 0
        return when {
            inet != null && inetAt >= trAt -> {
                val r = inet.response!!
                (r.requestRoute + inet.responseRoute.drop(1)) to
                    "🌐 ${r.status}: out ${r.requestRoute.joinToString(" → ")} → Internet → back ${inet.responseRoute.joinToString(" → ")} (${inetAt - inet.sentAt} ms)"
            }
            tracked != null ->
                tracked.deliveredRoute to "${tracked.type} ${tracked.priority}: ${tracked.deliveredRoute.zipWithNext().joinToString("  ") { "${it.first}→${it.second} ✓" }}  DELIVERED (${tracked.hopCount} hops, rtt ${tracked.rttMs} ms)"
            else -> null
        }
    }

    private fun act(block: suspend MeshSimulation.() -> Unit) {
        scope.launch { lock.withLock { sim.block(); publish() } }
    }

    fun toggleEdge(a: NodeId, b: NodeId) = act {
        if (network.isConnected(a, b)) { cut(a, b); addLog("✕ cut $a–$b") } else { link(a, b); addLog("● link $a–$b") }
    }

    fun toggleGateway(id: NodeId) = act {
        val n = node(id)
        n.isGateway = !n.isGateway
        addLog(if (n.isGateway) "🌐 $id is now an Internet gateway (fake Internet)" else "$id is no longer a gateway")
        if (n.isGateway) n.router.emitBeacon()
    }

    fun sendText(from: NodeId, to: NodeId, text: String) = act { get(from).sendText(to, text) }

    fun sendSos(from: NodeId, to: NodeId) = act { get(from).sendSos("MEDICAL: person collapsed near stage", to) }

    fun requestUrl(from: NodeId) = act { get(from).sendInternetRequest(InetRequest(InetKind.HTTP_GET, "https://example.com/", "")) }

    fun startTransfer(from: NodeId, to: NodeId) = act {
        get(from).sendTransfer(to, "photo.jpg", Random.nextBytes(256 * 1024), Priority.LOW)
        addLog("📦 256 KB LOW transfer $from → $to started (16 chunks)")
    }

    /** Makes sure some node is a gateway (E by default) so Ask scenarios have somewhere to go. */
    private suspend fun MeshSimulation.ensureGateway() {
        if (nodes.values.none { it.isGateway }) {
            node("E").isGateway = true
            addLog("🌐 E is now an Internet gateway (simulated backend)")
        }
        nodes.values.filter { it.isGateway }.forEach { it.router.emitBeacon() }
    }

    fun ask(from: NodeId, question: String) = act {
        ensureGateway()
        get(from).sendAsk(question, 60)
        addLog("🔎 $from asks: $question")
    }

    /**
     * Crowd rush: everyone asks at once through one gateway. B spams fake "urgent" questions; the fair-share
     * scheduler serves the medical and safety questions first and B's allowance runs out.
     */
    fun crowdRush() = act {
        ensureGateway()
        val wave = listOf(
            "B" to "URGENT help!!! cricket score", "A" to "cricket score now", "B" to "URGENT help emergency ipl live",
            "C" to "is the metro running", "B" to "URGENT help!!! movie times", "A" to "my friend fainted, nearest first aid",
            "C" to "which exit gates are open", "B" to "URGENT help emergency meme", "D" to "weather tonight",
        )
        wave.forEach { (n, q) -> get(n).sendAsk(q, if (n == "A") 9 else 60) }
        addLog("🌊 crowd rush: ${wave.size} questions from A–D at once (B is crying wolf)")
    }

    fun broadcastLiveInfo() = act {
        ensureGateway()
        val gw = nodes.values.first { it.isGateway }
        gw.router.broadcastBulletin(simAnswers.demoBulletin(++bulletinSeq))
        addLog("📢 ${gw.id} broadcast the official live-info board")
    }

    fun organizerNotice() = act {
        get("D").postNotice("Gate B closed", "Crowding at Gate B. Exit via Gates A or C.", 2)
        addLog("📢 D (verified organizer) posted a notice")
    }

    private fun simulatedAnswer(q: String): String {
        val s = q.lowercase()
        return when {
            "metro" in s || "train" in s -> "Metro Line 2 is running every 8 minutes until 23:30. Nearest entrance: 400 m from Gate C."
            "first aid" in s || "hospital" in s || "fainted" in s -> "Medical tent is behind the food court near Gate A (staffed now). Lay them down, raise legs, keep them cool."
            "gate" in s || "exit" in s -> "Gates A and C are open. Gate B is closed due to crowding."
            "weather" in s -> "27°C, light rain from 16:00, clearing by 19:00."
            "cricket" in s || "ipl" in s -> "Simulated score: 182/4 after 18 overs."
            else -> "Simulated answer to \"$q\"."
        }
    }

    fun close() = scope.cancel()
}
