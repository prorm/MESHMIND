package dev.meshmind.core.model

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID

/** Everything that crosses a single link. */
sealed class Frame {
    /**
     * First frame on every new connection: exchanges the persistent NodeID. Signed with the node's key over
     * (nodeId, timestamp, isGateway) and carries its certificate, so a neighbour cannot claim someone else's ID.
     */
    class Hello(
        val nodeId: NodeId,
        val protocolVersion: Int,
        val isGateway: Boolean,
        val timestamp: Long = 0,
        val cert: ByteArray? = null,
        val signature: ByteArray? = null,
    ) : Frame() {
        fun signedBytes(): ByteArray = dev.meshmind.core.security.Canon("meshmind-hello-v1").str(nodeId).num(timestamp).flag(isGateway).build()
    }
    data class Data(val packet: Packet) : Frame()
    /**
     * Hop-by-hop receipt for bulk (CHUNK) frames. Nearby reports a payload SUCCESS long before the peer has it
     * (measured 1.3-2.9 s on phones), so the sender uses these to cap how much bulk sits inside Nearby's buffers.
     */
    data class LinkAck(val packetIds: List<String>) : Frame()
    /**
     * Opaque link-local payload for an optional [dev.meshmind.core.routing.LinkExtension] (e.g. SAGE's contact
     * summary). Never forwarded. Older builds reject it as an unknown frame and carry on.
     */
    class Extension(val payload: ByteArray) : Frame()
}

class WireFormatException(msg: String) : Exception(msg)

/**
 * Compact hand-rolled binary format (no reflection, no extra deps). Packet/transfer IDs are UUIDs and go on
 * the wire as 16 bytes so a SUMMARY of ~1000 IDs still fits inside one Nearby BYTES payload (32 KB max).
 */
object WireCodec {
    private const val MAGIC = 0x4D4D // "MM"
    /** v2: signed packets and HELLO, ASK/READ, bulletins, gateway load in beacons. v1 phones are rejected. */
    const val PROTOCOL_VERSION = 2
    private const val FRAME_HELLO = 1
    private const val FRAME_DATA = 2
    private const val FRAME_LINK_ACK = 3
    private const val FRAME_EXTENSION = 4

    fun encode(frame: Frame): ByteArray = write {
        writeShort(MAGIC)
        writeByte(PROTOCOL_VERSION)
        when (frame) {
            is Frame.Hello -> {
                writeByte(FRAME_HELLO)
                writeUTF(frame.nodeId)
                writeInt(frame.protocolVersion)
                writeBoolean(frame.isGateway)
                writeLong(frame.timestamp)
                writeOptBytes(frame.cert)
                writeOptBytes(frame.signature)
            }
            is Frame.Data -> {
                writeByte(FRAME_DATA)
                writePacket(frame.packet)
            }
            is Frame.LinkAck -> {
                writeByte(FRAME_LINK_ACK)
                writeUuids(frame.packetIds)
            }
            is Frame.Extension -> {
                writeByte(FRAME_EXTENSION)
                writeBytes(frame.payload)
            }
        }
    }

    fun decode(bytes: ByteArray): Frame = read(bytes) {
        if (readShort().toInt() and 0xFFFF != MAGIC) throw WireFormatException("bad magic")
        val version = readByte().toInt()
        if (version != PROTOCOL_VERSION) throw WireFormatException("unsupported protocol $version")
        when (val kind = readByte().toInt()) {
            FRAME_HELLO -> Frame.Hello(readUTF(), readInt(), readBoolean(), readLong(), readOptBytes(), readOptBytes())
            FRAME_DATA -> Frame.Data(readPacket())
            FRAME_LINK_ACK -> Frame.LinkAck(readUuids())
            FRAME_EXTENSION -> Frame.Extension(readBytes())
            else -> throw WireFormatException("unknown frame $kind")
        }
    }

