package dev.meshmind.core.light

import dev.meshmind.core.model.Addresses
import dev.meshmind.core.routing.LogCategory
import dev.meshmind.core.routing.Router
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

data class LightSnapshot(
    val offers: List<LightOffer> = emptyList(),
    val show: LightOffer? = null,
    val hosting: Boolean = false,
    val clock: ClockSample? = null,
    val readyCount: Int = 0,
    val note: String = "Choose a show to join, or host your own.",
    val seat: LightSeat = LightSeat(),
    val participants: List<LightParticipant> = emptyList(),
    val programs: List<LightProgram> = emptyList(),
) {
    fun positionAt(localNow: Long): Long? = show?.let { s -> clock?.let { localNow + it.offsetMs - s.startAt } }
    fun activeProgramAt(hostNow: Long) = programs.filter { it.effectiveAt <= hostNow }.maxByOrNull { it.revision }
    fun colorAt(localNow: Long, nodeId: String): Int {
        val show = show ?: return 0xFF0B1214.toInt()
        val position = positionAt(localNow) ?: return 0xFF0B1214.toInt()
        if (position < 0 || position >= show.durationMs) return 0xFF0B1214.toInt()
        val hostNow = localNow + (clock?.offsetMs ?: 0)
        val active = activeProgramAt(hostNow) ?: return show.preset.colorAt(position, show.periodMs)
        val color = active.colorAt(position, seat, nodeId)
        val previous = programs.filter { it.revision < active.revision && it.effectiveAt <= active.effectiveAt }.maxByOrNull { it.revision }
        val before = previous?.colorAt(position, seat, nodeId) ?: show.preset.colorAt(position, show.periodMs)
        return LightScene.blend(before, color, (hostNow - active.effectiveAt) / 1000.0)
    }
}

/** In-memory sessions only. Mesh relays work without opting into screen playback. */
class LightShowController(private val router: Router, scope: CoroutineScope, private val now: () -> Long) {
    private val lock = Mutex()
    private val _state = MutableStateFlow(LightSnapshot())
    val state = _state.asStateFlow()
    private val offers = LinkedHashMap<String, Pair<LightOffer, Long>>()
    private val stopped = LinkedHashMap<String, Long>()
    private val pending = LinkedHashMap<String, Long>()
    private val participants = LinkedHashMap<String, LightParticipant>()
    private val assignments = LinkedHashMap<String, LightSeat>()
    private var revision = 0L
    private var restoreCues: List<LightCue>? = null
    private val samples = ArrayList<ClockSample>()
    private var lastAdvertised = Long.MIN_VALUE / 2
    private var lastPing = Long.MIN_VALUE / 2
    private var lastDiscovery = Long.MIN_VALUE / 2
    private var joinedAt = 0L
    private var lastReady = Long.MIN_VALUE / 2
    private val jobs: List<Job>

    init {
        jobs = listOf(
            scope.launch { router.lightEvents.collect { event -> lock.withLock { receive(event) } } },
            scope.launch { while (isActive) { delay(500); lock.withLock { tick() } } },
        )
    }

    suspend fun host(preset: LightPreset, durationMs: Long = 60_000, periodMs: Long = 8_000) = lock.withLock {
        leaveLocked()
        revision = 0; restoreCues = null
        val show = LightOffer(UUID.randomUUID().toString(), router.selfId, preset, now() + 10_000, durationMs, periodMs)
        _state.value = _state.value.copy(show = show, hosting = true, clock = ClockSample(0, 0, now()),
            note = "Show scheduled. Other phones can join during the countdown or playback.")
        advertise(show)
    }

    suspend fun join(showId: String) = lock.withLock {
        val show = offers[showId]?.first ?: return@withLock
        leaveLocked()
        joinedAt = now()
        _state.value = _state.value.copy(show = show, note = "Synchronizing with ${show.hostId}…")
        ping(show)
    }

    suspend fun leave() = lock.withLock { leaveLocked() }

    /** Scheduled at the next beat after a three-second distribution lead. */
    suspend fun program(cues: List<LightCue>) = lock.withLock { programLocked(cues) }

    private suspend fun programLocked(cues: List<LightCue>, urgent: Boolean = false): LightProgram {
        val current = _state.value
        val show = requireNotNull(current.show) { "Host a show first" }
        require(current.hosting) { "Only the host can conduct this show" }
        require(cues.all { it.atMs < show.durationMs }) { "Cues must fall within the show duration" }
        val time = now()
        require(urgent || current.programs.none { it.effectiveAt > time }) { "A cue is already queued; wait for it to take effect" }
        val beatMs = 60_000L / cues.first().scene.bpm
        val lead = if (urgent) 1500 else 3000
        val effective = maxOf(show.startAt, show.startAt + (maxOf(0, time + lead - show.startAt) + beatMs - 1) / beatMs * beatMs)
        require(effective < show.startAt + show.durationMs) { "Show is ending; start a new show" }
        val program = LightProgram(show.showId, ++revision, effective, cues.toList())
        // An urgent blackout supersedes any pending cue. Peers keep revisions, so delayed
        // lower revisions cannot restore an older effect after the blackout has begun.
        val history = current.programs.filter { !urgent || it.effectiveAt <= time }
        _state.value = current.copy(programs = (history + program).takeLast(8), note = "Cue ${program.revision} queued on the host beat.")
        send(Addresses.BROADCAST, LightMessage.Program(program), stopping = urgent)
        return program
    }

