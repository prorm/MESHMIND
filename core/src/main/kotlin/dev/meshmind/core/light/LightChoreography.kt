package dev.meshmind.core.light

import kotlin.math.*

enum class LightEffect(val title: String) {
    SOLID("Color wash"), BREATH("Breathing glow"), WAVE("Traveling wave"), RIPPLE("Center ripple"),
    SPIRAL("Spiral vortex"), CHECKER("Checker dance"), CONSTELLATION("Constellation"), RAINBOW("Rainbow sweep")
}

data class LightSeat(val group: Int = 0, val x: Int = 500, val y: Int = 500) {
    init { require(group in 0..3 && x in 0..1000 && y in 0..1000) }
}

data class LightScene(val effect: LightEffect = LightEffect.WAVE, val primary: Int = 0x35DCC3,
    val secondary: Int = 0x18224C, val bpm: Int = 100, val spreadMs: Int = 2400,
    val brightness: Int = 85, val groups: Int = 15, val direction: Int = 1) {
    init {
        require(primary in 0..0xFFFFFF && secondary in 0..0xFFFFFF)
        require(bpm in 30..180 && spreadMs in 0..8000 && brightness in 0..100 && groups in 1..15)
        require(direction == 1 || direction == -1)
    }
    fun colorAt(positionMs: Long, seat: LightSeat, nodeId: String): Int {
        if (groups and (1 shl seat.group) == 0) return 0xFF000000.toInt()
        val x = seat.x / 1000.0; val y = seat.y / 1000.0
        val radius = hypot(x - .5, y - .5) / sqrt(.5)
        // A visual cycle spans four musical beats: smooth effects, rather than high-frequency flashing.
        val period = 240_000.0 / bpm
        val phase = positionMs / period * direction
        val groupPhase = seat.group / 4.0
        val mix = when (effect) {
            LightEffect.SOLID -> 1.0
            LightEffect.BREATH -> .12 + .88 * (.5 - .5 * cos(phase * 2 * PI))
            LightEffect.WAVE -> .5 + .5 * sin(2 * PI * (phase - x * spreadMs / period - groupPhase))
            LightEffect.RIPPLE -> (.5 + .5 * cos(2 * PI * (phase - radius * spreadMs / period))).pow(3)
            LightEffect.SPIRAL -> .5 + .5 * sin(atan2(y - .5, x - .5) + radius * 4 * PI - phase * 2 * PI)
            LightEffect.CHECKER -> .5 + .5 * sin(phase * 2 * PI + ((floor(x * 7) + floor(y * 7) + seat.group).toInt() % 2) * PI)
            LightEffect.CONSTELLATION -> {
                val seed = (nodeId.hashCode().toLong() and 0xFFFFFFFFL) % 1000 / 1000.0
                .08 + .92 * (.5 + .5 * cos((phase + seed) * 2 * PI)).pow(8)
            }
            LightEffect.RAINBOW -> 0.0
        }
        val color = if (effect == LightEffect.RAINBOW)
            LightPreset.RAINBOW.colorAt((positionMs * direction - x * spreadMs - groupPhase * period).toLong(), period.toLong())
            else blend(secondary, primary, mix)
        fun dim(shift: Int) = (((color shr shift) and 255) * brightness / 100.0).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (dim(16) shl 16) or (dim(8) shl 8) or dim(0)
    }
    companion object {
        fun blend(a: Int, b: Int, fraction: Double): Int {
            val f = fraction.coerceIn(0.0, 1.0)
            fun channel(s: Int) = ((((a shr s) and 255) * (1 - f)) + (((b shr s) and 255) * f)).roundToInt()
            return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
        }
    }
}

data class LightCue(val atMs: Int, val scene: LightScene) { init { require(atMs in 0..299_999) } }

data class LightProgram(val showId: String, val revision: Long, val effectiveAt: Long, val cues: List<LightCue>) {
    init {
        require(showId.length in 1..64 && revision in 1..1_000_000 && effectiveAt in 0..Long.MAX_VALUE / 4)
        require(cues.size in 1..12 && cues.first().atMs == 0)
        require(cues.zipWithNext().all { (a, b) -> a.atMs < b.atMs })
    }
    fun sceneAt(positionMs: Long) = cues.lastOrNull { it.atMs <= positionMs }?.scene ?: cues.first().scene
    fun colorAt(positionMs: Long, seat: LightSeat, nodeId: String): Int {
        val index = cues.indexOfLast { it.atMs <= positionMs }.coerceAtLeast(0)
        val cue = cues[index]
        val color = cue.scene.colorAt(positionMs, seat, nodeId)
        if (index == 0) return color
        val blend = ((positionMs - cue.atMs) / 1000.0).coerceIn(0.0, 1.0)
        return LightScene.blend(cues[index - 1].scene.colorAt(positionMs, seat, nodeId), color, blend)
    }
}

data class LightParticipant(val nodeId: String, val seat: LightSeat, val lastSeen: Long,
    val roundTripMs: Long, val revision: Long)

object LightSequences {
    fun finale(durationMs: Long, bpm: Int, startPositionMs: Long = 0): List<LightCue> {
        val effects = listOf(LightEffect.WAVE, LightEffect.SPIRAL, LightEffect.CONSTELLATION, LightEffect.RIPPLE, LightEffect.RAINBOW)
        return effects.mapIndexed { i, effect -> LightCue(if (i == 0) 0 else (startPositionMs + (durationMs - startPositionMs) * i / effects.size).toInt(),
            LightScene(effect = effect, bpm = bpm, brightness = 65 + i * 7, primary = if (i % 2 == 0) 0x35DCC3 else 0xFF8066,
                secondary = 0x101635, spreadMs = 4000)) }
    }
}
