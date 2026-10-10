package dev.meshmind.app

import android.content.Context
import android.util.Log
import android.os.SystemClock
import dev.meshmind.core.light.LightShowController
import dev.meshmind.app.light.LightConsoleBridge
import dev.meshmind.app.data.MeshDatabase
import dev.meshmind.app.data.RoomPacketStore
import dev.meshmind.app.net.InternetMonitor
import dev.meshmind.app.net.NearbyTransport
import dev.meshmind.app.net.TransportStatus
import dev.meshmind.app.security.IdentityStore
import dev.meshmind.core.gateway.BackendClient
import dev.meshmind.core.gateway.GatewayConfig
import dev.meshmind.core.gateway.GatewayService
import dev.meshmind.core.gateway.JvmHttpExecutor
import dev.meshmind.core.gateway.SchedulerConfig
import dev.meshmind.core.security.Role
import dev.meshmind.core.security.TrustStore
import dev.meshmind.core.routing.LogCategory
import dev.meshmind.core.routing.MeshLogger
import dev.meshmind.core.routing.Router
import dev.meshmind.core.sage.ContactHistory
import dev.meshmind.core.sage.NodeLoad
import dev.meshmind.core.sage.SageNet
import dev.meshmind.core.sage.SageShadow
import android.os.BatteryManager
import java.io.File
import java.util.TimeZone
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

data class GatewaySettings(
    /** Owner's explicit consent to relay other people's (allowlisted) requests over this phone's Internet. */
    val consent: Boolean = false,
    /** Demo override: act as gateway even if Android hasn't validated connectivity. */
    val forceGateway: Boolean = false,
    val webhookUrl: String = "",
    val allowlist: Set<String> = GatewayConfig.DEFAULT_ALLOWLIST,
    /** Upstream requests per minute this phone's owner is willing to relay (the fair-share budget). */
    val perMinute: Int = 30,
)

/** Progress of phone verification, for the Identity screen. */
data class EnrollState(
    val busy: Boolean = false,
    val codeSent: Boolean = false,
    /** In the backend's demo mode the OTP is returned to the app instead of sent by SMS. */
    val demoCode: String? = null,
    val message: String? = null,
    val error: Boolean = false,
)

/**
 * Process-wide owner of the mesh: node identity, settings, and (while the foreground service runs) the
 * NearbyTransport + Router pair. The UI only observes StateFlows exposed here.
 */
class MeshRuntime(private val context: Context) {
    private val prefs = context.getSharedPreferences("meshmind", Context.MODE_PRIVATE)
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val db by lazy { MeshDatabase.build(context) }
    private val lifecycleLock = Mutex()

    private val _nodeId = MutableStateFlow(loadNodeId())
    val nodeId: StateFlow<String> = _nodeId.asStateFlow()

    private val _gateway = MutableStateFlow(loadGatewaySettings())
    val gatewaySettings: StateFlow<GatewaySettings> = _gateway.asStateFlow()

    val identityStore = IdentityStore(context)

    /** MeshMind backend (answers, live info, phone verification). Used by gateways and by phones enrolling online. */
    private val _backendUrl = MutableStateFlow(prefs.getString("backend.url", "") ?: "")
    val backendUrl: StateFlow<String> = _backendUrl.asStateFlow()
    val backend = BackendClient({ _backendUrl.value })

    fun setBackendUrl(url: String) {
        _backendUrl.value = url.trim()
        prefs.edit().putString("backend.url", url.trim()).apply()
    }

    private val _enroll = MutableStateFlow(EnrollState())
    val enroll: StateFlow<EnrollState> = _enroll.asStateFlow()

    /** Bumped whenever the identity changes, so screens re-read it while the mesh is stopped. */
    private val _identityVersion = MutableStateFlow(0)
    val identityVersion: StateFlow<Int> = _identityVersion.asStateFlow()

    val internet = InternetMonitor(context, appScope)

    private val _router = MutableStateFlow<Router?>(null)
    val router: StateFlow<Router?> = _router.asStateFlow()

    private val _lightShow = MutableStateFlow<LightShowController?>(null)
    val lightShow: StateFlow<LightShowController?> = _lightShow.asStateFlow()
    private val _lightConsole = MutableStateFlow<LightConsoleBridge?>(null)
    val lightConsole: StateFlow<LightConsoleBridge?> = _lightConsole.asStateFlow()

