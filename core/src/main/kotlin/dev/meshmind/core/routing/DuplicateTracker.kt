package dev.meshmind.core.routing

/**
 * Bounded seen-packet cache. Together with TTL and expiry this is what stops floods from looping forever.
 * Bounded so memory stays flat on long-running nodes; the oldest IDs fall out first, and by then
 * the packet's TTL/expiry has normally run out anyway.
 */
class DuplicateTracker(private val capacity: Int = 20_000) {
    private val seen = object : LinkedHashMap<String, Long>(256, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean = size > capacity
    }

    /** Returns true the first time [packetId] is offered, false for every duplicate. */
    @Synchronized
    fun firstTime(packetId: String, now: Long): Boolean {
        if (seen.containsKey(packetId)) return false
        seen[packetId] = now
        return true
    }

    @Synchronized
    fun contains(packetId: String): Boolean = seen.containsKey(packetId)

    /** Un-sees an ID (a forged copy was rejected): the genuine packet with that ID must still be accepted later. */
    @Synchronized
    fun forget(packetId: String) { seen.remove(packetId) }

    @Synchronized
    fun size(): Int = seen.size
}
