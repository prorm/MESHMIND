package dev.meshmind.core.sim

import dev.meshmind.core.gateway.AnswerService
import dev.meshmind.core.gateway.FakeInternetExecutor
import dev.meshmind.core.gateway.GatewayConfig
import dev.meshmind.core.gateway.GatewayService
import dev.meshmind.core.gateway.InternetExecutor
import dev.meshmind.core.gateway.LocalAuthority
import dev.meshmind.core.gateway.SchedulerConfig
import dev.meshmind.core.model.NodeId
import dev.meshmind.core.routing.InMemoryPacketStore
import dev.meshmind.core.routing.LinkExtension
import dev.meshmind.core.routing.MeshLogger
import dev.meshmind.core.routing.NextHopAdvisor
import dev.meshmind.core.routing.Router
import dev.meshmind.core.routing.RouterConfig
import dev.meshmind.core.security.Certificate
import dev.meshmind.core.security.NodeIdentity
import dev.meshmind.core.security.Role
import dev.meshmind.core.security.SoftwareSigner
import dev.meshmind.core.security.TrustStore
import dev.meshmind.core.transport.SimulatedNetwork
import kotlinx.coroutines.CoroutineScope

class SimNode(val id: NodeId, val store: InMemoryPacketStore, val identity: NodeIdentity, val trust: TrustStore) {
    lateinit var router: Router
    /** The node's NextHopAdvisor, if any (also its LinkExtension when it implements one, e.g. SAGE shadow). */
    var advisor: NextHopAdvisor? = null
    @Volatile var isGateway: Boolean = false
}

/**
 * Virtual mesh of real [Router]s over a [SimulatedNetwork]. Used by the JVM tests and by the app's
 * Simulation screen, so both exercise exactly the production routing code.
 *
 * With an [authority], every node is enrolled (authority-issued certificate, role from [roleOf]) and trusts that
 * authority, as phones do after enrolling with the backend. Without one, nodes run self-signed identities.
 */
class MeshSimulation(
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    latencyMs: Long = 10,
    bytesPerSecond: Long = 160_000,
    val internet: InternetExecutor = FakeInternetExecutor(),
    private val routerConfig: RouterConfig = RouterConfig(),
    var gatewayConfig: GatewayConfig = GatewayConfig(consent = true, webhookUrl = "https://ntfy.sh/meshmind-sim"),
    private val logEcho: ((NodeId, String) -> Unit)? = null,
    /** Optional per-node NextHopAdvisor (e.g. a SAGE model, or a hostile one in safety tests). */
    private val advisorFor: ((NodeId) -> NextHopAdvisor?)? = null,
    val authority: LocalAuthority? = null,
    private val roleOf: (NodeId) -> Role = { Role.USER },
    var answers: AnswerService? = null,
    var schedulerConfig: SchedulerConfig = SchedulerConfig(),
) {
    val network = SimulatedNetwork(latencyMs, bytesPerSecond)
    val nodes = LinkedHashMap<NodeId, SimNode>()

    private fun newIdentity(id: NodeId): NodeIdentity {
        val key = SoftwareSigner.generate()
        val cert = authority?.let { Certificate.issue(it.signer, id, key.publicKey, roleOf(id), clock()) }
            ?: Certificate.selfSigned(id, key, clock())
        return NodeIdentity(key, cert)
    }

    private fun newTrust() = TrustStore(listOfNotNull(authority?.signer?.publicKey))

    suspend fun addNode(id: NodeId, gateway: Boolean = false, identity: NodeIdentity = newIdentity(id)): SimNode {
        val node = SimNode(id, InMemoryPacketStore(), identity, newTrust())
        node.isGateway = gateway
        build(node, MeshLogger(clock, echo = logEcho?.let { echo -> { e -> echo(id, e.message) } }))
        nodes[id] = node
        return node
    }

    /** Re-creates a node's router on the same persistent store and key (simulates an app restart). */
    suspend fun restart(id: NodeId): SimNode {
        val old = nodes.getValue(id)
        old.router.stop()
        val node = SimNode(id, old.store, old.identity, old.trust).also { it.isGateway = old.isGateway }
        build(node, MeshLogger(clock))
        nodes[id] = node
        return node
    }

    private suspend fun build(node: SimNode, logger: MeshLogger) {
        val gw = GatewayService(node.id, { gatewayConfig }, internet, isActive = { node.isGateway }, answers = { answers })
        node.router = Router(node.id, network.transport(node.id), node.store, scope, clock, gateway = gw, config = routerConfig, logger = logger,
            nextHopAdvisor = advisorFor?.invoke(node.id).also { node.advisor = it }, linkExtension = node.advisor as? LinkExtension,
            identity = node.identity, trust = node.trust, schedulerConfig = { schedulerConfig })
        node.router.start()
    }

    operator fun get(id: NodeId): Router = nodes.getValue(id).router
    fun node(id: NodeId): SimNode = nodes.getValue(id)

    suspend fun link(a: NodeId, b: NodeId) = network.connect(a, b)
    suspend fun cut(a: NodeId, b: NodeId) = network.disconnect(a, b)

    suspend fun chain(vararg ids: NodeId) {
        ids.toList().zipWithNext().forEach { (a, b) -> link(a, b) }
    }

    suspend fun tickAll() = nodes.values.forEach { it.router.tick() }
    suspend fun beaconAll() = nodes.values.forEach { it.router.emitBeacon() }
}
