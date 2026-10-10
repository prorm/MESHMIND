package dev.meshmind.core.light

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.math.PI
import kotlin.math.cos

enum class LightPreset(val title: String, val description: String) {
    RAINBOW("Rainbow", "A smooth cycle through the spectrum"),
    PULSE("Ocean pulse", "A gentle teal glow that rises and falls"),
    SUNSET("Sunset", "Warm amber blends into pink and indigo");

    fun colorAt(positionMs: Long, periodMs: Long): Int {
        val phase = Math.floorMod(positionMs, periodMs).toDouble() / periodMs
        if (this == PULSE) {
            val brightness = .18 + .82 * (1 - cos(phase * 2 * PI)) / 2
            return rgb((35 * brightness).toInt(), (220 * brightness).toInt(), (195 * brightness).toInt())
        }
        val colors = if (this == RAINBOW) intArrayOf(0xFF7043, 0xFFC857, 0x72D572, 0x35DCC3, 0x5599FF, 0xB477EF, 0xEF77B4)
            else intArrayOf(0xFFC857, 0xF78465, 0xDF70B0, 0x7377CD, 0xDF70B0, 0xF78465)
        val scaled = phase * colors.size
        val a = colors[scaled.toInt() % colors.size]
        val b = colors[(scaled.toInt() + 1) % colors.size]
        val fraction = scaled - scaled.toInt()
        fun channel(shift: Int) = (((a shr shift) and 255) * (1 - fraction) + ((b shr shift) and 255) * fraction).toInt()
        return rgb(channel(16), channel(8), channel(0))
    }

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}

data class LightOffer(val showId: String, val hostId: String, val preset: LightPreset,
    val startAt: Long, val durationMs: Long, val periodMs: Long) {
    init {
        require(showId.length in 1..64 && hostId.length in 1..64)
        require(startAt >= 0 && durationMs in 10_000..300_000 && periodMs in 2_000..30_000)
    }
}

sealed class LightMessage {
    data class Offer(val show: LightOffer) : LightMessage()
    data class Stop(val showId: String) : LightMessage()
    data object Discover : LightMessage()
    data class Ping(val showId: String, val token: String, val t1: Long) : LightMessage()
    data class Pong(val showId: String, val token: String, val t1: Long, val t2: Long, val t3: Long) : LightMessage()
    data class Ready(val showId: String, val seat: LightSeat = LightSeat(), val roundTripMs: Long = 0, val revision: Long = 0) : LightMessage()
    data class Program(val program: LightProgram) : LightMessage()
    data class Seat(val showId: String, val seat: LightSeat) : LightMessage()
    data class Depart(val showId: String) : LightMessage()
}

object LightCodec {
    fun encode(message: LightMessage): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeByte(2)
            with(out) {
                when (message) {
                    is LightMessage.Offer -> { writeByte(1); with(message.show) {
                        writeUTF(showId); writeUTF(hostId); writeByte(preset.ordinal)
                        writeLong(startAt); writeLong(durationMs); writeLong(periodMs)
                    } }
                    is LightMessage.Stop -> { writeByte(2); writeUTF(message.showId) }
                    LightMessage.Discover -> writeByte(3)
                    is LightMessage.Ping -> { writeByte(4); writeUTF(message.showId); writeUTF(message.token); writeLong(message.t1) }
                    is LightMessage.Pong -> { writeByte(5); writeUTF(message.showId); writeUTF(message.token)
                        writeLong(message.t1); writeLong(message.t2); writeLong(message.t3) }
                    is LightMessage.Ready -> { writeByte(6); writeUTF(message.showId); writeSeat(message.seat); writeLong(message.roundTripMs); writeLong(message.revision) }
                    is LightMessage.Program -> { writeByte(7); with(message.program) {
                        writeUTF(showId); writeLong(revision); writeLong(effectiveAt); writeByte(cues.size)
                        cues.forEach { cue -> writeInt(cue.atMs); with(cue.scene) {
                            writeByte(effect.ordinal); writeInt(primary); writeInt(secondary); writeShort(bpm)
                            writeShort(spreadMs); writeByte(brightness); writeByte(groups); writeByte(direction)
                        } }
                    } }
                    is LightMessage.Seat -> { writeByte(8); writeUTF(message.showId); writeSeat(message.seat) }
                    is LightMessage.Depart -> { writeByte(9); writeUTF(message.showId) }
                }
            }
        }
        return bytes.toByteArray().also { require(it.size <= 512) }
    }

    fun decode(bytes: ByteArray): LightMessage {
        require(bytes.size <= 512)
        return DataInputStream(ByteArrayInputStream(bytes)).use {
            with(it) {
                require(readUnsignedByte() == 2) { "Unsupported light-show protocol; update every phone" }
                fun id() = readUTF().also { s -> require(s.length in 1..64) }
                val message = when (readUnsignedByte()) {
                    1 -> LightMessage.Offer(LightOffer(id(), id(), LightPreset.entries[readUnsignedByte()], readLong(), readLong(), readLong()))
                    2 -> LightMessage.Stop(id())
                    3 -> LightMessage.Discover
                    4 -> LightMessage.Ping(id(), id(), readLong())
                    5 -> LightMessage.Pong(id(), id(), readLong(), readLong(), readLong())
                    6 -> LightMessage.Ready(id(), readSeat(), readLong().also { n -> require(n in 0..3000) }, readLong().also { n -> require(n in 0..1_000_000) })
                    7 -> {
                        val show = id(); val revision = readLong(); val effective = readLong()
                        val count = readUnsignedByte().also { n -> require(n in 1..12) }
                        LightMessage.Program(LightProgram(show, revision, effective, List(count) {
                            LightCue(readInt(), LightScene(LightEffect.entries[readUnsignedByte()], readInt(), readInt(),
                                readUnsignedShort(), readUnsignedShort(), readUnsignedByte(), readUnsignedByte(), readByte().toInt()))
                        }))
                    }
                    8 -> LightMessage.Seat(id(), readSeat())
                    9 -> LightMessage.Depart(id())
                    else -> error("Unknown light-show control")
                }
                require(available() == 0) { "Trailing light-show bytes" }
                message
            }
        }
    }

    private fun DataOutputStream.writeSeat(seat: LightSeat) { writeByte(seat.group); writeShort(seat.x); writeShort(seat.y) }
    private fun DataInputStream.readSeat() = LightSeat(readUnsignedByte(), readUnsignedShort(), readUnsignedShort())
}

data class ClockSample(val offsetMs: Long, val roundTripMs: Long, val measuredAt: Long) {
    companion object {
        /** Four timestamps, all monotonic: local send, host receive/send, local receive. */
        fun estimate(t1: Long, t2: Long, t3: Long, t4: Long): ClockSample? {
            if (t1 < 0 || t2 < 0 || t3 < t2 || t4 < t1) return null
            val rtt = (t4 - t1) - (t3 - t2)
            if (rtt !in 0..3_000) return null
            return ClockSample(((t2 - t1) + (t3 - t4)) / 2, rtt, t4)
        }
    }
}
