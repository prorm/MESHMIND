package dev.meshmind.core.routing

import dev.meshmind.core.ai.Classification
import dev.meshmind.core.ai.ClassificationInput
import dev.meshmind.core.ai.PriorityClassifier
import dev.meshmind.core.ai.RuleBasedPriorityClassifier
import dev.meshmind.core.gateway.AnswerCache
import dev.meshmind.core.gateway.GatewayQueueSnapshot
import dev.meshmind.core.gateway.GatewayScheduler
import dev.meshmind.core.gateway.GatewayService
import dev.meshmind.core.gateway.NeedContext
import dev.meshmind.core.gateway.NeedScorer
import dev.meshmind.core.gateway.PolicyDecision
import dev.meshmind.core.gateway.RuleBasedNeedScorer
import dev.meshmind.core.gateway.SchedulerConfig
import dev.meshmind.core.model.Answer
import dev.meshmind.core.model.AnswerKind
import dev.meshmind.core.model.Bulletin
import dev.meshmind.core.model.BulletinItem
import dev.meshmind.core.model.BulletinKind
import dev.meshmind.core.model.InetKind
import dev.meshmind.core.model.InetStatus
import dev.meshmind.core.model.AckPayload
import dev.meshmind.core.model.Addresses
import dev.meshmind.core.model.BeaconPayload
import dev.meshmind.core.model.ChunkPayload
import dev.meshmind.core.model.Frame
import dev.meshmind.core.model.InetRequest
import dev.meshmind.core.model.InetResponse
import dev.meshmind.core.model.NodeId
import dev.meshmind.core.model.Packet
import dev.meshmind.core.model.PacketState
import dev.meshmind.core.model.PacketType
import dev.meshmind.core.model.Priority
import dev.meshmind.core.model.StoredPacket
import dev.meshmind.core.model.SummaryPayload
import dev.meshmind.core.model.WireCodec
import dev.meshmind.core.model.WireFormatException
import dev.meshmind.core.security.Certificate
import dev.meshmind.core.security.NodeIdentity
import dev.meshmind.core.security.Role
import dev.meshmind.core.security.TrustLevel
import dev.meshmind.core.security.TrustResult
import dev.meshmind.core.security.TrustStore
import dev.meshmind.core.security.signingBytes
import dev.meshmind.core.transport.MeshTransport
import dev.meshmind.core.transport.TransportListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Hook for future smarter routing (mobility-aware forwarding, ML next-hop prediction). Given the candidate
 * links the epidemic router would use, it may narrow them. V1 ships no implementation: pure flooding with
 * the two deterministic shortcuts below (direct destination, known gateway next hop).
 */
fun interface NextHopAdvisor {
    fun choose(packet: Packet, candidates: List<NodeId>): List<NodeId>
}

/**
 * Optional link-local side channel (e.g. SAGE's contact summary). Frames ride below every CRITICAL packet, are never
 * forwarded, and an exception here never affects routing. Both calls run under the router lock: keep them fast.
 */
interface LinkExtension {
    /** Payload to send once a link's peer is known (and again every [RouterConfig.linkExtensionRefreshMs]); null = none. */
    fun onPeerIdentified(peer: NodeId): ByteArray?
    fun onFrame(peer: NodeId, payload: ByteArray)
}

/** Per-link queue key of the HELLO control frame. */
private const val HELLO_KEY = "hello"

/**
 * Application-layer mesh router: epidemic flooding + store-and-forward + ACK-based replication stop,
 * duplicate suppression, TTL/expiry, per-link priority scheduling, and Internet-gateway routing.
 *
 * All mutable state is guarded by one [Mutex]; transport sends happen outside the lock, one frame at a time
 * per link, so the per-link [PacketPriorityQueue] decides what goes next.
 */
