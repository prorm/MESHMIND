package dev.meshmind.core.gateway

import dev.meshmind.core.model.Answer
import dev.meshmind.core.model.AnswerKind
import dev.meshmind.core.model.AnswerSource
import dev.meshmind.core.model.Bulletin
import dev.meshmind.core.model.BulletinItem
import dev.meshmind.core.model.BulletinKind
import dev.meshmind.core.model.NodeId
import dev.meshmind.core.security.Ec
import dev.meshmind.core.security.Signer
import kotlinx.coroutines.delay

/**
 * Small LRU of authority-verified answers, keyed by normalised question. Every phone keeps one: a relay that
 * already carried an answer can serve the next person who asks the same thing without bothering the gateway.
 * Only answers whose signature verified are ever stored, so a stranger cannot poison anyone's cache.
 */
class AnswerCache(private val capacity: Int = 200) {
    private val map = object : LinkedHashMap<String, Answer>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Answer>?) = size > capacity
    }
    var hits = 0L
        private set

    @Synchronized
    fun put(a: Answer) { map[keyOf(a.kind, a.query)] = a }

    @Synchronized
    fun get(kind: AnswerKind, query: String, now: Long): Answer? {
        val k = keyOf(kind, query)
        val a = map[k] ?: return null
        if (!a.isFresh(now)) { map.remove(k); return null }
        hits++
        return a
    }

    @Synchronized
    fun size(): Int = map.size

    companion object {
        private val stop = setOf(
            "a", "an", "the", "is", "are", "was", "were", "be", "to", "of", "in", "on", "at", "for", "and", "or",
            "do", "does", "did", "i", "me", "my", "we", "you", "it", "this", "that", "there", "what", "whats",
            "please", "tell", "can", "could", "would", "will", "now", "today", "right", "currently", "any", "where",
        )

        /** "Is the metro running?" and "metro running now" share a key: lowercase, no stop words, sorted. */
        fun normalize(query: String): String =
            query.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").split(' ')
                .filter { it.isNotBlank() && it !in stop }.distinct().sorted().joinToString(" ")

        fun keyOf(kind: AnswerKind, query: String) = when (kind) {
            AnswerKind.ASK -> "ask:" + normalize(query)
            AnswerKind.READ -> "read:" + query.trim()
        }
    }
}

/** Signs answers and bulletins the way the backend does; used in-process by tests and the simulator. */
class LocalAuthority(val signer: Signer) {
    val keyId: String = Ec.fingerprint(signer.publicKey)

    fun answer(kind: AnswerKind, query: String, text: String, sources: List<AnswerSource>, now: Long, ttlMs: Long, provider: String): Answer {
        val unsigned = Answer(kind, query, text, sources, now, now + ttlMs, provider, keyId, ByteArray(0))
        return Answer(kind, query, text, sources, now, now + ttlMs, provider, keyId, signer.sign(unsigned.signedBytes()))
    }

    fun bulletin(boardId: String, title: String, seq: Long, now: Long, items: List<BulletinItem>): Bulletin =
        Bulletin(boardId, title, seq, now, items, keyId, signer.sign(Bulletin.canonical(boardId, title, seq, now, items, keyId)))
}

/** Deterministic answer service for tests and the in-app simulator; answers are genuinely authority-signed. */
class FakeAnswerService(
    private val authority: LocalAuthority,
    private val clock: () -> Long,
    private val latencyMs: Long = 0,
    private val responder: (String) -> String = { q -> "Simulated answer to \"$q\"." },
) : AnswerService {
    val calls = mutableListOf<String>()

    override suspend fun ask(query: String, requester: NodeId): Answer {
        synchronized(calls) { calls += query }
        if (latencyMs > 0) delay(latencyMs)
        return authority.answer(AnswerKind.ASK, query, responder(query),
            listOf(AnswerSource("Example source", "https://example.com/")), clock(), 30 * 60_000L, "simulated")
    }

    override suspend fun read(url: String, requester: NodeId): Answer {
        synchronized(calls) { calls += "READ $url" }
        if (latencyMs > 0) delay(latencyMs)
        return authority.answer(AnswerKind.READ, url, "Readable text of $url (simulated).", listOf(AnswerSource(url, url)),
            clock(), 30 * 60_000L, "simulated")
    }

    fun demoBulletin(seq: Long): Bulletin {
        val now = clock()
        return authority.bulletin("official", "Live info", seq, now, listOf(
            BulletinItem(BulletinKind.WEATHER, 0, "27°C, light rain from 16:00", "Carry a raincoat; paths near Gate B get slippery.",
                "Open-Meteo", now, now + 3 * 3_600_000L),
            BulletinItem(BulletinKind.ALERT, 1, "Heavy crowd at Gate B", "Use Gates A or C to exit.", "Simulated organizer feed",
                now, now + 3_600_000L),
        ))
    }
}