    suspend fun blackout() = lock.withLock {
        val current = _state.value
        require(current.hosting && current.show != null) { "Host a show first" }
        val lastLit = current.programs.lastOrNull { program -> program.cues.any { it.scene.brightness > 0 } }
        if (lastLit != null || restoreCues == null)
            restoreCues = lastLit?.cues ?: listOf(LightCue(0, LightScene(effect = LightEffect.RAINBOW)))
        programLocked(listOf(LightCue(0, LightScene(effect = LightEffect.SOLID, primary = 0, secondary = 0, brightness = 0))), urgent = true)
    }

    suspend fun restore() = lock.withLock { programLocked(requireNotNull(restoreCues) { "Nothing to restore" }) }

    suspend fun finale(bpm: Int = 100) = lock.withLock {
        val current = _state.value
        val show = requireNotNull(current.show) { "Host a show first" }
        programLocked(LightSequences.finale(show.durationMs, bpm, maxOf(0, current.positionAt(now()) ?: 0)))
    }

    suspend fun setSeat(seat: LightSeat) = lock.withLock {
        _state.value = _state.value.copy(seat = seat)
        val show = _state.value.show
        if (show != null && !_state.value.hosting && _state.value.clock != null && now() - lastReady >= 500) ready(show)
    }

    suspend fun assign(nodeId: String, seat: LightSeat) = lock.withLock { assignLocked(nodeId, seat) }

    private suspend fun assignLocked(nodeId: String, seat: LightSeat) {
        val current = _state.value
        require(current.hosting && current.show != null) { "Host a show first" }
        require(nodeId in participants) { "Phone is not currently joined" }
        assignments[nodeId] = seat
        participants[nodeId]?.let { participants[nodeId] = it.copy(seat = seat) }
        send(nodeId, LightMessage.Seat(current.show.showId, seat))
        _state.value = _state.value.copy(participants = participants.values.toList())
    }

    suspend fun arrangeGrid() = lock.withLock {
        val nodes = participants.keys.sorted()
        val columns = kotlin.math.ceil(kotlin.math.sqrt(nodes.size.toDouble())).toInt().coerceAtLeast(1)
        val rows = (nodes.size + columns - 1) / columns
        nodes.forEachIndexed { index, node -> assignLocked(node, LightSeat(index % 4,
            if (columns == 1) 500 else (index % columns) * 1000 / (columns - 1),
            if (rows <= 1) 500 else (index / columns) * 1000 / (rows - 1))) }
    }

    suspend fun discover() = lock.withLock {
        if (now() - lastDiscovery >= 1_000) {
            lastDiscovery = now()
            send(Addresses.BROADCAST, LightMessage.Discover)
        }
    }

    private suspend fun leaveLocked(note: String = "Left the show. Choose another show or host your own.") {
        val current = _state.value
        if (current.hosting && current.show != null) {
            rememberStop(current.show.showId)
            send(Addresses.BROADCAST, LightMessage.Stop(current.show.showId), stopping = true)
        } else if (current.show != null) {
            send(current.show.hostId, LightMessage.Depart(current.show.showId))
        }
        pending.clear(); samples.clear(); participants.clear(); assignments.clear()
        _state.value = current.copy(show = null, hosting = false, clock = null, readyCount = 0, note = note, programs = emptyList(), participants = emptyList())
    }

    fun close() { jobs.forEach { it.cancel() } }

    private suspend fun send(destination: String, message: LightMessage, stopping: Boolean = false) {
        router.sendLightControl(destination, LightCodec.encode(message), stopping)
    }

    private suspend fun advertise(show: LightOffer, destination: String = Addresses.BROADCAST) {
        lastAdvertised = now()
        send(destination, LightMessage.Offer(show))
        sendPrograms(destination)
    }

    private suspend fun sendPrograms(destination: String) {
        val current = _state.value
        val active = current.activeProgramAt(now())
        val upcoming = current.programs.filter { it.effectiveAt > now() }.maxByOrNull { it.revision }
        listOfNotNull(active, upcoming).distinct().forEach { send(destination, LightMessage.Program(it)) }
    }

    private suspend fun ready(show: LightOffer) {
        lastReady = now()
        val current = _state.value
        send(show.hostId, LightMessage.Ready(show.showId, current.seat, current.clock?.roundTripMs ?: 0,
            current.programs.maxOfOrNull { it.revision } ?: 0))
    }

    private suspend fun ping(show: LightOffer) {
        val token = UUID.randomUUID().toString()
        val t1 = now()
        lastPing = t1
        pending[token] = t1
        send(show.hostId, LightMessage.Ping(show.showId, token, t1))
    }