class Router(
    val selfId: NodeId,
    private val transport: MeshTransport,
    private val store: PacketStore,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val classifier: PriorityClassifier = RuleBasedPriorityClassifier(),
    private val gateway: GatewayService? = null,
    val config: RouterConfig = RouterConfig(),
    val logger: MeshLogger = MeshLogger(clock),
    private val nextHopAdvisor: NextHopAdvisor? = null,
    private val monotonicClock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val linkExtension: LinkExtension? = null,
    /** Signs everything this node originates. Defaults to a fresh self-signed identity. */
    val identity: NodeIdentity = NodeIdentity.ephemeral(selfId, clock()),
    /** Verifies what others send: authority anchors plus node-key pins. */
    val trust: TrustStore = TrustStore(),
    private val needScorer: NeedScorer = RuleBasedNeedScorer(),
    private val schedulerConfig: () -> SchedulerConfig = { SchedulerConfig() },
) : TransportListener {

    data class LightControl(val packet: Packet, val receivedAt: Long)
    private val lightControls = Channel<LightControl>(64)
    /** Single consumer: the light-show controller. Delivery never blocks the router or SOS. */
    val lightEvents = lightControls.receiveAsFlow()

    suspend fun sendLightControl(destination: NodeId, payload: ByteArray, stopping: Boolean = false) = atomically {
        require(payload.size <= 512)
        originateLocked(PacketType.LIGHT_SHOW, destination, payload,
            Classification(if (stopping) Priority.HIGH else Priority.NORMAL, "LIGHT SHOW", emptyList()),
            5_000, persist = false, quiet = true)
        publish()
    }

    private inner class Link(val endpointId: String, val connectedAt: Long) {
        var nodeId: NodeId? = null
        val queue = PacketPriorityQueue()
        val wake = Channel<Unit>(Channel.CONFLATED)
        var job: Job? = null
        var bytesSent = 0L
        var framesSent = 0
        var consecutiveFailures = 0
        /** When the peer's SUMMARY arrived: it answers our HELLO, so the handshake is complete in both directions. */
        var peerSummaryAt: Long? = null
        /** CHUNK packetId -> time handed to the transport, awaiting the neighbour's LINK_ACK. */
        val bulkInFlight = LinkedHashMap<String, Long>()
        var windowTimerArmed = false
        var extensionSentAt = Long.MIN_VALUE / 2
        var trust: TrustResult = TrustResult.UNSIGNED
    }

    private class Outgoing(val item: PacketPriorityQueue.Item, val packet: Packet?, val bytes: ByteArray)

    private class Assembly(
        val from: NodeId, val name: String, val total: Int, val totalBytes: Int, val startedAt: Long,
    ) {
        val parts = arrayOfNulls<ByteArray>(total)
        var received = 0
        var completedAt: Long? = null
    }

    private val mutex = Mutex()
    private val links = LinkedHashMap<String, Link>()
    private val packets = LinkedHashMap<String, StoredPacket>()
    private val ephemeral = HashSet<String>()
    private val acked = LinkedHashSet<String>()
    private val dup = DuplicateTracker()
    private val peerHas = HashMap<NodeId, MutableSet<String>>()
    val gateways = GatewayTable(config.gatewayEntryLifetimeMs)
    private val executed = HashSet<String>()
    private val tracked = LinkedHashMap<String, TrackedPacket>()
    private val inbox = ArrayList<InboxMessage>()
    private val inetResults = LinkedHashMap<String, InetResult>()
    private val transfersOut = LinkedHashMap<String, TransferProgress>()
    private val chunkOwner = HashMap<String, String>()
    private val transfersIn = LinkedHashMap<String, Assembly>()
    private val receivedFiles = LinkedHashMap<String, ByteArray>()
    private val knownNodes = LinkedHashMap<NodeId, Long>()
    private val edges = LinkedHashMap<Pair<NodeId, NodeId>, Long>()
    private val metrics = ArrayList<DeliveryMetric>()
    private var stats = MeshStats()
    private var beaconSeq = 0L
    private var lastBeaconAt = Long.MIN_VALUE / 2
    private var advisorDisabled = false
    private var advisorSlowCalls = 0
    private var advisorFailures = 0
    /** Verified trust of packets we hold or delivered, for the UI (bounded). */
    private val packetTrust = object : LinkedHashMap<String, TrustResult>(256, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TrustResult>?) = size > 5_000
    }
    /** When we last saw an SOS from each node: a need signal the gateway observes itself. */
    private val lastSosFrom = HashMap<NodeId, Long>()
    val answerCache = AnswerCache()
    private var relayCacheAnswers = 0L
    private val bulletins = LinkedHashMap<String, BulletinView>()
    /** Need-aware fair-share queue; only used while this node acts as a gateway. */
    private val scheduler: GatewayScheduler? = gateway?.let {
        GatewayScheduler(scope, clock, needScorer, schedulerConfig) { scope.launch { mutex.withLock { publish() } } }
    }

    private companion object {
        /** Packet types a [NextHopAdvisor] may narrow. Everything else is routed deterministically. */
        val ADVISABLE = setOf(PacketType.TEXT, PacketType.CHUNK, PacketType.INET_REQUEST, PacketType.INET_RESPONSE)
        const val EXTENSION_RANK = 11
    }

    private val _state = MutableStateFlow(MeshSnapshot(selfId))
    val state: StateFlow<MeshSnapshot> = _state.asStateFlow()

    init {
        transport.setListener(this)
        require(identity.nodeId == selfId) { "identity certificate names ${identity.nodeId}, router is $selfId" }
    }

    /**
     * Runs a state change to completion even if the caller is cancelled (e.g. the user leaves the screen that
     * started a transfer). Without this, cancellation during a suspending store write left half-created transfers.
     */
    private suspend fun <T> atomically(block: suspend () -> T): T = withContext(NonCancellable) { mutex.withLock { block() } }

    private fun gatewayActive(): Boolean = gateway?.isActive?.invoke() == true

    // =====================================================================================
    // Lifecycle
    // =====================================================================================

    /** Loads the persisted store-and-forward buffer. Call once before the transport starts. */
    suspend fun start() = mutex.withLock {
        val now = clock()
        val load = store.loadStartup()
        val stored = load.packets
        store.loadAckedIds().forEach { acked.add(it) }
        load.finishedIds.forEach { dup.firstTime(it, now) }
        for (sp in stored) {
            // Finished packets are only needed for the UI (tracked/inbox) and duplicate detection, not in memory.
            if (sp.state == PacketState.QUEUED || sp.state == PacketState.FORWARDING) packets[sp.id] = sp
            dup.firstTime(sp.id, now)
            val p = sp.packet
            if (sp.origin && p.type in setOf(PacketType.TEXT, PacketType.SOS, PacketType.INET_REQUEST)) {
                tracked[sp.id] = TrackedPacket(
                    p.packetId, p.type, p.priority, p.label, listOf("restored"), p.destinationId, p.createdAt,
                    preview(p), status = when (sp.state) {
                        PacketState.DELIVERED -> TrackStatus.DELIVERED
                        PacketState.EXPIRED -> TrackStatus.EXPIRED
                        PacketState.FORWARDING -> TrackStatus.IN_TRANSIT
                        PacketState.QUEUED -> TrackStatus.QUEUED
                    },
                )
            }
            if (!sp.origin && (p.destinationId == selfId || p.isBroadcast) && p.type in setOf(PacketType.TEXT, PacketType.SOS)) {
                packetTrust[p.packetId] = trust.verifyPacket(p, now)
                inbox += inboxOf(p, sp.updatedAt)
            }
        }
        val pending = packets.size
        logger.log(LogCategory.INFO, "router $selfId started: restored ${stored.size + load.finishedIds.size} packets ($pending pending), ${acked.size} acked ids")
        publish()
    }

    /** Periodic housekeeping: expiry, gateway table pruning, and re-offering pending packets to current peers. */
    suspend fun tick() = mutex.withLock {
        val now = clock()
        for (sp in packets.values.toList()) {
            val p = sp.packet
            if ((sp.state == PacketState.QUEUED || sp.state == PacketState.FORWARDING) && p.isExpired(now)) {
                if (sp.id in ephemeral) {
                    packets.remove(sp.id); ephemeral.remove(sp.id)
                } else {
                    packets[sp.id] = sp.copy(state = PacketState.EXPIRED, updatedAt = now)
                    store.updateState(sp.id, PacketState.EXPIRED, now)
                    stats = stats.copy(expired = stats.expired + 1)
                    tracked[sp.id]?.let { tracked[sp.id] = it.copy(status = TrackStatus.EXPIRED) }
                    logger.log(LogCategory.EXPIRY, "expired ${p.type} ${short(p.packetId)} ${p.sourceId}->${p.destinationId} after ${(now - p.createdAt) / 1000}s", p.packetId)
                }
                links.values.forEach { it.queue.remove(sp.id) }
            }
        }
        gateways.prune(now).forEach { logger.log(LogCategory.BEACON, "gateway $it timed out (no beacon)") }
        edges.entries.removeIf { now - it.value > 10 * 60_000L }
        links.values.forEach { if (it.nodeId != null && now - it.extensionSentAt >= config.linkExtensionRefreshMs) sendExtensionLocked(it, now) }
        packets.values.toList().forEach { scheduleReplicationLocked(it, exclude = null) }
        publish()
    }

    suspend fun stop() = mutex.withLock {
        links.values.forEach { it.job?.cancel(); it.wake.close() }
        links.clear()
        publish()
    }

    // =====================================================================================
    // Public sending API
    // =====================================================================================

    suspend fun sendText(destination: NodeId, text: String): Packet = atomically {
        val bytes = text.toByteArray()
        val cls = classifier.classify(ClassificationInput(PacketType.TEXT, text, bytes.size))
        val p = originateLocked(PacketType.TEXT, destination, bytes, cls, config.messageLifetimeMs)
        trackLocked(p, cls, text)
        publish()
        p
    }

    /** Explicit SOS: ALWAYS CRITICAL, regardless of what the classifier would say. */
    suspend fun sendSos(description: String, destination: NodeId = Addresses.BROADCAST): Packet = atomically {
        val text = description.ifBlank { "SOS" }
        val cls = classifier.classify(ClassificationInput(PacketType.SOS, text, text.length, sosAction = true))
            .copy(priority = Priority.CRITICAL)
        val p = originateLocked(PacketType.SOS, destination, text.toByteArray(), cls, config.sosLifetimeMs)
        trackLocked(p, cls, text)
        publish()
        p
    }

    /** INET_REQUEST addressed to "any gateway". [sos] forces CRITICAL (e.g. SOS to a webhook). */
    suspend fun sendInternetRequest(request: InetRequest, sos: Boolean = false): Packet = atomically {
        val bytes = WireCodec.encodeInetRequest(request)
        var cls = classifier.classify(ClassificationInput(PacketType.INET_REQUEST, request.body, bytes.size, sosAction = sos))
        if (sos) cls = cls.copy(priority = Priority.CRITICAL, label = "SOS")
        val now = clock()
        val p: Packet
        if (gatewayActive()) {
            p = newPacket(PacketType.INET_REQUEST, Addresses.ANY_GATEWAY, bytes, cls, config.inetLifetimeMs, now)
            dup.firstTime(p.packetId, now)
            inetResults[p.packetId] = InetResult(p.packetId, request, now, p.priority)
            trackLocked(p, cls, "${request.kind} ${request.url}${request.body}")
            logger.log(LogCategory.GATEWAY, "I am a gateway: executing my own request locally", p.packetId)
            executeRequestLocked(p)
        } else {
            p = originateLocked(PacketType.INET_REQUEST, Addresses.ANY_GATEWAY, bytes, cls, config.inetLifetimeMs)
            inetResults[p.packetId] = InetResult(p.packetId, request, now, p.priority)
            trackLocked(p, cls, "${request.kind} ${request.url}${request.body}")
            val g = gateways.best(now)
            logger.log(
                LogCategory.GATEWAY,
                if (g != null) "INET_REQUEST queued toward gateway ${g.gatewayId} (${g.hopDistance} hops via ${g.viaPeer})"
                else "INET_REQUEST queued: no gateway known yet, flooding / waiting for a beacon",
                p.packetId,
            )
        }
        publish()
        p
    }

    /**
     * Asks a question through the mesh. Answers already held by this phone (verified, still fresh) come back
     * instantly without any radio traffic. Returns the request id to follow in [MeshSnapshot.inet].
     */
    suspend fun sendAsk(query: String, battery: Int = -1): String = askOrRead(InetRequest(InetKind.ASK, "", query.trim(), battery))

    /** Fetches a page as plain readable text (extracted and signed by the backend). */
    suspend fun sendRead(url: String, battery: Int = -1): String = askOrRead(InetRequest(InetKind.READ, url.trim(), "", battery))

    private suspend fun askOrRead(req: InetRequest): String {
        val kind = if (req.kind == InetKind.ASK) AnswerKind.ASK else AnswerKind.READ
        val cached = mutex.withLock {
            val now = clock()
            answerCache.get(kind, if (kind == AnswerKind.ASK) req.body else req.url, now)?.let { a ->
                val id = Packet.newId()
                val resp = InetResponse(id, InetStatus.OK, 200, InetResponse.ANSWER_CONTENT_TYPE, WireCodec.encodeAnswer(a), false,
                    "already on this phone", selfId, listOf(selfId), now, servedFrom = InetResponse.SERVED_LOCAL_CACHE)
                inetResults[id] = InetResult(id, req, now, Priority.NORMAL, reachedGatewayAt = now, response = resp,
                    responseRoute = listOf(selfId), completedAt = now, answer = a, answerVerified = true, servedFrom = resp.servedFrom)
                logger.log(LogCategory.GATEWAY, "${req.kind} answered from this phone's cache (no radio traffic)", id)
                publish()
                id
            }
        }
        return cached ?: sendInternetRequest(req).packetId
    }

    /** Broadcasts a bulletin (official board fetched by a gateway, or an organizer's notice) to every phone. */
    suspend fun broadcastBulletin(b: Bulletin): Boolean = atomically {
        val now = clock()
        val cur = bulletins[b.boardId]
        if (cur != null && cur.bulletin.seq >= b.seq) return@atomically false
        val official = b.nodeSigner == null
        bulletins[b.boardId] = BulletinView(b, official, if (official) Role.USER else identity.cert.role, selfId, now, 0)
        val expires = (b.items.maxOfOrNull { it.expiresAt } ?: (now + config.bulletinLifetimeMs)).coerceAtMost(now + config.bulletinLifetimeMs)
        originateLocked(PacketType.BULLETIN, Addresses.BROADCAST, WireCodec.encodeBulletin(b),
            Classification(Priority.NORMAL, "BULLETIN", listOf("board ${b.boardId}")), (expires - now).coerceAtLeast(60_000))
        publish()
        true
    }

    /**
     * Posts a notice to this node's own board. Receivers only show it if this node's certificate is
     * authority-issued with the ORGANIZER or RESPONDER role, so ordinary phones cannot broadcast alerts.
     */
    suspend fun postNotice(title: String, body: String, severity: Int, kind: BulletinKind = BulletinKind.NOTICE): Boolean {
        val now = clock()
        val prev = mutex.withLock { bulletins[selfId]?.bulletin }
        val items = (listOf(BulletinItem(kind, severity.coerceIn(0, 3), title.take(120), body.take(500), selfId, now, now + config.bulletinLifetimeMs)) +
            (prev?.items ?: emptyList()).filter { it.expiresAt > now }).take(5)
        val keyId = Bulletin.NODE_KEY_PREFIX + selfId
        val seq = maxOf(now, (prev?.seq ?: 0) + 1)
        val b = Bulletin(selfId, "Notices from $selfId", seq, now, items, keyId,
            identity.sign(Bulletin.canonical(selfId, "Notices from $selfId", seq, now, items, keyId)))
        return broadcastBulletin(b)
    }

    /**
     * Sends [data] as application-layer CHUNK packets (each its own Nearby BYTES payload) so higher-priority
     * traffic can interleave. Returns the transfer ID.
     */
    suspend fun sendTransfer(destination: NodeId, name: String, data: ByteArray, priority: Priority = Priority.LOW): String =
        atomically {
            val transferId = Packet.newId()
            val now = clock()
            val total = maxOf(1, (data.size + config.chunkSize - 1) / config.chunkSize)
            transfersOut[transferId] = TransferProgress(transferId, name, destination, true, total, 0, data.size, now)
            for (i in 0 until total) {
                val part = data.copyOfRange(i * config.chunkSize, minOf(data.size, (i + 1) * config.chunkSize))
                val payload = WireCodec.encodeChunk(ChunkPayload(transferId, i, total, data.size, name, part))
                val base = classifier.classify(ClassificationInput(PacketType.CHUNK, "", payload.size))
                val cls = base.copy(priority = priority, label = "BULK ${i + 1}/$total")
                val p = originateLocked(PacketType.CHUNK, destination, payload, cls, config.chunkLifetimeMs, quiet = i > 0)
                chunkOwner[p.packetId] = transferId
            }
            logger.log(LogCategory.QUEUE, "transfer '$name' ${data.size}B -> $destination split into $total chunks of ≤${config.chunkSize}B at $priority")
            publish()
            transferId
        }

    /** Floods a GATEWAY_BEACON if this node is an active gateway. */
    suspend fun emitBeacon(): Boolean = atomically { emitBeaconLocked(force = true).also { publish() } }

    fun receivedFile(transferId: String): ByteArray? = synchronized(receivedFiles) { receivedFiles[transferId] }

    suspend fun clearHistory() = mutex.withLock {
        tracked.clear(); inbox.clear(); inetResults.clear(); transfersOut.clear(); transfersIn.clear(); bulletins.clear()
        metrics.clear(); stats = MeshStats(); logger.clear()
        publish()
    }

    // =====================================================================================
    // TransportListener
    // =====================================================================================

    override suspend fun onLinkUp(endpointId: String) = mutex.withLock {
        if (links.containsKey(endpointId)) return@withLock
        val link = Link(endpointId, clock())
        links[endpointId] = link
        offerHelloLocked(link)
        link.job = scope.launch { senderLoop(link) }
        logger.log(LogCategory.PEER, "link up: endpoint $endpointId (sending HELLO)")
        scope.launch { handshakeWatchdog(link) }
        publish()
    }

    private fun offerHelloLocked(link: Link) {
        val unsigned = Frame.Hello(selfId, WireCodec.PROTOCOL_VERSION, gatewayActive(), clock())
        val hello = Frame.Hello(unsigned.nodeId, unsigned.protocolVersion, unsigned.isGateway, unsigned.timestamp,
            identity.certBytes, identity.sign(unsigned.signedBytes()))
        link.queue.offer(HELLO_KEY, -2, WireCodec.encode(hello))
        link.wake.trySend(Unit)
    }

    /** A HELLO must carry a certificate naming its node ID and a fresh signature by that key. */
    private fun verifyHelloLocked(hello: Frame.Hello, now: Long): TrustResult {
        val certBytes = hello.cert ?: return TrustResult.UNSIGNED
        val sig = hello.signature ?: return TrustResult.UNSIGNED
        val cert = runCatching { Certificate.decode(certBytes) }.getOrElse { return TrustResult.invalid("undecodable certificate") }
        if (cert.nodeId != hello.nodeId) return TrustResult.invalid("certificate names ${cert.nodeId}, HELLO claims ${hello.nodeId}")
        if (kotlin.math.abs(now - hello.timestamp) > config.helloMaxSkewMs) return TrustResult.invalid("stale HELLO (clock off by ${(now - hello.timestamp) / 1000}s)")
        val r = trust.evaluate(cert, now)
        if (!r.accepted) return r
        return if (trust.verifyNode(hello.signedBytes(), sig, cert.publicKey)) r else TrustResult.invalid("HELLO signature invalid")
    }

    /** Re-sends HELLO until the peer answers with a SUMMARY, so a lost HELLO cannot leave a half-open link. */
    private suspend fun handshakeWatchdog(link: Link) {
        repeat(config.handshakeRetries) {
            delay(config.handshakeRetryMs)
            mutex.withLock {
                if (links[link.endpointId] !== link || link.peerSummaryAt != null) return
                logger.log(LogCategory.PEER, "handshake with ${link.nodeId ?: link.endpointId} unconfirmed (no SUMMARY); re-sending HELLO")
                offerHelloLocked(link)
            }
        }
    }

    override suspend fun onLinkDown(endpointId: String) = mutex.withLock {
        val link = links.remove(endpointId) ?: return@withLock
        link.job?.cancel()
        link.wake.close()
        val node = link.nodeId
        logger.log(LogCategory.PEER, "link down: ${node ?: "?"} (endpoint $endpointId), ${link.queue.size} frames were queued")
        if (node != null) {
            gateways.removeVia(node).forEach { logger.log(LogCategory.BEACON, "gateway $it unreachable (route via $node lost)") }
        }
        publish()
    }

    override suspend fun onReceived(endpointId: String, bytes: ByteArray) {
        val frame = try {
            WireCodec.decode(bytes)
        } catch (e: WireFormatException) {
            logger.log(LogCategory.ERROR, "undecodable frame from $endpointId: ${e.message}")
            return
        }
        mutex.withLock {
            val link = links[endpointId]
            if (link == null) {
                logger.log(LogCategory.ERROR, "frame from unknown endpoint $endpointId ignored")
                return@withLock
            }
            stats = stats.copy(framesReceived = stats.framesReceived + 1, bytesReceived = stats.bytesReceived + bytes.size)
            when (frame) {
                is Frame.Hello -> onHelloLocked(link, frame)
                is Frame.LinkAck -> {
                    frame.packetIds.forEach { link.bulkInFlight.remove(it) }
                    link.wake.trySend(Unit)
                }
                is Frame.Extension -> {
                    val node = link.nodeId
                    val ext = linkExtension
                    if (node != null && ext != null) {
                        runCatching { ext.onFrame(node, frame.payload) }
                            .onFailure { logger.log(LogCategory.ERROR, "link extension frame from $node rejected: ${it.message}") }
                    }
                }
                is Frame.Data -> {
                    if (link.nodeId == null) {
                        logger.log(LogCategory.ERROR, "data before HELLO from $endpointId ignored")
                    } else {
                        handlePacketLocked(link, frame.packet)
                    }
                }
            }
            publish()
        }
    }

    // =====================================================================================
    // Inbound processing
    // =====================================================================================

    private suspend fun onHelloLocked(link: Link, hello: Frame.Hello) {
        if (hello.nodeId == selfId) {
            logger.log(LogCategory.ERROR, "HELLO carries my own id; ignoring endpoint ${link.endpointId}")
            return
        }
        val now = clock()
        val t = verifyHelloLocked(hello, now)
        if (!t.accepted && (config.requireSignatures || t.level == TrustLevel.INVALID)) {
            stats = stats.copy(rejectedUntrusted = stats.rejectedUntrusted + 1)
            logger.log(LogCategory.SECURITY, "HELLO from endpoint ${link.endpointId} claiming ${hello.nodeId} rejected: ${t.reason}")
            return
        }
        link.nodeId = hello.nodeId
        link.trust = t
        knownNodes[hello.nodeId] = now
        addEdge(selfId, hello.nodeId, now)
        val held = replicableIdsLocked(now)
        val ackedIds = acked.toList().takeLast(config.summaryMaxIds)
        logger.log(
            LogCategory.PEER,
            "handshake: endpoint ${link.endpointId} is node ${hello.nodeId} [${t.level}]${if (hello.isGateway) " (gateway)" else ""}; " +
                "sending SUMMARY (${held.size} held, ${ackedIds.size} acked)",
        )
        enqueueControlLocked(link, -1, summaryPacket(hello.nodeId, SummaryPayload(held, ackedIds, emptyList())))
        if (gatewayActive()) emitBeaconLocked(force = false)
        sendExtensionLocked(link, now)
        // Fast detection: hand a newcomer the gateway routes and SOS we hold now, not at the next 10 s tick.
        packets.values.filter { it.packet.type == PacketType.GATEWAY_BEACON || it.packet.priority == Priority.CRITICAL }
            .forEach { scheduleReplicationLocked(it, exclude = null) }
    }

    private suspend fun handlePacketLocked(link: Link, p: Packet) {
        val from = link.nodeId ?: return
        if (p.type == PacketType.SUMMARY) {
            handleSummaryLocked(link, from, p)
            return
        }
        if (p.type == PacketType.CHUNK) {
            // Hop-by-hop receipt (also for duplicates) so the sender's bulk window can advance.
            link.queue.offer("lack-${p.packetId}", -3, WireCodec.encode(Frame.LinkAck(listOf(p.packetId))))
            link.wake.trySend(Unit)
        }
        val now = clock()
        peerSet(from).add(p.packetId)
        val fullRoute = p.routeHistory + selfId
        fullRoute.forEach { knownNodes[it] = now }
        fullRoute.zipWithNext().forEach { (a, b) -> addEdge(a, b, now) }

        if (!dup.firstTime(p.packetId, now)) {
            stats = stats.copy(duplicatesRejected = stats.duplicatesRejected + 1)
            logger.log(
                LogCategory.DUPLICATE,
                "duplicate ${p.type} ${short(p.packetId)} from $from rejected (route ${p.routeHistory.joinToString("→")})",
                p.packetId,
            )
            if (p.type == PacketType.GATEWAY_BEACON) beaconUpdateLocked(p, from, now)
            return
        }
        if (p.packetId in acked) {
            logger.log(LogCategory.DUPLICATE, "${p.type} ${short(p.packetId)} already acknowledged; dropped", p.packetId)
            return
        }
        // Light controls use a short LOCAL relay lifetime: unrelated wall clocks must not
        // discard the very messages used to synchronize monotonic show clocks.
        if (p.type != PacketType.LIGHT_SHOW && p.isExpired(now)) {
            stats = stats.copy(expired = stats.expired + 1)
            logger.log(LogCategory.EXPIRY, "dropped expired ${p.type} ${short(p.packetId)} from $from", p.packetId)
            return
        }
        val verdict = trust.verifyPacket(p, now)
        if (!verdict.accepted && (config.requireSignatures || verdict.level == TrustLevel.INVALID)) {
            // A forged copy must not "use up" the ID: otherwise racing a tampered copy ahead of the real one would
            // get the real one dropped as a duplicate.
            dup.forget(p.packetId)
            peerHas[from]?.remove(p.packetId)
            stats = stats.copy(rejectedUntrusted = stats.rejectedUntrusted + 1)
            logger.log(LogCategory.SECURITY, "REJECTED ${p.type} ${short(p.packetId)} claiming ${p.sourceId} from $from: ${verdict.reason}", p.packetId)
            return
        }
        packetTrust[p.packetId] = verdict
        val local = if (p.type == PacketType.LIGHT_SHOW)
            p.copy(routeHistory = fullRoute, createdAt = now, expiresAt = now + 5_000)
            else p.copy(routeHistory = fullRoute)
        logger.log(
            LogCategory.RX,
            "rx ${p.type} ${short(p.packetId)} ${p.priority} ${p.sourceId}→${p.destinationId} ttl=${p.ttl} hops=${p.hopCount} " +
                "route=${fullRoute.joinToString("→")} from $from",
            p.packetId,
        )
        when (p.type) {
            PacketType.LIGHT_SHOW -> {
                if (p.destinationId == selfId || p.isBroadcast) {
                    if (!lightControls.trySend(LightControl(local, monotonicClock())).isSuccess)
                        logger.log(LogCategory.ERROR, "light-show control buffer full; dropped control")
                }
                if (p.destinationId != selfId) storeAndForwardLocked(local, from)
            }
            PacketType.ACK -> handleAckLocked(local, from)
            PacketType.GATEWAY_BEACON -> handleBeaconLocked(local, from)
            PacketType.INET_REQUEST -> when {
                gatewayActive() -> executeRequestLocked(local)
                answerFromRelayCacheLocked(local) -> Unit
                else -> storeAndForwardLocked(local, from)
            }
            PacketType.BULLETIN -> if (handleBulletinLocked(local, verdict, now)) storeAndForwardLocked(local, from)
            PacketType.INET_RESPONSE -> {
                if (p.destinationId == selfId) deliverLocked(local) else { cacheAnswerFromLocked(local); storeAndForwardLocked(local, from) }
            }
            else -> when {
                p.destinationId == selfId -> deliverLocked(local)
                p.isBroadcast -> { deliverLocked(local); storeAndForwardLocked(local, from) }
                else -> storeAndForwardLocked(local, from)
            }
        }
    }

    private suspend fun handleSummaryLocked(link: Link, from: NodeId, p: Packet) {
        val s = try { WireCodec.decodeSummary(p.payload) } catch (e: WireFormatException) {
            logger.log(LogCategory.ERROR, "bad SUMMARY from $from: ${e.message}"); return
        }
        link.peerSummaryAt = clock()
        peerSet(from).addAll(s.held)
        var newlyAcked = 0
        // A SUMMARY is unsigned hearsay. For broadcasts and CRITICAL packets we hold, only a signed ACK may stop
        // replication, so a hostile neighbour cannot silence an SOS by claiming it was delivered.
        s.acked.filter { id -> packets[id]?.packet?.let { it.isBroadcast || it.priority == Priority.CRITICAL } != true }
            .forEach { if (markAckedLocked(it, quiet = true)) newlyAcked++ }
        var served = 0
        for (id in s.want) {
            val sp = packets[id] ?: continue
            if (isReplicable(sp, clock())) { peerSet(from).remove(id); if (enqueuePacketLocked(link, sp)) served++ }
        }
        val missing = s.held.filter { !dup.contains(it) && !packets.containsKey(it) && it !in acked }
        if (s.held.isNotEmpty() || s.acked.isNotEmpty()) {
            logger.log(
                LogCategory.QUEUE,
                "SUMMARY from $from: holds ${s.held.size}, acked ${s.acked.size} ($newlyAcked new); requesting ${missing.size} missing",
            )
        }
        if (s.want.isNotEmpty()) logger.log(LogCategory.QUEUE, "$from requested ${s.want.size} packets; serving $served")
        if (missing.isNotEmpty()) {
            enqueueControlLocked(link, -1, summaryPacket(from, SummaryPayload(emptyList(), emptyList(), missing.take(config.summaryMaxIds))))
        }
    }

    private suspend fun deliverLocked(p: Packet) {
        val now = clock()
        val latency = now - p.createdAt
        stats = stats.copy(deliveredToMe = stats.deliveredToMe + 1)
        metrics += DeliveryMetric(p.packetId, p.type, p.priority, p.sourceId, p.destinationId, p.hopCount, p.routeHistory, latency, null, now, "destination")
        if (p.type != PacketType.CHUNK || logChunk(p)) {
            logger.log(
                LogCategory.DELIVERY,
                "DELIVERED ${p.type} ${short(p.packetId)} ${p.priority} from ${p.sourceId}: hops=${p.hopCount} " +
                    "route=${p.routeHistory.joinToString("→")} latency=${latency}ms",
                p.packetId,
            )
        }
        if (!p.isBroadcast) {
            val sp = StoredPacket(p, PacketState.DELIVERED, now, origin = false)
            packets[p.packetId] = sp
            store.upsert(sp)
            markAckedLocked(p.packetId, quiet = true)
        }
        if (p.type == PacketType.SOS) lastSosFrom[p.sourceId] = now
        when (p.type) {
            PacketType.TEXT, PacketType.SOS -> inbox += inboxOf(p, now)
            PacketType.INET_RESPONSE -> handleInetResponseLocked(p, now)
            PacketType.CHUNK -> handleChunkLocked(p, now)
            else -> Unit
        }
        val ack = AckPayload(p.packetId, p.type, now, p.hopCount, p.routeHistory, p.isBroadcast)
        val prio = if (p.priority == Priority.CRITICAL) Priority.CRITICAL else Priority.HIGH
        originateLocked(
            PacketType.ACK, p.sourceId, WireCodec.encodeAck(ack), Classification(prio, "CONTROL", listOf("ack")),
            config.ackLifetimeMs, quiet = true,
        )
    }

    private suspend fun handleAckLocked(ackPacket: Packet, from: NodeId) {
        val a = try { WireCodec.decodeAck(ackPacket.payload) } catch (e: WireFormatException) {
            logger.log(LogCategory.ERROR, "bad ACK from $from"); return
        }
        val target = packets[a.ackedPacketId]?.packet
        if (target != null && !target.isBroadcast && !target.isForAnyGateway && target.destinationId != ackPacket.sourceId) {
            stats = stats.copy(rejectedUntrusted = stats.rejectedUntrusted + 1)
            logger.log(LogCategory.SECURITY, "ignored ACK for ${short(a.ackedPacketId)} from ${ackPacket.sourceId}: only ${target.destinationId} can acknowledge it", a.ackedPacketId)
            return
        }
        if (!a.broadcastAck) markAckedLocked(a.ackedPacketId, quiet = false, reason = "ACK from ${ackPacket.sourceId}")
        if (ackPacket.destinationId != selfId) {
            storeAndForwardLocked(ackPacket, from)
            return
        }
        val now = clock()
        stats = stats.copy(acksReceived = stats.acksReceived + 1)
        val sp = StoredPacket(ackPacket, PacketState.DELIVERED, now, origin = false)
        packets[ackPacket.packetId] = sp
        store.upsert(sp)
        // The ACK itself has arrived: say so in our SUMMARYs, so relays stop carrying copies of it (they used to
        // replicate every ACK for its whole 30 min lifetime and re-flood it after every restart).
        markAckedLocked(ackPacket.packetId, quiet = true)
        val original = packets[a.ackedPacketId]?.packet
        val createdAt = original?.createdAt ?: tracked[a.ackedPacketId]?.createdAt ?: now
        val rtt = now - createdAt
        val oneWay = a.deliveredAt - createdAt
        metrics += DeliveryMetric(
            a.ackedPacketId, a.ackedType, original?.priority ?: Priority.NORMAL, selfId, ackPacket.sourceId,
            a.hopCount, a.route, oneWay, rtt, now, "source",
        )
        tracked[a.ackedPacketId]?.let { t ->
            tracked[a.ackedPacketId] = if (a.broadcastAck) {
                t.copy(
                    status = TrackStatus.DELIVERED, confirmedBy = (t.confirmedBy + ackPacket.sourceId).distinct(),
                    deliveredRoute = if (t.deliveredRoute.isEmpty()) a.route else t.deliveredRoute,
                    hopCount = if (t.deliveredRoute.isEmpty()) a.hopCount else t.hopCount,
                    rttMs = t.rttMs ?: rtt, oneWayMs = t.oneWayMs ?: oneWay,
                )
            } else {
                t.copy(status = TrackStatus.DELIVERED, deliveredRoute = a.route, hopCount = a.hopCount, rttMs = rtt, oneWayMs = oneWay)
            }
        }
        inetResults[a.ackedPacketId]?.let { r ->
            if (r.reachedGatewayAt == null) inetResults[a.ackedPacketId] = r.copy(reachedGatewayAt = now)
        }
        val transferId = chunkOwner[a.ackedPacketId]
        if (transferId != null) {
            chunkOwner.remove(a.ackedPacketId)
            transfersOut[transferId]?.let { t ->
                val done = t.done + 1
                val upd = t.copy(done = done, completedAt = if (done >= t.total) now else null)
                transfersOut[transferId] = upd
                if (done >= t.total) {
                    logger.log(LogCategory.DELIVERY, "transfer '${t.name}' fully acknowledged by ${t.peer}: ${t.totalBytes}B in ${now - t.startedAt}ms")
                }
            }
            return
        }
        logger.log(
            LogCategory.DELIVERY,
            "ACK: ${a.ackedType} ${short(a.ackedPacketId)} delivered to ${ackPacket.sourceId} via ${a.route.joinToString("→")} " +
                "hops=${a.hopCount} rtt=${rtt}ms one-way=${oneWay}ms",
            a.ackedPacketId,
        )
    }

    private suspend fun handleBeaconLocked(b: Packet, from: NodeId) {
        val bp = try { WireCodec.decodeBeacon(b.payload) } catch (e: WireFormatException) { return }
        if (bp.gatewayId == selfId) return
        if (bp.gatewayId != b.sourceId) {
            logger.log(LogCategory.SECURITY, "beacon for ${bp.gatewayId} originated by ${b.sourceId} ignored", b.packetId)
            return
        }
        val now = clock()
        stats = stats.copy(beaconsReceived = stats.beaconsReceived + 1)
        beaconUpdateLocked(b, from, now)
        if (b.ttl > 0) {
            val sp = StoredPacket(b, PacketState.QUEUED, now, origin = false)
            packets[b.packetId] = sp
            ephemeral.add(b.packetId)
            scheduleReplicationLocked(sp, exclude = from)
        }
    }

    private fun beaconUpdateLocked(b: Packet, from: NodeId, now: Long) {
        val bp = runCatching { WireCodec.decodeBeacon(b.payload) }.getOrNull() ?: return
        val gatewayId = bp.gatewayId
        if (gatewayId == selfId || gatewayId != b.sourceId) return
        val verified = packetTrust[b.packetId]?.level == TrustLevel.VERIFIED
        if (gateways.update(gatewayId, b.hopCount, from, now, verified, bp.load)) {
            logger.log(LogCategory.BEACON, "gateway $gatewayId at ${b.hopCount} hop(s) via $from", b.packetId)
            // A (better) route to a gateway appeared: retry queued Internet requests now.
            packets.values.filter { it.packet.type == PacketType.INET_REQUEST }.forEach { scheduleReplicationLocked(it, exclude = null) }
        }
    }

    private suspend fun executeRequestLocked(p: Packet) {
        if (!executed.add(p.packetId)) return
        val gw = gateway ?: return
        val req = try { WireCodec.decodeInetRequest(p.payload) } catch (e: WireFormatException) {
            logger.log(LogCategory.ERROR, "bad INET_REQUEST ${short(p.packetId)}"); return
        }
        val now = clock()
        val sp = StoredPacket(p, PacketState.DELIVERED, now, origin = p.sourceId == selfId)
        packets[p.packetId] = sp
        store.upsert(sp)
        markAckedLocked(p.packetId, quiet = true)
        stats = stats.copy(inetExecuted = stats.inetExecuted + 1)
        logger.log(
            LogCategory.GATEWAY,
            "GATEWAY received ${req.kind} ${req.url.ifEmpty { req.body.take(60).ifEmpty { "(webhook)" } }} from ${p.sourceId}, request path ${p.routeHistory.joinToString("→")}",
            p.packetId,
        )
        if (p.sourceId != selfId) {
            val ack = AckPayload(p.packetId, p.type, now, p.hopCount, p.routeHistory, false)
            originateLocked(PacketType.ACK, p.sourceId, WireCodec.encodeAck(ack), Classification(Priority.HIGH, "CONTROL", listOf("ack")), config.ackLifetimeMs, quiet = true)
        }
        val t = if (p.sourceId == selfId) TrustResult(if (identity.cert.selfSigned) TrustLevel.SELF_SIGNED else TrustLevel.VERIFIED, identity.cert.role)
            else packetTrust[p.packetId] ?: TrustResult.UNSIGNED
        val ctx = NeedContext(
            requester = p.sourceId, kind = req.kind, text = req.body.ifEmpty { req.url }, claimedBattery = req.battery,
            requesterVerified = t.level == TrustLevel.VERIFIED, requesterRole = t.role,
            sinceRequesterSosMs = lastSosFrom[p.sourceId]?.let { now - it }, urgentUsed = 0, urgentAllowance = 0,
        )
        val sched = scheduler ?: return
        val deliver: suspend (InetResponse) -> Unit = { resp -> mutex.withLock { respondLocked(p, resp); publish() } }

        // Already answered recently: no queue, no budget, no upstream call.
        val kind = when (req.kind) { InetKind.ASK -> AnswerKind.ASK; InetKind.READ -> AnswerKind.READ; else -> null }
        val hit = kind?.let { answerCache.get(it, if (it == AnswerKind.ASK) req.body else req.url, now) }
        if (hit != null) {
            sched.recordCacheHit(ctx)
            val sc = sched.scoreOnly(ctx)
            respondLocked(p, InetResponse(p.packetId, InetStatus.OK, 200, InetResponse.ANSWER_CONTENT_TYPE, WireCodec.encodeAnswer(hit), false,
                "answered from the gateway's cache", selfId, p.routeHistory, now, servedFrom = InetResponse.SERVED_GATEWAY_CACHE,
                need = (sc.value * 100).toInt(), needCategory = sc.category.label, needReasons = sc.reasons))
            return
        }
        val decision = gw.policy.admit(p.sourceId, req, now)
        if (decision is PolicyDecision.Reject) {
            respondLocked(p, gw.response(p.packetId, p.routeHistory, InetStatus.REJECTED, decision.reason, now))
            return
        }
        sched.submit(p.packetId, ctx, sosFlag = p.priority == Priority.CRITICAL,
            execute = { gw.perform(p.packetId, p.sourceId, req, p.routeHistory, clock) },
            deliver = deliver)
    }

    /** Sends (or, for our own request, applies) a gateway response, caching any verified answer on the way. */
    private suspend fun respondLocked(p: Packet, raw: InetResponse) {
        val resp = raw.copy(gatewayId = selfId, requestRoute = p.routeHistory)
        logger.log(
            LogCategory.GATEWAY,
            "GATEWAY result ${resp.status} http=${resp.httpCode} ${resp.body.size}B served=${resp.servedFrom} need=${resp.need} wait=${resp.queueWaitMs}ms ${resp.message}",
            p.packetId,
        )
        if (resp.contentType == InetResponse.ANSWER_CONTENT_TYPE) verifiedAnswer(resp)?.let { answerCache.put(it) }
        if (p.sourceId == selfId) {
            applyInetResponseLocked(resp, listOf(selfId), clock())
        } else {
            originateLocked(
                PacketType.INET_RESPONSE, p.sourceId, WireCodec.encodeInetResponse(resp),
                Classification(p.priority, "INTERNET", listOf("response to ${short(p.packetId)}")), config.inetLifetimeMs,
            )
        }
    }

    /** Decodes an answer body and returns it only if a trusted authority signed it. */
    private fun verifiedAnswer(resp: InetResponse): Answer? {
        val a = runCatching { WireCodec.decodeAnswer(resp.body) }.getOrNull() ?: return null
        return a.takeIf { trust.verifyAuthority(it.signedBytes(), it.signature, it.keyId) }
    }

    /** A relay that already holds a verified, fresh answer to this question replies itself and stops the request here. */
    private suspend fun answerFromRelayCacheLocked(p: Packet): Boolean {
        val req = runCatching { WireCodec.decodeInetRequest(p.payload) }.getOrNull() ?: return false
        val kind = when (req.kind) { InetKind.ASK -> AnswerKind.ASK; InetKind.READ -> AnswerKind.READ; else -> return false }
        val now = clock()
        val hit = answerCache.get(kind, if (kind == AnswerKind.ASK) req.body else req.url, now) ?: return false
        val sp = StoredPacket(p, PacketState.DELIVERED, now, origin = false)
        packets[p.packetId] = sp
        store.upsert(sp)
        markAckedLocked(p.packetId, quiet = true)
        relayCacheAnswers++
        logger.log(LogCategory.GATEWAY, "answered ${req.kind} from ${p.sourceId} out of my cache (gateway not needed)", p.packetId)
        val resp = InetResponse(p.packetId, InetStatus.OK, 200, InetResponse.ANSWER_CONTENT_TYPE, WireCodec.encodeAnswer(hit), false,
            "answered by a nearby phone's cache ($selfId)", selfId, p.routeHistory, now, servedFrom = InetResponse.SERVED_RELAY_CACHE)
        originateLocked(PacketType.INET_RESPONSE, p.sourceId, WireCodec.encodeInetResponse(resp),
            Classification(p.priority, "INTERNET", listOf("cached answer for ${short(p.packetId)}")), config.inetLifetimeMs)
        return true
    }

    /** Relays learn verified answers from the responses they carry. */
    private fun cacheAnswerFromLocked(p: Packet) {
        val resp = runCatching { WireCodec.decodeInetResponse(p.payload) }.getOrNull() ?: return
        if (resp.status != InetStatus.OK || resp.contentType != InetResponse.ANSWER_CONTENT_TYPE) return
        verifiedAnswer(resp)?.let { answerCache.put(it) }
    }

    /** Validates and stores a bulletin; returns false for boards nobody trusted signed (they are not forwarded). */
    private fun handleBulletinLocked(p: Packet, verdict: TrustResult, now: Long): Boolean {
        val b = runCatching { WireCodec.decodeBulletin(p.payload) }.getOrElse {
            logger.log(LogCategory.ERROR, "bad BULLETIN ${short(p.packetId)}"); return false
        }
        val signer = b.nodeSigner
        val ok = if (signer == null) {
            trust.verifyAuthority(b.signedBytes(), b.signature, b.keyId)
        } else {
            val cert = p.cert?.let { runCatching { Certificate.decode(it) }.getOrNull() }
            signer == p.sourceId && b.boardId == p.sourceId && verdict.level == TrustLevel.VERIFIED &&
                verdict.role in setOf(Role.ORGANIZER, Role.RESPONDER) && cert != null &&
                trust.verifyNode(b.signedBytes(), b.signature, cert.publicKey)
        }
        if (!ok) {
            logger.log(LogCategory.SECURITY, "bulletin '${b.boardId}' from ${p.sourceId} ignored: not signed by the authority or a verified organizer", p.packetId)
            return false
        }
        val cur = bulletins[b.boardId]
        if (cur == null || b.seq > cur.bulletin.seq) {
            bulletins[b.boardId] = BulletinView(b, signer == null, if (signer == null) Role.USER else verdict.role, p.sourceId, now, p.hopCount)
            logger.log(LogCategory.DELIVERY, "bulletin '${b.title}' (${b.items.size} items, seq ${b.seq}) from ${p.sourceId}", p.packetId)
        }
        return true
    }

    private fun handleInetResponseLocked(p: Packet, now: Long) {
        val resp = try { WireCodec.decodeInetResponse(p.payload) } catch (e: WireFormatException) {
            logger.log(LogCategory.ERROR, "bad INET_RESPONSE ${short(p.packetId)}"); return
        }
        applyInetResponseLocked(resp, p.routeHistory, now)
    }

    private fun applyInetResponseLocked(resp: InetResponse, responseRoute: List<NodeId>, now: Long) {
        val r = inetResults[resp.requestId]
        if (r == null) {
            logger.log(LogCategory.GATEWAY, "INET_RESPONSE for unknown request ${short(resp.requestId)} ignored")
            return
        }
        if (r.response != null) {
            logger.log(LogCategory.DUPLICATE, "second INET_RESPONSE for ${short(resp.requestId)} from ${resp.gatewayId} ignored (first gateway wins)")
            return
        }
        val answer = if (resp.contentType == InetResponse.ANSWER_CONTENT_TYPE) runCatching { WireCodec.decodeAnswer(resp.body) }.getOrNull() else null
        val verified = answer != null && trust.verifyAuthority(answer.signedBytes(), answer.signature, answer.keyId)
        if (answer != null && verified) answerCache.put(answer)
        if (answer != null && !verified) logger.log(LogCategory.SECURITY, "answer for ${short(resp.requestId)} is not signed by a trusted authority: shown as unverified", resp.requestId)
        inetResults[resp.requestId] = r.copy(response = resp, responseRoute = responseRoute, completedAt = now,
            answer = answer, answerVerified = verified, servedFrom = resp.servedFrom)
        logger.log(
            LogCategory.DELIVERY,
            "INTERNET round trip ${short(resp.requestId)}: ${resp.status} http=${resp.httpCode} ${resp.body.size}B " +
                "out ${resp.requestRoute.joinToString("→")} → Internet → back ${responseRoute.joinToString("→")} in ${now - r.sentAt}ms",
            resp.requestId,
        )
    }

    private fun handleChunkLocked(p: Packet, now: Long) {
        val c = try { WireCodec.decodeChunk(p.payload) } catch (e: WireFormatException) { return }
        val a = transfersIn.getOrPut(c.transferId) { Assembly(p.sourceId, c.name, c.total, c.totalBytes, now) }
        if (c.index !in 0 until a.total || a.parts[c.index] != null) return
        a.parts[c.index] = c.data
        a.received++
        if (a.received == a.total) {
            a.completedAt = now
            val all = a.parts.fold(ByteArray(0)) { acc, part -> acc + part!! }
            synchronized(receivedFiles) {
                receivedFiles[c.transferId] = all
                while (receivedFiles.size > 5) receivedFiles.remove(receivedFiles.keys.first())
            }
            logger.log(LogCategory.DELIVERY, "transfer '${c.name}' from ${p.sourceId} complete: ${all.size}B in ${a.total} chunks, ${now - a.startedAt}ms")
        }
    }

    // =====================================================================================
    // Store-and-forward + replication
    // =====================================================================================

    private suspend fun storeAndForwardLocked(p: Packet, from: NodeId?) {
        val now = clock()
        if (p.ttl <= 0) {
            stats = stats.copy(ttlExhausted = stats.ttlExhausted + 1)
            logger.log(LogCategory.QUEUE, "TTL exhausted for ${p.type} ${short(p.packetId)} at hop ${p.hopCount}: not forwarded", p.packetId)
            return
        }
        val sp = StoredPacket(p, PacketState.QUEUED, now, origin = false)
        packets[p.packetId] = sp
        if (p.type == PacketType.GATEWAY_BEACON || p.type == PacketType.LIGHT_SHOW) ephemeral.add(p.packetId) else store.upsert(sp)
        val n = scheduleReplicationLocked(sp, exclude = from)
        if (n == 0 && p.type != PacketType.ACK) {
            logger.log(LogCategory.QUEUE, "stored ${p.type} ${short(p.packetId)} for ${p.destinationId}: no eligible peer now (store-and-forward)", p.packetId)
        }
    }

    private fun isReplicable(sp: StoredPacket, now: Long): Boolean =
        (sp.state == PacketState.QUEUED || sp.state == PacketState.FORWARDING) &&
            sp.id !in acked && !sp.packet.isExpired(now) && sp.packet.ttl > 0

    /** Queues [sp] on the links that should carry it; returns how many links were chosen. */
    private fun scheduleReplicationLocked(sp: StoredPacket, exclude: NodeId?): Int {
        val now = clock()
        if (!isReplicable(sp, now)) return 0
        val p = sp.packet
        if (p.type == PacketType.INET_REQUEST && gatewayActive()) return 0
        val candidates = links.values.filter { l ->
            val n = l.nodeId
            n != null && n != exclude && n !in p.routeHistory
        }
        if (candidates.isEmpty()) return 0
        fun lacking(ls: List<Link>) = ls.filter { p.packetId !in peerSet(it.nodeId!!) }

        val targets: List<Link> = when {
            p.isForAnyGateway -> {
                val g = gateways.best(now)
                val via = g?.let { e -> candidates.firstOrNull { it.nodeId == e.gatewayId } ?: candidates.firstOrNull { it.nodeId == e.viaPeer } }
                if (via != null) lacking(listOf(via)) else lacking(candidates)
            }
            !p.isBroadcast -> {
                val direct = candidates.firstOrNull { it.nodeId == p.destinationId }
                if (direct != null) lacking(listOf(direct)) else lacking(candidates)
            }
            else -> lacking(candidates)
        }.let { ts -> adviseLocked(p, ts) }
        var n = 0
        targets.forEach { if (enqueuePacketLocked(it, sp)) n++ }
        return n
    }

    /**
     * Per-link send order. CRITICAL always first; then gateway beacons (tiny, rate-limited, and what makes a joining
     * phone usable); then HIGH, NORMAL, LOW. ACKs older than [RouterConfig.staleAckAfterMs] are housekeeping and go
     * after fresh NORMAL traffic. Control frames (HELLO, SUMMARY, LINK_ACK) use negative ranks.
     */
    private fun queueRank(p: Packet, now: Long): Int = when {
        p.priority == Priority.CRITICAL -> 0
        p.type == PacketType.GATEWAY_BEACON -> 5
        p.type == PacketType.ACK && now - p.createdAt > config.staleAckAfterMs -> Priority.NORMAL.rank * 10 + 5
        else -> p.priority.rank * 10
    }

    /**
     * Safety envelope for [NextHopAdvisor]: it may only narrow ordinary traffic (TEXT, CHUNK, Internet request/response).
     * CRITICAL/SOS, ACKs, beacons and light-show controls never reach it. A throwing, empty, foreign or slow answer
     * falls back to the deterministic targets; consecutive slow answers switch the advisor off for this session.
     */
    private fun adviseLocked(p: Packet, ts: List<Link>): List<Link> {
        val advisor = nextHopAdvisor ?: return ts
        if (advisorDisabled || p.priority == Priority.CRITICAL || p.type !in ADVISABLE || ts.isEmpty()) return ts
        val startNs = System.nanoTime()
        val chosen = try {
            advisor.choose(p, ts.map { it.nodeId!! }).toSet()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            advisorFailures++
            logger.log(LogCategory.ERROR, "advisor failed (${e.javaClass.simpleName}); using flooding targets", p.packetId)
            return ts
        }
        if ((System.nanoTime() - startNs) / 1_000_000 <= config.advisorBudgetMs) {
            advisorSlowCalls = 0
        } else if (++advisorSlowCalls >= config.advisorMaxSlowCalls) {
            advisorDisabled = true
            logger.log(LogCategory.ERROR, "advisor exceeded ${config.advisorBudgetMs} ms $advisorSlowCalls times in a row; disabled for this session")
        }
        return ts.filter { it.nodeId in chosen }.ifEmpty { ts }
    }

    private fun enqueuePacketLocked(link: Link, sp: StoredPacket): Boolean {
        if (!link.queue.offer(sp.id, queueRank(sp.packet, clock()))) return false
        link.wake.trySend(Unit)
        return true
    }

    private fun sendExtensionLocked(link: Link, now: Long) {
        val ext = linkExtension ?: return
        val peer = link.nodeId ?: return
        link.extensionSentAt = now
        val payload = try { ext.onPeerIdentified(peer) } catch (e: Exception) {
            logger.log(LogCategory.ERROR, "link extension failed for $peer: ${e.message}"); null
        } ?: return
        val bytes = WireCodec.encode(Frame.Extension(payload))
        if (bytes.size > transport.maxFrameBytes) { logger.log(LogCategory.ERROR, "link extension frame ${bytes.size}B too large; not sent"); return }
        // Positive rank: after CRITICAL, beacons and fresh HIGH traffic.
        link.queue.offer("ext-$peer", EXTENSION_RANK, bytes)
        link.wake.trySend(Unit)
    }

    private fun enqueueControlLocked(link: Link, rank: Int, packet: Packet) {
        link.queue.offer("ctl-${packet.packetId}", rank, WireCodec.encode(Frame.Data(packet)))
        link.wake.trySend(Unit)
    }

    private fun replicableIdsLocked(now: Long): List<String> =
        packets.values.filter { isReplicable(it, now) }
            .sortedWith(compareBy({ it.packet.priority.rank }, { -it.packet.createdAt }))
            .take(config.summaryMaxIds)
            .map { it.id }

    /** Returns true if the id was not yet known as acknowledged. */
    private suspend fun markAckedLocked(packetId: String, quiet: Boolean, reason: String = ""): Boolean {
        if (!acked.add(packetId)) return false
        while (acked.size > 20_000) acked.remove(acked.first())
        val now = clock()
        store.addAcked(packetId, now)
        packets[packetId]?.let { sp ->
            if (sp.state == PacketState.QUEUED || sp.state == PacketState.FORWARDING) {
                packets[packetId] = sp.copy(state = PacketState.DELIVERED, updatedAt = now)
                store.updateState(packetId, PacketState.DELIVERED, now)
            }
        }
        // The source may learn of delivery only through a SUMMARY (the ACK packet itself never reached it).
        tracked[packetId]?.let { t -> if (t.status != TrackStatus.DELIVERED) tracked[packetId] = t.copy(status = TrackStatus.DELIVERED) }
        var dequeued = 0
        links.values.forEach { if (it.queue.remove(packetId)) dequeued++ }
        if (!quiet) {
            logger.log(LogCategory.QUEUE, "stop replicating ${short(packetId)} ($reason)${if (dequeued > 0) ", removed from $dequeued queue(s)" else ""}", packetId)
        }
        return true
    }

    // =====================================================================================
    // Sending
    // =====================================================================================

    private suspend fun senderLoop(link: Link) {
        for (signal in link.wake) {
            while (true) {
                val out = mutex.withLock { nextFrameLocked(link) } ?: break
                val ok = try {
                    transport.send(link.endpointId, out.bytes)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    false
                }
                mutex.withLock { afterSendLocked(link, out, ok); publish() }
                if (!ok) delay(config.sendRetryDelayMs)
            }
        }
    }

    private fun nextFrameLocked(link: Link): Outgoing? {
        if (links[link.endpointId] !== link) return null
        val now = clock()
        link.bulkInFlight.entries.removeIf { now - it.value > config.bulkAckTimeoutMs }
        while (true) {
            val head = link.queue.peek() ?: return null
            // The queue is priority-ordered, so if the head is bulk, nothing more urgent is waiting: hold it back
            // while the window is full instead of stuffing Nearby's buffer (which would delay a later SOS).
            if (head.control == null && link.bulkInFlight.size >= config.bulkWindow &&
                packets[head.key]?.packet?.type == PacketType.CHUNK
            ) {
                armWindowTimerLocked(link)
                return null
            }
            val item = link.queue.poll() ?: return null
            if (item.control != null) return Outgoing(item, null, item.control)
            val node = link.nodeId ?: continue
            val sp = packets[item.key] ?: continue
            if (!isReplicable(sp, now) || item.key in peerSet(node)) continue
            val wire = sp.packet.copy(ttl = sp.packet.ttl - 1, hopCount = sp.packet.hopCount + 1)
            val bytes = WireCodec.encode(Frame.Data(wire))
            if (bytes.size > transport.maxFrameBytes) {
                logger.log(LogCategory.ERROR, "${wire.type} ${short(wire.packetId)} is ${bytes.size}B > transport max ${transport.maxFrameBytes}B; dropped", wire.packetId)
                continue
            }
            return Outgoing(item, wire, bytes)
        }
    }

    /** Wakes the sender after the ack timeout so a stalled window (no LINK_ACK support on the peer) can't block forever. */
    private fun armWindowTimerLocked(link: Link) {
        if (link.windowTimerArmed) return
        link.windowTimerArmed = true
        scope.launch {
            delay(config.bulkAckTimeoutMs + 1)
            mutex.withLock { link.windowTimerArmed = false }
            link.wake.trySend(Unit)
        }
    }

    private suspend fun afterSendLocked(link: Link, out: Outgoing, ok: Boolean) {
        val node = link.nodeId
        if (!ok) {
            link.consecutiveFailures++
            stats = stats.copy(sendFailures = stats.sendFailures + 1)
            logger.log(LogCategory.ERROR, "send to ${node ?: link.endpointId} failed (${out.packet?.type ?: "control"}), attempt ${link.consecutiveFailures}")
            // HELLO is never given up while the link lives: without it the peer discards everything we send.
            if (links[link.endpointId] === link && (out.item.key == HELLO_KEY || link.consecutiveFailures <= config.maxConsecutiveFailures)) {
                link.queue.offer(out.item.key, out.item.rank, out.item.control)
            }
            return
        }
        link.consecutiveFailures = 0
        link.bytesSent += out.bytes.size
        link.framesSent++
        stats = stats.copy(bytesSent = stats.bytesSent + out.bytes.size)
        val p = out.packet ?: return
        if (node != null) peerSet(node).add(p.packetId)
        val now = clock()
        if (p.type == PacketType.CHUNK) link.bulkInFlight[p.packetId] = now
        packets[p.packetId]?.let { sp ->
            if (sp.state == PacketState.QUEUED) {
                packets[p.packetId] = sp.copy(state = PacketState.FORWARDING, updatedAt = now)
                if (p.packetId !in ephemeral) store.updateState(p.packetId, PacketState.FORWARDING, now)
            }
        }
        if (p.sourceId != selfId) stats = stats.copy(packetsForwarded = stats.packetsForwarded + 1)
        tracked[p.packetId]?.let { t ->
            if (node != null) {
                tracked[p.packetId] = t.copy(
                    firstHops = (t.firstHops + node).distinct(),
                    status = if (t.status == TrackStatus.QUEUED) TrackStatus.IN_TRANSIT else t.status,
                )
            }
        }
        if (p.type != PacketType.CHUNK || logChunk(p)) {
            logger.log(
                LogCategory.FORWARD,
                "${if (p.sourceId == selfId) "sent" else "forwarded"} ${p.type} ${short(p.packetId)} ${p.priority} → $node " +
                    "(ttl ${p.ttl} hop ${p.hopCount}) ${out.bytes.size}B, ${link.queue.size} left in queue",
                p.packetId,
            )
        }
    }

    // =====================================================================================
    // Helpers
    // =====================================================================================

    /** Every originated packet is signed and carries our certificate. */
    private fun newPacket(type: PacketType, dest: NodeId, payload: ByteArray, cls: Classification, lifetimeMs: Long, now: Long, ttl: Int = config.defaultTtl): Packet {
        val p = Packet(Packet.newId(), selfId, dest, type, cls.priority, now, now + lifetimeMs, ttl, 0, payload, listOf(selfId), cls.label)
        return p.copy(signature = identity.sign(p.signingBytes()), cert = identity.certBytes)
    }

    private suspend fun originateLocked(
        type: PacketType,
        dest: NodeId,
        payload: ByteArray,
        cls: Classification,
        lifetimeMs: Long,
        ttl: Int = config.defaultTtl,
        persist: Boolean = true,
        quiet: Boolean = false,
    ): Packet {
        val now = clock()
        val p = newPacket(type, dest, payload, cls, lifetimeMs, now, ttl)
        dup.firstTime(p.packetId, now)
        stats = stats.copy(packetsOriginated = stats.packetsOriginated + 1)
        val sp = StoredPacket(p, PacketState.QUEUED, now, origin = true)
        packets[p.packetId] = sp
        if (persist) store.upsert(sp) else ephemeral.add(p.packetId)
        val n = scheduleReplicationLocked(sp, exclude = null)
        if (!quiet) {
            logger.log(
                LogCategory.QUEUE,
                "new ${p.type} ${short(p.packetId)} → $dest priority=${p.priority} label=${p.label} ${payload.size}B; " +
                    if (n > 0) "queued on $n link(s)" else "no peer yet, held (store-and-forward)",
                p.packetId,
            )
        }
        return p
    }

    private fun emitBeaconLocked(force: Boolean): Boolean {
        if (!gatewayActive()) return false
        val now = clock()
        if (!force && now - lastBeaconAt < config.minBeaconGapMs) return false
        lastBeaconAt = now
        val payload = WireCodec.encodeBeacon(BeaconPayload(selfId, ++beaconSeq, "ask,read,http-get,webhook",
            scheduler?.queueLength ?: 0, scheduler?.capacity ?: 0))
        val p = newPacket(PacketType.GATEWAY_BEACON, Addresses.BROADCAST, payload, Classification(Priority.NORMAL, "CONTROL", listOf("beacon")), config.beaconLifetimeMs, now, config.beaconTtl)
        dup.firstTime(p.packetId, now)
        val sp = StoredPacket(p, PacketState.QUEUED, now, origin = true)
        packets[p.packetId] = sp
        ephemeral.add(p.packetId)
        val n = scheduleReplicationLocked(sp, exclude = null)
        logger.log(LogCategory.BEACON, "gateway beacon #$beaconSeq sent to $n peer(s)", p.packetId)
        return true
    }

    private fun summaryPacket(peer: NodeId, s: SummaryPayload): Packet {
        val now = clock()
        return Packet(
            Packet.newId(), selfId, peer, PacketType.SUMMARY, Priority.HIGH, now, now + 60_000, 0, 0,
            WireCodec.encodeSummary(s), listOf(selfId), "CONTROL",
        )
    }

    private fun trackLocked(p: Packet, cls: Classification, preview: String) {
        tracked[p.packetId] = TrackedPacket(p.packetId, p.type, p.priority, cls.label, cls.reasons, p.destinationId, p.createdAt, preview.take(120))
    }

    private fun peerSet(node: NodeId): MutableSet<String> = peerHas.getOrPut(node) {
        object : LinkedHashSet<String>() {
            override fun add(element: String): Boolean {
                val r = super.add(element)
                if (size > 20_000) remove(first())
                return r
            }
        }
    }

    private fun addEdge(a: NodeId, b: NodeId, now: Long) {
        if (a == b) return
        edges[if (a < b) a to b else b to a] = now
    }

    private fun inboxOf(p: Packet, now: Long) = InboxMessage(
        p.packetId, p.type, p.sourceId, p.destinationId, p.payload.toString(Charsets.UTF_8), p.priority, p.label,
        p.routeHistory, p.hopCount, now - p.createdAt, now, packetTrust[p.packetId]?.level ?: TrustLevel.UNSIGNED,
    )

    private fun preview(p: Packet): String = when (p.type) {
        PacketType.TEXT, PacketType.SOS -> p.payload.toString(Charsets.UTF_8)
        PacketType.INET_REQUEST -> runCatching { WireCodec.decodeInetRequest(p.payload).let { "${it.kind} ${it.url}${it.body}" } }.getOrDefault("")
        else -> ""
    }.take(120)

    private fun logChunk(p: Packet): Boolean {
        val idx = runCatching { WireCodec.decodeChunk(p.payload) }.getOrNull() ?: return true
        return idx.index == 0 || idx.index == idx.total - 1 || idx.index % 4 == 0
    }

    private fun short(id: String) = id.take(8)

    private fun publish() {
        val now = clock()
        var pendingCount = 0
        var pendingBytes = 0L
        packets.values.forEach { if (isReplicable(it, now) && it.packet.type != PacketType.GATEWAY_BEACON) { pendingCount++; pendingBytes += it.packet.payload.size } }
        val linkEdges = links.values.mapNotNull { l -> l.nodeId?.let { if (selfId < it) selfId to it else it to selfId } }
        _state.value = MeshSnapshot(
            selfId = selfId,
            peers = links.values.map { PeerInfo(it.endpointId, it.nodeId, it.queue.size, it.bytesSent, it.framesSent, it.connectedAt) },
            gateways = gateways.all(now),
            isGateway = gatewayActive(),
            stats = stats,
            tracked = tracked.values.reversed(),
            inbox = inbox.reversed(),
            inet = inetResults.values.reversed(),
            transfers = (transfersOut.values + transfersIn.entries.map { (id, a) ->
                TransferProgress(id, a.name, a.from, false, a.total, a.received, a.totalBytes, a.startedAt, a.completedAt)
            }).sortedByDescending { it.startedAt },
            knownNodes = (knownNodes.keys + selfId).distinct(),
            edges = (edges.keys + linkEdges).distinct(),
            queued = packets.values.filter { isReplicable(it, now) && it.packet.type != PacketType.GATEWAY_BEACON && it.packet.type != PacketType.ACK }
                .sortedWith(compareBy({ it.packet.priority.rank }, { it.packet.createdAt }))
                .take(200)
                .map { QueuedInfo(it.id, it.packet.type, it.packet.priority, it.packet.sourceId, it.packet.destinationId, it.state, it.packet.ttl, it.packet.hopCount, now - it.packet.createdAt) },
            metrics = metrics.takeLast(500),
            pendingCount = pendingCount,
            pendingBytes = pendingBytes,
            identity = IdentityView(
                selfId, identity.cert.fingerprint,
                if (identity.cert.selfSigned) TrustLevel.SELF_SIGNED else TrustLevel.VERIFIED,
                if (identity.cert.selfSigned) Role.USER else identity.cert.role, identity.cert.issuer, identity.cert.expiresAt,
                trust.anchorFingerprints(),
            ),
            bulletins = bulletins.values.sortedByDescending { it.receivedAt },
            gatewayQueue = if (gatewayActive()) scheduler?.snapshot() else null,
            answerCacheSize = answerCache.size(),
            relayCacheAnswers = relayCacheAnswers,
        )
    }

    /** Plain-text report for the Debug screen's export: counters, per-packet metrics, then the log. */
    fun exportReport(): String {
        val s = state.value
        val sb = StringBuilder()
        sb.appendLine("MeshMind debug export — node ${s.selfId} — ${MeshLogger.formatTime(clock())}")
        sb.appendLine("peers=${s.connectedPeers} gateways=${s.gateways.map { "${it.gatewayId}@${it.hopDistance}hop via ${it.viaPeer}" }}")
        sb.appendLine("stats=${s.stats}")
        sb.appendLine()
        sb.appendLine("side,packetId,type,priority,src,dst,hops,route,oneWayMs,rttMs")
        s.metrics.forEach { m ->
            sb.appendLine("${m.side},${m.packetId},${m.type},${m.priority},${m.sourceId},${m.destinationId},${m.hops},${m.route.joinToString(">")},${m.oneWayMs},${m.rttMs ?: ""}")
        }
        sb.appendLine()
        logger.entries.value.forEach { sb.appendLine(logger.format(it)) }
        return sb.toString()
    }
}
