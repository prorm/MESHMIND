package dev.meshmind.core.model

import dev.meshmind.core.security.Canon

enum class AnswerKind { ASK, READ }

data class AnswerSource(val title: String, val url: String)

/**
 * A compact answer produced by the MeshMind backend (search + AI for ASK, readable-text extraction for READ) and
 * signed by the authority key. The signature is what lets any phone cache an answer and hand it to strangers:
 * nobody along the way, gateway included, can alter it without the change being detected.
 */
class Answer(
    val kind: AnswerKind,
    val query: String,
    val text: String,
    val sources: List<AnswerSource>,
    val createdAt: Long,
    val expiresAt: Long,
    val provider: String,
    /** Fingerprint of the authority key that signed it. */
    val keyId: String,
    val signature: ByteArray,
) {
    fun signedBytes(): ByteArray = Canon("meshmind-answer-v1").str(kind.name).str(query).str(text).num(sources.size)
        .apply { sources.forEach { str(it.title).str(it.url) } }
        .num(createdAt).num(expiresAt).str(provider).str(keyId).build()

    fun isFresh(now: Long) = now < expiresAt
}

/** Append only: the ordinal is the wire value. */
enum class BulletinKind { ALERT, WEATHER, NOTICE, TRANSPORT, INFO }

data class BulletinItem(
    val kind: BulletinKind,
    /** 0 = info, 1 = advisory, 2 = warning, 3 = emergency. */
    val severity: Int,
    val title: String,
    val body: String,
    val source: String,
    val issuedAt: Long,
    val expiresAt: Long,
)

/**
 * A board of short items broadcast to every phone. Official boards are fetched by gateways from the backend and
 * signed by the authority ([keyId] = authority fingerprint). Organizer boards are posted from a phone whose
 * authority-issued certificate carries the ORGANIZER or RESPONDER role ([keyId] = "node:<nodeId>", signed with
 * that node's key). Anything else is ignored, so a stranger cannot broadcast fake alerts to the crowd.
 */
class Bulletin(
    val boardId: String,
    val title: String,
    /** Monotonic per board: a newer seq replaces the old board. */
    val seq: Long,
    val issuedAt: Long,
    val items: List<BulletinItem>,
    val keyId: String,
    val signature: ByteArray,
) {
    fun signedBytes(): ByteArray = canonical(boardId, title, seq, issuedAt, items, keyId)

    val nodeSigner: NodeId? get() = keyId.removePrefix(NODE_KEY_PREFIX).takeIf { keyId.startsWith(NODE_KEY_PREFIX) }

    companion object {
        const val NODE_KEY_PREFIX = "node:"

        fun canonical(boardId: String, title: String, seq: Long, issuedAt: Long, items: List<BulletinItem>, keyId: String): ByteArray =
            Canon("meshmind-bulletin-v1").str(boardId).str(title).num(seq).num(issuedAt).num(items.size)
                .apply { items.forEach { str(it.kind.name).num(it.severity).str(it.title).str(it.body).str(it.source).num(it.issuedAt).num(it.expiresAt) } }
                .str(keyId).build()
    }
}