    suspend fun enableLightConsole() = lifecycleLock.withLock {
        if (_lightConsole.value == null) _lightConsole.value = LightConsoleBridge(context,
            requireNotNull(_lightShow.value) { "Start the mesh first" }, requireNotNull(session))
    }

    suspend fun disableLightConsole() = lifecycleLock.withLock {
        _lightConsole.value?.close(); _lightConsole.value = null
        if (_lightShow.value?.state?.value?.hosting == true) _lightShow.value?.leave()
    }

    /** SAGE v1 in shadow mode: scores routing decisions and logs them; the deterministic router stays in charge. */
    private val _sage = MutableStateFlow<SageShadow?>(null)
    val sage: StateFlow<SageShadow?> = _sage.asStateFlow()
    private val _sageEnabled = MutableStateFlow(prefs.getBoolean("sage.shadow", true))
    val sageEnabled: StateFlow<Boolean> = _sageEnabled.asStateFlow()
    private val sageNet: SageNet? by lazy {
        runCatching { SageNet.loadBundled() }.onFailure { Log.w("MeshMind", "SAGE model unavailable; shadow mode logs nothing", it) }.getOrNull()
    }

    fun setSageEnabled(on: Boolean) {
        _sageEnabled.value = on
        prefs.edit().putBoolean("sage.shadow", on).apply()
    }

