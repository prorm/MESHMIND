package dev.meshmind.core

import dev.meshmind.core.light.*
import kotlin.test.*

class LightChoreographyTest {
    @Test fun maximumScoreFitsControlBudgetAndRoundTripsWithPositionsAndDirection() {
        val program = LightProgram("a".repeat(64), 1_000_000, 300_000, (0..11).map { i ->
            LightCue(i * 1000, LightScene(effect = LightEffect.entries[i % LightEffect.entries.size], direction = -1))
        })
        val message = LightMessage.Program(program)
        val bytes = LightCodec.encode(message)
        assertTrue(bytes.size <= 512)
        assertEquals(message, LightCodec.decode(bytes))
        listOf(LightMessage.Seat("show", LightSeat(3, 0, 1000)), LightMessage.Depart("show"),
            LightMessage.Ready("show", LightSeat(2, 125, 550), 200, 15)).forEach { assertEquals(it, LightCodec.decode(LightCodec.encode(it))) }
    }

    @Test fun spatialEffectsActuallyDifferAcrossPhonePositions() {
        val left = LightSeat(0, 0, 0); val right = LightSeat(0, 1000, 600)
        listOf(LightEffect.WAVE, LightEffect.RIPPLE, LightEffect.SPIRAL, LightEffect.CHECKER, LightEffect.RAINBOW).forEach { effect ->
            val scene = LightScene(effect = effect, spreadMs = 1800)
            assertNotEquals(scene.colorAt(1234, left, "A"), scene.colorAt(1234, right, "A"), effect.name)
        }
        val stars = LightScene(effect = LightEffect.CONSTELLATION)
        assertNotEquals(stars.colorAt(1234, left, "Phone-A"), stars.colorAt(1234, left, "Phone-B"))
    }

    @Test fun timelineCrossfadesAndValidationPreventsAmbiguousOrOversizedScores() {
        val a = LightScene(effect = LightEffect.SOLID, primary = 0xFF0000, brightness = 100)
        val b = a.copy(primary = 0x0000FF)
        val score = LightProgram("show", 1, 10, listOf(LightCue(0, a), LightCue(4000, b)))
        assertEquals(0xFFFF0000.toInt(), score.colorAt(4000, LightSeat(), "A"))
        assertEquals(0xFF800080.toInt(), score.colorAt(4500, LightSeat(), "A"))
        assertEquals(0xFF0000FF.toInt(), score.colorAt(5000, LightSeat(), "A"))
        assertFails { score.copy(cues = listOf(LightCue(10, a))) }
        assertFails { score.copy(cues = listOf(LightCue(0, a), LightCue(0, b))) }
        assertFails { LightSeat(4, 500, 500) }
        assertFails { a.copy(bpm = 0) }
        assertFails { a.copy(groups = 0) }
        assertFails { a.copy(direction = 0) }
    }
}