    private fun rememberStop(showId: String) {
        stopped[showId] = now()
        while (stopped.size > 128) stopped.remove(stopped.keys.first())
        offers.remove(showId)
    }

    private suspend fun receive(event: Router.LightControl) {
        val message = try { LightCodec.decode(event.packet.payload) } catch (e: Exception) {
            router.logger.log(LogCategory.ERROR, "invalid light-show control: ${e.message}"); return
        }
        val source = event.packet.sourceId
        val current = _state.value
        val show = current.show
        when (message) {
            is LightMessage.Offer -> {
                val offer = message.show
                if (offer.hostId != source || offer.showId in stopped || "$source/${offer.showId}" in stopped || source == router.selfId) return
                // A repeated advertisement cannot change a session's timeline or effect.
                val previous = offers[offer.showId]?.first
                if (previous != null && previous != offer) return
                offers[offer.showId] = offer to now()
                while (offers.size > 32) offers.remove(offers.keys.first())
            }
            LightMessage.Discover -> if (current.hosting && show != null && now() - lastAdvertised >= 500) advertise(show)
            is LightMessage.Stop -> {
                val known = offers[message.showId]?.first ?: show?.takeIf { it.showId == message.showId }
                if (known != null && known.hostId != source) return
                // Remember stop-before-offer too, keyed to its source to prevent another host stopping it.
                rememberStop("$source/${message.showId}")
                if (known != null) rememberStop(message.showId)
                if (show?.showId == message.showId && show.hostId == source && !current.hosting)
                    leaveLocked("The host stopped this show.")
            }
            is LightMessage.Ping -> if (current.hosting && show?.showId == message.showId) {
                send(source, LightMessage.Pong(message.showId, message.token, message.t1, event.receivedAt, now()))
            }
            is LightMessage.Pong -> {
                if (current.hosting || show?.showId != message.showId || show.hostId != source) return
                val sentAt = pending.remove(message.token) ?: return
                if (sentAt != message.t1) return
                val sample = ClockSample.estimate(sentAt, message.t2, message.t3, event.receivedAt) ?: return
                samples.removeAll { now() - it.measuredAt > 20_000 }
                samples += sample
                while (samples.size > 10) samples.removeAt(0)
                val best = samples.minBy { it.roundTripMs }
                _state.value = current.copy(clock = best, note = "Timing synchronized; playback follows the host timeline.")
                ready(show)
            }
            is LightMessage.Ready -> if (current.hosting && show?.showId == message.showId) {
                if (source !in participants && participants.size >= 64) return
                val seat = assignments[source] ?: message.seat
                participants[source] = LightParticipant(source, seat, now(), message.roundTripMs, message.revision)
                if (seat != message.seat) send(source, LightMessage.Seat(show.showId, seat))
                if (message.revision < (current.programs.maxOfOrNull { it.revision } ?: 0)) sendPrograms(source)
            }
            is LightMessage.Program -> {
                val program = message.program
                if (current.hosting || show?.showId != program.showId || show.hostId != source) return
                if (program.cues.any { it.atMs >= show.durationMs }) return
                val old = current.programs.firstOrNull { it.revision == program.revision }
                if (old != null && old != program) return
                _state.value = current.copy(programs = (current.programs.filter { it.revision != program.revision } + program)
                    .sortedBy { it.revision }.takeLast(8))
            }
            is LightMessage.Seat -> if (!current.hosting && show?.showId == message.showId && show.hostId == source)
                _state.value = current.copy(seat = message.seat)
            is LightMessage.Depart -> if (current.hosting && show?.showId == message.showId) {
                participants.remove(source); assignments.remove(source)
            }
        }
        // Stop arriving before its offer must suppress that offer as well.
        offers.entries.removeAll { (id, pair) -> "${pair.first.hostId}/$id" in stopped }
        _state.value = _state.value.copy(offers = offers.values.map { it.first }, participants = participants.values.toList(), readyCount = participants.size)
    }

    private suspend fun tick() {
        val time = now()
        offers.entries.removeAll { time - it.value.second > 12_000 }
        stopped.entries.removeAll { time - it.value > 600_000 }
        pending.entries.removeAll { time - it.value > 5_000 }
        participants.entries.removeAll { time - it.value.lastSeen > 15_000 }
        assignments.keys.retainAll(participants.keys)
        val current = _state.value
        val show = current.show
        if (show != null) {
            val position = current.positionAt(time)
            if (position != null && position >= show.durationMs) {
                leaveLocked("Show finished. Choose another show or host your own.")
            } else if (current.hosting) {
                if (time - lastAdvertised >= 3_000) advertise(show)
            } else {
                if (time - lastPing >= if (samples.size < 5) 500 else 5_000) ping(show)
                if (current.clock == null && time - joinedAt > 15_000)
                    leaveLocked("Could not synchronize. Check the mesh connection, then join again.")
            }
        }
        _state.value = _state.value.copy(offers = offers.values.map { it.first }, readyCount = participants.size, participants = participants.values.toList())
    }
}