    /** SAGE shadow scoring runs here, off the router's lock; a backed-up queue drops the oldest opportunity. */
    private val sageWorker = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(256),
        { job -> Thread(job, "sage-shadow").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } },
        { job, pool -> _sage.value?.noteDropped(); pool.queue.poll(); pool.queue.offer(job) })

    private fun sageFile(id: String) =File(context.filesDir, "sage_history_$id.bin")

    private fun saveSage(s: SageShadow) {
        runCatching {
            val f = sageFile(s.history.nodeId)
            val tmp = File(f.path + ".tmp")
            tmp.writeBytes(s.encodeHistory())
            tmp.renameTo(f)
        }.onFailure { Log.w("MeshMind", "could not persist SAGE history", it) }
    }

    /** Local wall-clock seconds: hour-of-day and day features use the phone's local time. */
    private fun localSeconds(): Double {
        val now = System.currentTimeMillis()
        return (now + TimeZone.getDefault().getOffset(now)) / 1000.0
    }

    /** Battery percentage reported with Ask requests (a small need signal for gateways); -1 if unknown. */
    fun batteryPercent(): Int = (batteryFraction() * 100).toInt().takeIf { it in 0..100 } ?: -1

    private fun batteryFraction(): Double = runCatching {
        val bm = context.getSystemService(BatteryManager::class.java)
        bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 }?.div(100.0)
    }.getOrNull() ?: 1.0

    private val _transportStatus = MutableStateFlow(TransportStatus())
    val transportStatus: StateFlow<TransportStatus> = _transportStatus.asStateFlow()

    /** Topology lock: node names (part before '-') or full IDs this phone may link with; empty = anyone. */
    private val _peerFilter = MutableStateFlow(prefs.getStringSet("peerFilter", emptySet())!!.toSet())
    val peerFilter: StateFlow<Set<String>> = _peerFilter.asStateFlow()

    fun setPeerFilter(names: Set<String>) {
        _peerFilter.value = names
        prefs.edit().putStringSet("peerFilter", names).apply()
        transport?.enforcePeerFilter()
    }

    private fun peerAllowed(remoteName: String): Boolean {
        val f = _peerFilter.value
        return f.isEmpty() || remoteName in f || remoteName.substringBefore('-') in f
    }

    private var session: CoroutineScope? = null
    private var transport: NearbyTransport? = null

    val isGatewayActive: Boolean
        get() = _gateway.value.consent && (internet.validated.value || _gateway.value.forceGateway)

    // ---- identity & settings ------------------------------------------------------------------

    private fun loadNodeId(): String = prefs.getString("nodeId", null) ?: run {
        val id = "Node-" + UUID.randomUUID().toString().take(4)
        prefs.edit().putString("nodeId", id).apply()
        id
    }

    /** Renaming changes the routing identity, so it is only allowed while the mesh is stopped. */
    fun rename(name: String): Boolean {
        if (_router.value != null) return false
        val clean = name.trim().replace(Regex("[^A-Za-z0-9_]"), "").take(12)
        if (clean.isEmpty()) return false
        val suffix = UUID.randomUUID().toString().take(4)
        val id = "$clean-$suffix"
        prefs.edit().putString("nodeId", id).apply()
        _nodeId.value = id
        return true
    }

    private fun loadGatewaySettings() = GatewaySettings(
        consent = prefs.getBoolean("gw.consent", false),
        forceGateway = prefs.getBoolean("gw.force", false),
        webhookUrl = prefs.getString("gw.webhook", "") ?: "",
        allowlist = prefs.getStringSet("gw.allowlist", null) ?: GatewayConfig.DEFAULT_ALLOWLIST,
        perMinute = prefs.getInt("gw.perMinute", 30),
    )

    fun updateGateway(transform: (GatewaySettings) -> GatewaySettings) {
        val s = transform(_gateway.value)
        _gateway.value = s
        prefs.edit()
            .putBoolean("gw.consent", s.consent)
            .putBoolean("gw.force", s.forceGateway)
            .putString("gw.webhook", s.webhookUrl)
            .putStringSet("gw.allowlist", s.allowlist)
            .putInt("gw.perMinute", s.perMinute)
            .apply()
    }

    private fun gatewayConfig(): GatewayConfig = _gateway.value.let {
        GatewayConfig(consent = it.consent, allowedDomains = it.allowlist, webhookUrl = it.webhookUrl, globalPerMinute = it.perMinute)
    }

    private fun schedulerConfig(): SchedulerConfig = SchedulerConfig(perMinute = _gateway.value.perMinute)

    // ---- phone verification --------------------------------------------------------------------

    fun requestCode(phone: String) = launch {
        _enroll.value = EnrollState(busy = true)
        _enroll.value = runCatching { backend.enrollStart(phone.trim()) }.fold(
            { code -> EnrollState(codeSent = true, demoCode = code, message = if (code != null) "Demo mode: code shown below" else "Code sent by SMS") },
            { EnrollState(message = "Could not reach the backend: ${it.message}", error = true) },
        )
    }

    /** Verifies the code, stores the certificate and restarts the mesh (if running) so it signs as the new identity. */
    fun verifyCode(phone: String, code: String, role: Role, organizerCode: String) = launch {
        _enroll.value = _enroll.value.copy(busy = true, message = null, error = false)
        runCatching { enrollNow(phone, code, role, organizerCode) }
            .onSuccess { r -> _enroll.value = EnrollState(message = "Verified ✓ (${r.name.lowercase()})") }
            .onFailure { _enroll.value = _enroll.value.copy(busy = false, message = it.message ?: "verification failed", error = true) }
    }

    /** The verification itself; returns the role granted. Also used by the debug benchmark receiver. */
    suspend fun enrollNow(phone: String, code: String, role: Role, organizerCode: String): Role {
        val e = backend.enrollVerify(phone.trim(), code.trim(), _nodeId.value, identityStore.signer.publicKey, role, organizerCode.trim())
        identityStore.saveEnrollment(e.certificate, e.authorityKey)
        _identityVersion.value++
        if (_router.value != null) { stopMesh(); startMesh() }
        return e.certificate.role
    }

    fun forgetVerification() = launch {
        identityStore.forgetEnrollment()
        _identityVersion.value++
        _enroll.value = EnrollState(message = "Verification removed")
        if (_router.value != null) { stopMesh(); startMesh() }
    }

    /** Gateway: fetch the official live-info board from the backend and broadcast it if it changed. */
    suspend fun refreshBulletin(): Boolean {
        val r = _router.value ?: return false
        if (!isGatewayActive || _backendUrl.value.isBlank()) return false
        return runCatching { r.broadcastBulletin(backend.bulletin(null, null)) }
            .onFailure { r.logger.log(LogCategory.GATEWAY, "live info fetch failed: ${it.message}") }
            .getOrDefault(false)
    }

    // ---- mesh lifecycle (called by MeshService) -----------------------------------------------

    suspend fun startMesh() = lifecycleLock.withLock {
        if (_router.value != null) return@withLock
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val id = _nodeId.value
        val logger = MeshLogger(System::currentTimeMillis, capacity = 4000) { e -> Log.d("MeshMind", "[${e.category}] ${e.message}") }
        val t = NearbyTransport(context, id, scope, log = { msg -> logger.log(LogCategory.PEER, "nearby: $msg") }, peerAllowed = ::peerAllowed)
        val gateway = GatewayService(id, ::gatewayConfig, JvmHttpExecutor(), isActive = { isGatewayActive },
            answers = { backend.takeIf { _backendUrl.value.isNotBlank() } })
        val identity = identityStore.identityFor(id, System.currentTimeMillis())
        val trust = TrustStore(identityStore.anchors())
        val history = sageFile(id).takeIf { it.exists() }?.let { f -> runCatching { ContactHistory.decodeState(f.readBytes(), id) }.getOrNull() }
            ?: ContactHistory(id)
        var routerRef: Router? = null
        val sage = SageShadow(
            id, history, sageNet,
            localLoad = {
                val snap = routerRef?.state?.value
                NodeLoad(
                    queueFrac = ((snap?.pendingBytes ?: 0L) / SAGE_QUEUE_BYTES).coerceIn(0.0, 1.0),
                    queueCount = snap?.pendingCount ?: 0,
                    battery = batteryFraction(),
                    isGateway = isGatewayActive,
                )
            },
            localSeconds = ::localSeconds,
            enabled = { _sageEnabled.value },
            scoreOn = { job -> sageWorker.execute(job) },
        )
        sageWorker.execute { sage.warmUp() }
        val r = Router(id, t, RoomPacketStore(db.dao()), scope, gateway = gateway, logger = logger,
            monotonicClock = SystemClock::elapsedRealtime, nextHopAdvisor = sage, linkExtension = sage,
            identity = identity, trust = trust, schedulerConfig = ::schedulerConfig)
        routerRef = r
        val lights = LightShowController(r, scope, SystemClock::elapsedRealtime)
        r.start()
        t.start()
        scope.launch { t.status.collectLatest { _transportStatus.value = it } }
        // Housekeeping: expiry + retry. Slow on purpose (battery); new links trigger immediate SUMMARY exchange anyway.
        scope.launch { while (isActive) { delay(10_000); r.tick() } }
        // SAGE contact bins: every 20 s, record every MeshMind phone in range (linked or only discovered).
        scope.launch {
            var lastSave = SystemClock.elapsedRealtime()
            while (isActive) {
                val start = localSeconds()
                delay((history.binSeconds * 1000).toLong())
                val snap = r.state.value
                val seen = snap.connectedPeers.toSet() + t.discoveredNames()
                val gatewayPeer = snap.gateways.any { it.hopDistance == 1 && it.gatewayId in seen }
                sage.observeBin(start, seen, gatewayPeer)
                if (SystemClock.elapsedRealtime() - lastSave > 5 * 60_000L) { saveSage(sage); lastSave = SystemClock.elapsedRealtime() }
            }
        }
        // Gateway beacons: every 20 s while active (handshakes also trigger one, rate-limited by the router).
        scope.launch {
            var wasActive = false
            while (isActive) {
                val active = isGatewayActive
                if (active != wasActive) {
                    logger.log(LogCategory.GATEWAY, if (active) "this phone is now an Internet gateway" else "gateway role inactive")
                    wasActive = active
                }
                if (active) r.emitBeacon()
                delay(20_000)
            }
        }
        // Live info: gateways fetch the official board every 5 minutes and broadcast it when it changes.
        scope.launch {
            while (isActive) {
                refreshBulletin()
                delay(5 * 60_000L)
            }
        }
        session = scope
        transport = t
        _router.value = r
        _lightShow.value = lights
        _sage.value = sage
        logger.log(LogCategory.INFO, "mesh started as $id (Nearby ${NearbyTransport.SERVICE_ID}, P2P_CLUSTER), " +
            "identity ${identity.cert.fingerprint} ${if (identity.cert.selfSigned) "self-signed" else "verified ${identity.cert.role}"}, " +
            "${trust.anchorFingerprints().size} trusted authorit${if (trust.anchorFingerprints().size == 1) "y" else "ies"}")
    }

    suspend fun stopMesh() = lifecycleLock.withLock {
        val r = _router.value ?: return@withLock
        _lightConsole.value?.close(); _lightConsole.value = null
        _lightShow.value?.let { it.leave(); it.close() }
        _lightShow.value = null
        runCatching { transport?.stop() }
        runCatching { r.stop() }
        _sage.value?.let { saveSage(it) }
        _sage.value = null
        session?.cancel()
        session = null
        transport = null
        _router.value = null
        _transportStatus.value = TransportStatus()
    }

    fun launch(block: suspend CoroutineScope.() -> Unit): Job = appScope.launch(block = block)

    private companion object {
        /** Research simulator's per-phone buffer (Scenario.queue_bytes): queue_frac is relative to it. */
        const val SAGE_QUEUE_BYTES = 262_144.0
    }
}
