package dev.meshmind.core.routing

/**
 * Per-link outbound queue. Nearby Connections sends payloads in submission order with no notion of priority,
 * so the router hands the transport exactly one frame at a time and always picks the next one from here.
 * That is what lets a CRITICAL SOS slip in between two LOW chunks of a large transfer.
 *
 * Ordering: rank ascending (control frames use negative ranks), then FIFO within a rank.
 */
class PacketPriorityQueue {
    data class Item(
        /** Packet ID, or a unique key for control frames. */
        val key: String,
        val rank: Int,
        val seq: Long,
        /** Pre-encoded bytes for control frames (hello/summary); null for packets looked up at send time. */
        val control: ByteArray? = null,
    )

    private val heap = java.util.PriorityQueue<Item>(compareBy<Item>({ it.rank }, { it.seq }))
    private val keys = HashSet<String>()
    private var seq = 0L

    /** Returns false if [key] is already queued. */
    fun offer(key: String, rank: Int, control: ByteArray? = null): Boolean {
        if (!keys.add(key)) return false
        heap.add(Item(key, rank, seq++, control))
        return true
    }

    fun peek(): Item? = heap.peek()

    fun poll(): Item? = heap.poll()?.also { keys.remove(it.key) }

    fun remove(key: String): Boolean {
        if (!keys.remove(key)) return false
        heap.removeIf { it.key == key }
        return true
    }

    fun contains(key: String): Boolean = key in keys
    val size: Int get() = heap.size
    fun isEmpty(): Boolean = heap.isEmpty()
    fun snapshot(): List<Item> = heap.sortedWith(compareBy({ it.rank }, { it.seq }))
    fun clear() { heap.clear(); keys.clear() }
}
