package dev.meshmind.app.net

import android.content.Context
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionOptions
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import dev.meshmind.core.transport.MeshTransport
import dev.meshmind.core.transport.TransportListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

data class TransportStatus(
    val advertising: Boolean = false,
    val discovering: Boolean = false,
    val discovered: Int = 0,
    val connecting: Int = 0,
    val connected: Int = 0,
    val lastError: String? = null,
)

/**
 * One-hop transport over Google Nearby Connections (P2P_CLUSTER: M-to-N, every phone both advertises and
 * discovers). Nearby gives us reliable point-to-point links only; all multi-hop behaviour lives in the router.
 *
 * Design decisions forced by the API:
 * - Only BYTES payloads (≤ 32 KB). Never FILE/STREAM: Nearby has no payload priority, so the router must
 *   interleave small frames itself.
 * - [send] resolves when Nearby reports the payload SUCCESS/FAILURE, keeping exactly one frame in flight per link.
 * - Connection collisions: only the lexicographically smaller node name initiates immediately; the other side
 *   waits a few seconds before trying, so both phones don't request simultaneously.
 * - Bandwidth upgrade is non-disruptive, so a gateway phone is not knocked off its Wi-Fi network by Nearby
 *   switching it to a hotspot.
 * - Connection count is capped (Bluetooth realistically supports a handful of links per phone).
 */