    private fun DataOutputStream.writePacket(p: Packet) {
        writeUuid(p.packetId)
        writeUTF(p.sourceId)
        writeUTF(p.destinationId)
        writeByte(p.type.ordinal)
        writeByte(p.priority.ordinal)
        writeLong(p.createdAt)
        writeLong(p.expiresAt)
        writeByte(p.ttl)
        writeByte(p.hopCount)
        writeBytes(p.payload)
        writeStrings(p.routeHistory)
        writeUTF(p.label)
        writeOptBytes(p.signature)
        writeOptBytes(p.cert)
    }

    private fun DataInputStream.readPacket(): Packet = Packet(
        packetId = readUuid(),
        sourceId = readUTF(),
        destinationId = readUTF(),
        type = PacketType.entries[readByte().toInt()],
        priority = Priority.entries[readByte().toInt()],
        createdAt = readLong(),
        expiresAt = readLong(),
        ttl = readByte().toInt(),
        hopCount = readByte().toInt(),
        payload = readBytes(),
        routeHistory = readStrings(),
        label = readUTF(),
        signature = readOptBytes(),
        cert = readOptBytes(),
    )

    // ---- typed payloads -------------------------------------------------------------------

    fun encodeAck(a: AckPayload) = write {
        writeUuid(a.ackedPacketId); writeByte(a.ackedType.ordinal); writeLong(a.deliveredAt)
        writeByte(a.hopCount); writeStrings(a.route); writeBoolean(a.broadcastAck)
    }

    fun decodeAck(b: ByteArray) = read(b) {
        AckPayload(readUuid(), PacketType.entries[readByte().toInt()], readLong(), readByte().toInt(), readStrings(), readBoolean())
    }

    fun encodeBeacon(b: BeaconPayload) = write {
        writeUTF(b.gatewayId); writeLong(b.seq); writeUTF(b.note); writeShort(b.queueLength); writeShort(b.queueCapacity)
    }
    fun decodeBeacon(b: ByteArray) = read(b) { BeaconPayload(readUTF(), readLong(), readUTF(), readShort().toInt(), readShort().toInt()) }

    fun encodeInetRequest(r: InetRequest) = write { writeByte(r.kind.ordinal); writeUTF(r.url); writeUTF(r.body); writeByte(r.battery) }
    fun decodeInetRequest(b: ByteArray) = read(b) { InetRequest(InetKind.entries[readByte().toInt()], readUTF(), readUTF(), readByte().toInt()) }

    fun encodeInetResponse(r: InetResponse) = write {
        writeUuid(r.requestId); writeByte(r.status.ordinal); writeInt(r.httpCode); writeUTF(r.contentType)
        writeBytes(r.body); writeBoolean(r.truncated); writeUTF(r.message); writeUTF(r.gatewayId)
        writeStrings(r.requestRoute); writeLong(r.executedAt)
        writeUTF(r.servedFrom); writeLong(r.queueWaitMs); writeByte(r.need); writeUTF(r.needCategory); writeStrings(r.needReasons)
    }

    fun decodeInetResponse(b: ByteArray) = read(b) {
        InetResponse(
            readUuid(), InetStatus.entries[readByte().toInt()], readInt(), readUTF(), readBytes(), readBoolean(),
            readUTF(), readUTF(), readStrings(), readLong(),
            servedFrom = readUTF(), queueWaitMs = readLong(), need = readByte().toInt(), needCategory = readUTF(), needReasons = readStrings(),
        )
    }

    fun encodeAnswer(a: Answer) = write {
        writeByte(a.kind.ordinal); writeText(a.query); writeText(a.text)
        writeShort(a.sources.size); a.sources.forEach { writeText(it.title); writeText(it.url) }
        writeLong(a.createdAt); writeLong(a.expiresAt); writeUTF(a.provider); writeUTF(a.keyId); writeBytes(a.signature)
    }

    fun decodeAnswer(b: ByteArray) = read(b) {
        val kind = AnswerKind.entries[readByte().toInt()]
        val query = readText(); val text = readText()
        val sources = List(readShort().toInt()) { AnswerSource(readText(), readText()) }
        Answer(kind, query, text, sources, readLong(), readLong(), readUTF(), readUTF(), readBytes())
    }

