package dev.meshmind.core.routing

import dev.meshmind.core.model.PacketState
import dev.meshmind.core.model.PacketType
import dev.meshmind.core.model.StoredPacket

/**
 * Durable store-and-forward buffer. The router keeps an in-memory mirror for speed and writes through to this.
 * The Android app implements it with Room; tests and the simulator use [InMemoryPacketStore].
 */
/** What the router needs at startup: packets it must hold in memory, and IDs it only needs to recognise. */
class StartupLoad(val packets: List<StoredPacket>, val finishedIds: List<String>)

interface PacketStore {
    suspend fun loadAll(): List<StoredPacket>

    /**
     * Startup load. Only packets still in flight, plus finished TEXT/SOS/INET_REQUEST (shown in the UI), are loaded
     * in full; other finished packets (chunks, ACKs, responses) come back as IDs, so a long history of bulk
     * transfers can't exhaust the heap on restart (seen on a phone: OOM at 256 MiB reloading ~9,300 packets).
     */
    suspend fun loadStartup(): StartupLoad = loadAll().partition { keepInMemoryAtStartup(it.state, it.packet.type) }
        .let { (keep, rest) -> StartupLoad(keep, rest.map { it.id }) }
    suspend fun upsert(packet: StoredPacket)
    suspend fun updateState(packetId: String, state: PacketState, at: Long)
    suspend fun loadAckedIds(): List<String>
    suspend fun addAcked(packetId: String, at: Long)
    suspend fun clear()
}

fun keepInMemoryAtStartup(state: PacketState, type: PacketType): Boolean =
    state == PacketState.QUEUED || state == PacketState.FORWARDING ||
        type == PacketType.TEXT || type == PacketType.SOS || type == PacketType.INET_REQUEST

class InMemoryPacketStore : PacketStore {
    private val lock = Any()
    private val packets = LinkedHashMap<String, StoredPacket>()
    private val acked = LinkedHashSet<String>()

    override suspend fun loadAll(): List<StoredPacket> = synchronized(lock) { packets.values.toList() }
    override suspend fun upsert(packet: StoredPacket) = synchronized(lock) { packets[packet.id] = packet }

    override suspend fun updateState(packetId: String, state: PacketState, at: Long) = synchronized(lock) {
        packets[packetId]?.let { packets[packetId] = it.copy(state = state, updatedAt = at) }
        Unit
    }

    override suspend fun loadAckedIds(): List<String> = synchronized(lock) { acked.toList() }
    override suspend fun addAcked(packetId: String, at: Long) = synchronized(lock) { acked.add(packetId); Unit }
    override suspend fun clear() = synchronized(lock) { packets.clear(); acked.clear() }

    fun snapshot(): Map<String, StoredPacket> = synchronized(lock) { LinkedHashMap(packets) }
}