class NearbyTransport(
    context: Context,
    private val localName: String,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit,
    private val maxPeers: Int = 4,
    /**
     * Testing aid ("topology lock"): on a desk every phone hears every other, so Nearby would form a full mesh.
     * Returning false for a peer name makes this phone refuse that link, which forces a real A↔B↔C line.
     */
    private val peerAllowed: (String) -> Boolean = { true },
) : MeshTransport {

    companion object {
        const val SERVICE_ID = "dev.meshmind.mesh.v1"
        val STRATEGY: Strategy = Strategy.P2P_CLUSTER
    }

    private val client: ConnectionsClient = Nearby.getConnectionsClient(context.applicationContext)
    override val maxFrameBytes: Int = ConnectionsClient.MAX_BYTES_DATA_SIZE

    private var listener: TransportListener? = null
    private val events = Channel<suspend (TransportListener) -> Unit>(Channel.UNLIMITED)
    private val connected = ConcurrentHashMap<String, String>()
    private val pending = ConcurrentHashMap<String, String>()
    private val discovered = ConcurrentHashMap<String, String>()
    private val outgoing = ConcurrentHashMap<Long, CompletableDeferred<Boolean>>()
    private val outgoingEndpoint = ConcurrentHashMap<Long, String>()
    private val jobs = mutableListOf<Job>()
    /** Failed connection attempts per endpoint, for exponential backoff. */
    private val attempts = ConcurrentHashMap<String, Int>()
    private val retryScheduled = ConcurrentHashMap.newKeySet<String>()

    @Volatile private var advertising = false
    @Volatile private var discovering = false
    private val _status = MutableStateFlow(TransportStatus())
    val status: StateFlow<TransportStatus> = _status.asStateFlow()

    override fun setListener(listener: TransportListener) { this.listener = listener }

    /** Names (= node IDs) of MeshMind phones currently in radio range, linked or not. SAGE counts them as contacts. */
    fun discoveredNames(): Set<String> = discovered.values.toSet()

    override suspend fun start() {
        // Single consumer keeps per-endpoint ordering (link up → hello → data → link down).
        jobs += scope.launch { for (ev in events) listener?.let { runCatching { ev(it) }.onFailure { e -> log("listener error: $e") } } }
        startAdvertising()
        startDiscovery()
        jobs += scope.launch { discoveryDutyCycle() }
    }

    override suspend fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        runCatching { client.stopAdvertising() }
        runCatching { client.stopDiscovery() }
        runCatching { client.stopAllEndpoints() }
        advertising = false; discovering = false
        val ids = connected.keys.toList()
        connected.clear(); pending.clear(); discovered.clear()
        outgoing.values.forEach { it.complete(false) }
        outgoing.clear()
        ids.forEach { id -> listener?.onLinkDown(id) }
        publish()
    }

    override suspend fun send(endpointId: String, bytes: ByteArray): Boolean {
        if (!connected.containsKey(endpointId)) return false
        if (bytes.size > maxFrameBytes) { log("frame ${bytes.size}B exceeds Nearby BYTES limit"); return false }
        val payload = Payload.fromBytes(bytes)
        val done = CompletableDeferred<Boolean>()
        outgoing[payload.id] = done
        outgoingEndpoint[payload.id] = endpointId
        try {
            client.sendPayload(endpointId, payload).addOnFailureListener { e ->
                log("sendPayload to $endpointId failed: ${describe(e)}")
                done.complete(false)
            }
            return withTimeoutOrNull(20_000) { done.await() } ?: false.also { log("send to $endpointId timed out") }
        } finally {
            outgoing.remove(payload.id)
            outgoingEndpoint.remove(payload.id)
        }
    }

    /** Re-applies [peerAllowed] to live links (a local disconnect does not trigger onDisconnected, so report it). */
    fun enforcePeerFilter() {
        connected.filterValues { !peerAllowed(it) }.forEach { (id, name) ->
            client.disconnectFromEndpoint(id)
            connected.remove(id)
            log("dropped link to $name (topology lock)")
            events.trySend { it.onLinkDown(id) }
        }
        discovered.forEach { (id, name) -> maybeConnect(id, name) }
        publish()
    }

    // ---- advertising / discovery -------------------------------------------------------------

    private fun startAdvertising() {
        if (advertising) return
        val opts = AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
        client.startAdvertising(localName, SERVICE_ID, lifecycle, opts)
            .addOnSuccessListener { advertising = true; log("advertising as $localName"); publish() }
            .addOnFailureListener { e -> advertising = false; error("startAdvertising: ${describe(e)}") }
    }

    private fun startDiscovery() {
        if (discovering) return
        discovering = true
        val opts = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
        client.startDiscovery(SERVICE_ID, discovery, opts)
            .addOnSuccessListener { log("discovery started"); publish() }
            .addOnFailureListener { e ->
                if ((e as? ApiException)?.statusCode == ConnectionsStatusCodes.STATUS_ALREADY_DISCOVERING) return@addOnFailureListener
                discovering = false
                error("startDiscovery: ${describe(e)}")
            }
    }

    private fun stopDiscovery() {
        if (!discovering) return
        client.stopDiscovery()
        discovering = false
        log("discovery paused (battery)")
        publish()
    }

    /**
     * Battery: scan continuously only while isolated; with peers, scan 20 s per minute; at the connection cap,
     * stop scanning entirely. Advertising stays on so others can still find us.
     */
    private suspend fun discoveryDutyCycle() {
        while (scope.isActive) {
            // Safety net: anything we can see but aren't linked with gets another chance (failed attempts,
            // collisions, a peer that never initiated). requestConnection itself backs off per endpoint.
            discovered.forEach { (id, name) -> if (id !in retryScheduled) maybeConnect(id, name) }
            val n = connected.size
            when {
                n >= maxPeers -> { stopDiscovery(); delay(10_000) }
                n == 0 -> { startDiscovery(); delay(10_000) }
                else -> {
                    startDiscovery(); delay(20_000)
                    if (connected.isNotEmpty() && connected.size < maxPeers) { stopDiscovery(); delay(40_000) }
                }
            }
        }
    }

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (info.serviceId != SERVICE_ID) return
            discovered[endpointId] = info.endpointName
            log("discovered ${info.endpointName} ($endpointId)")
            publish()
            maybeConnect(endpointId, info.endpointName)
        }

        override fun onEndpointLost(endpointId: String) {
            discovered.remove(endpointId)
            log("lost sight of $endpointId")
            publish()
        }
    }

    private fun maybeConnect(endpointId: String, remoteName: String) {
        if (connected.containsKey(endpointId) || pending.containsKey(endpointId)) return
        if (!peerAllowed(remoteName)) { log("not linking to $remoteName (topology lock)"); return }
        if (connected.size + pending.size >= maxPeers) { log("at connection cap ($maxPeers); not connecting to $remoteName"); return }
        if (connected.values.contains(remoteName)) return
        if (localName < remoteName) {
            requestConnection(endpointId, remoteName)
        } else {
            // Let the other side initiate first; fall back if it doesn't (e.g. it can't see us).
            scope.launch {
                delay(6_000)
                if (discovered.containsKey(endpointId) && !connected.containsKey(endpointId) && !pending.containsKey(endpointId) &&
                    !connected.values.contains(remoteName)
                ) requestConnection(endpointId, remoteName)
            }
        }
    }

    private fun requestConnection(endpointId: String, remoteName: String) {
        pending[endpointId] = remoteName
        publish()
        val options = ConnectionOptions.Builder().setDisruptiveUpgrade(false).build()
        try {
            client.requestConnection(localName, endpointId, lifecycle, options)
                .addOnSuccessListener { log("connection requested to $remoteName") }
                .addOnFailureListener { e ->
                    pending.remove(endpointId)
                    val code = (e as? ApiException)?.statusCode
                    if (code != ConnectionsStatusCodes.STATUS_ALREADY_CONNECTED_TO_ENDPOINT) {
                        error("requestConnection $remoteName: ${describe(e)}")
                        scheduleRetry(endpointId, remoteName)
                    }
                    publish()
                }
        } catch (e: SecurityException) {
            pending.remove(endpointId)
            error("missing permission for requestConnection: ${e.message}")
        }
    }

    /**
     * Found on two real phones: a BLE-only connection attempt can fail with STATUS_ENDPOINT_IO_ERROR (8012), and the
     * peer was never retried, so the two phones sat side by side unlinked. Retry with exponential backoff + jitter
     * (jitter also breaks simultaneous-request collisions), from either side, while the endpoint is still visible.
     */
    private fun scheduleRetry(endpointId: String, remoteName: String) {
        if (!retryScheduled.add(endpointId)) return
        val n = attempts.merge(endpointId, 1, Int::plus) ?: 1
        val backoff = minOf(30_000L, 2_000L shl minOf(n - 1, 4)) + (0..2_000).random()
        log("retrying $remoteName in ${backoff / 1000}s (attempt ${n + 1})")
        scope.launch {
            delay(backoff)
            retryScheduled.remove(endpointId)
            if (discovered.containsKey(endpointId) && !connected.containsKey(endpointId) && !pending.containsKey(endpointId) &&
                !connected.values.contains(remoteName) && peerAllowed(remoteName)
            ) requestConnection(endpointId, remoteName)
        }
    }

    // ---- connection lifecycle ---------------------------------------------------------------

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            if (!peerAllowed(info.endpointName)) {
                log("rejecting ${info.endpointName}: topology lock")
                client.rejectConnection(endpointId)
                return
            }
            if (connected.size >= maxPeers && !connected.containsKey(endpointId)) {
                log("rejecting ${info.endpointName}: connection cap")
                client.rejectConnection(endpointId)
                return
            }
            pending[endpointId] = info.endpointName
            // Demo: auto-accept. A production build would verify info.authenticationDigits / a shared key.
            client.acceptConnection(endpointId, payloads)
                .addOnFailureListener { e -> error("acceptConnection: ${describe(e)}") }
            log("connection initiated with ${info.endpointName} (${if (info.isIncomingConnection) "incoming" else "outgoing"})")
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            val name = pending.remove(endpointId) ?: discovered[endpointId] ?: endpointId
            if (result.status.isSuccess) {
                connected[endpointId] = name
                attempts.remove(endpointId)
                log("CONNECTED to $name ($endpointId)")
                events.trySend { it.onLinkUp(endpointId) }
            } else {
                log("connection to $name failed: ${ConnectionsStatusCodes.getStatusCodeString(result.status.statusCode)}")
                if (discovered.containsKey(endpointId)) scheduleRetry(endpointId, name)
            }
            publish()
        }

        override fun onDisconnected(endpointId: String) {
            val name = connected.remove(endpointId)
            outgoingEndpoint.filterValues { it == endpointId }.keys.forEach { outgoing[it]?.complete(false) }
            if (name != null) {
                log("DISCONNECTED from $name ($endpointId)")
                events.trySend { it.onLinkDown(endpointId) }
            }
            publish()
        }
    }

    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type != Payload.Type.BYTES) return // FILE/STREAM never used by design
            val bytes = payload.asBytes() ?: return
            events.trySend { it.onReceived(endpointId, bytes) }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            when (update.status) {
                PayloadTransferUpdate.Status.SUCCESS -> outgoing[update.payloadId]?.complete(true)
                PayloadTransferUpdate.Status.FAILURE, PayloadTransferUpdate.Status.CANCELED -> outgoing[update.payloadId]?.complete(false)
                else -> Unit
            }
        }
    }

    // ---- helpers ------------------------------------------------------------------------------

    private fun error(msg: String) {
        log("ERROR $msg")
        _status.value = _status.value.copy(lastError = msg)
        publish()
    }

    private fun publish() {
        _status.value = TransportStatus(advertising, discovering, discovered.size, pending.size, connected.size, _status.value.lastError)
    }

    private fun describe(e: Exception): String {
        val code = (e as? ApiException)?.statusCode
        return if (code != null) "${ConnectionsStatusCodes.getStatusCodeString(code)} ($code)" else (e.message ?: e.javaClass.simpleName)
    }
}
