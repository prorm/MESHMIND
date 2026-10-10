package dev.meshmind.core

import dev.meshmind.core.model.AckPayload
import dev.meshmind.core.model.ChunkPayload
import dev.meshmind.core.model.Frame
import dev.meshmind.core.model.InetResponse
import dev.meshmind.core.model.InetStatus
import dev.meshmind.core.model.Packet
import dev.meshmind.core.model.PacketType
import dev.meshmind.core.model.Priority
import dev.meshmind.core.model.SummaryPayload
import dev.meshmind.core.model.WireCodec
import dev.meshmind.core.model.WireFormatException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WireCodecTest {
    @Test
    fun packetRoundTrip() {
        val p = Packet(Packet.newId(), "A-1f2e", "C-99aa", PacketType.TEXT, Priority.HIGH, 1, 2, 7, 1,
            "héllo 🚨".toByteArray(), listOf("A-1f2e", "B-0000"), "URGENT")
        val back = (WireCodec.decode(WireCodec.encode(Frame.Data(p))) as Frame.Data).packet
        assertEquals(p, back)
        val hello = Frame.Hello("A-1f2e", 2, true, 1234, byteArrayOf(1, 2, 3), byteArrayOf(9, 8))
        val h2 = WireCodec.decode(WireCodec.encode(hello)) as Frame.Hello
        assertEquals(listOf<Any>(hello.nodeId, hello.protocolVersion, hello.isGateway, hello.timestamp), listOf<Any>(h2.nodeId, h2.protocolVersion, h2.isGateway, h2.timestamp))
        assertTrue(hello.cert.contentEquals(h2.cert) && hello.signature.contentEquals(h2.signature))
        val unsigned = WireCodec.decode(WireCodec.encode(Frame.Hello("B", 2, false))) as Frame.Hello
        assertEquals(null, unsigned.cert)
        val signed = p.copy(signature = ByteArray(71) { 7 }, cert = ByteArray(200) { 3 })
        assertEquals(signed, (WireCodec.decode(WireCodec.encode(Frame.Data(signed))) as Frame.Data).packet)
    }

    @Test
    fun payloadRoundTrips() {
        val ack = AckPayload(Packet.newId(), PacketType.SOS, 5, 3, listOf("A", "B", "C", "D"), false)
        assertEquals(ack, WireCodec.decodeAck(WireCodec.encodeAck(ack)))
        val c = ChunkPayload(Packet.newId(), 2, 10, 1000, "f.bin", ByteArray(16 * 1024) { it.toByte() })
        val c2 = WireCodec.decodeChunk(WireCodec.encodeChunk(c))
        assertTrue(c.data.contentEquals(c2.data)); assertEquals(c.index, c2.index)
        val r = InetResponse(Packet.newId(), InetStatus.OK, 200, "text/html", ByteArray(10), false, "ok", "D", listOf("A", "D"), 9)
        val r2 = WireCodec.decodeInetResponse(WireCodec.encodeInetResponse(r))
        assertEquals(r.requestRoute, r2.requestRoute); assertEquals(r.status, r2.status)
    }

    /** A full-size SUMMARY (500 held + 500 acked) and a full chunk both fit in one 32 KB Nearby BYTES payload. */
    @Test
    fun framesFitNearbyLimit() {
        val ids = List(500) { Packet.newId() }
        val summary = WireCodec.encodeSummary(SummaryPayload(ids, ids, emptyList()))
        val sp = Packet(Packet.newId(), "A-1234", "B-1234", PacketType.SUMMARY, Priority.HIGH, 0, 1, 0, 0, summary, listOf("A-1234"))
        assertTrue(WireCodec.encode(Frame.Data(sp)).size < 32 * 1024)
        val chunk = WireCodec.encodeChunk(ChunkPayload(Packet.newId(), 0, 1, 16384, "x", ByteArray(16 * 1024)))
        val cp = Packet(Packet.newId(), "A-1234", "B-1234", PacketType.CHUNK, Priority.LOW, 0, 1, 8, 0, chunk, List(8) { "N-000$it" })
        assertTrue(WireCodec.encode(Frame.Data(cp)).size < 32 * 1024)
    }

    @Test
    fun garbageIsRejected() {
        assertFailsWith<WireFormatException> { WireCodec.decode(byteArrayOf(1, 2, 3)) }
        assertFailsWith<WireFormatException> { WireCodec.decode(ByteArray(0)) }
    }
}