    fun encodeBulletin(x: Bulletin) = write {
        writeText(x.boardId); writeText(x.title); writeLong(x.seq); writeLong(x.issuedAt)
        writeShort(x.items.size)
        x.items.forEach {
            writeByte(it.kind.ordinal); writeByte(it.severity); writeText(it.title); writeText(it.body); writeText(it.source)
            writeLong(it.issuedAt); writeLong(it.expiresAt)
        }
        writeUTF(x.keyId); writeBytes(x.signature)
    }

    fun decodeBulletin(b: ByteArray) = read(b) {
        val board = readText(); val title = readText(); val seq = readLong(); val issued = readLong()
        val items = List(readShort().toInt()) {
            BulletinItem(BulletinKind.entries[readByte().toInt()], readByte().toInt(), readText(), readText(), readText(), readLong(), readLong())
        }
        Bulletin(board, title, seq, issued, items, readUTF(), readBytes())
    }

    fun encodeSummary(s: SummaryPayload) = write { writeUuids(s.held); writeUuids(s.acked); writeUuids(s.want) }
    fun decodeSummary(b: ByteArray) = read(b) { SummaryPayload(readUuids(), readUuids(), readUuids()) }

    fun encodeChunk(c: ChunkPayload) = write {
        writeUuid(c.transferId); writeInt(c.index); writeInt(c.total); writeInt(c.totalBytes); writeUTF(c.name); writeBytes(c.data)
    }

    fun decodeChunk(b: ByteArray) = read(b) { ChunkPayload(readUuid(), readInt(), readInt(), readInt(), readUTF(), readBytes()) }

    // ---- primitives -----------------------------------------------------------------------

    private inline fun write(block: DataOutputStream.() -> Unit): ByteArray {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { it.block() }
        return bos.toByteArray()
    }

    private inline fun <T> read(bytes: ByteArray, block: DataInputStream.() -> T): T = try {
        DataInputStream(ByteArrayInputStream(bytes)).use { it.block() }
    } catch (e: WireFormatException) {
        throw e
    } catch (e: Exception) {
        throw WireFormatException("malformed: ${e.javaClass.simpleName} ${e.message}")
    }

    private fun DataOutputStream.writeUuid(id: String) {
        val u = UUID.fromString(id)
        writeLong(u.mostSignificantBits); writeLong(u.leastSignificantBits)
    }

    private fun DataInputStream.readUuid(): String = UUID(readLong(), readLong()).toString()

    private fun DataOutputStream.writeUuids(ids: List<String>) { writeInt(ids.size); ids.forEach { writeUuid(it) } }
    private fun DataInputStream.readUuids(): List<String> = List(checkedCount(readInt())) { readUuid() }

    private fun DataOutputStream.writeBytes(b: ByteArray) { writeInt(b.size); write(b) }
    private fun DataInputStream.readBytes(): ByteArray = ByteArray(checkedCount(readInt(), 1 shl 20)).also { readFully(it) }

    private fun DataOutputStream.writeOptBytes(b: ByteArray?) { if (b == null) writeShort(0xFFFF) else { require(b.size < 0xFFFF); writeShort(b.size); write(b) } }
    private fun DataInputStream.readOptBytes(): ByteArray? = readUnsignedShort().let { n -> if (n == 0xFFFF) null else ByteArray(n).also { readFully(it) } }

    /** UTF-8 text without writeUTF's 64 KB modified-UTF-8 limit (READ answers can be long). */
    private fun DataOutputStream.writeText(s: String) = writeBytes(s.toByteArray(Charsets.UTF_8))
    private fun DataInputStream.readText(): String = readBytes().toString(Charsets.UTF_8)

    private fun DataOutputStream.writeStrings(s: List<String>) { writeShort(s.size); s.forEach { writeUTF(it) } }
    private fun DataInputStream.readStrings(): List<String> = List(readShort().toInt()) { readUTF() }

    private fun checkedCount(n: Int, max: Int = 100_000): Int {
        if (n < 0 || n > max) throw WireFormatException("bad length $n")
        return n
    }
}
