package dev.pacer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HapticsTest {
    private fun pulses(p: HapticPattern) = p.timings.indices.count { it % 2 == 1 }

    @Test fun patternsAreWellFormed() {
        HapticPattern.entries.forEach {
            assertEquals(it.name, it.timings.size, it.amplitudes.size)
            assertEquals(it.name, 0, it.timings[0])
            assertTrue(it.name, it.timings.drop(1).all { t -> t in 40..800 })
        }
    }

    @Test fun everyPatternHasADistinctRhythm() {
        // Same pulse count and same longest pulse would feel alike; require one to differ.
        val sig = HapticPattern.entries.map { pulses(it) to it.timings.filterIndexed { i, _ -> i % 2 == 1 }.max() }
        assertEquals(sig.size, sig.toSet().size)
    }

    @Test fun speedUpAndSlowDownAreOpposites() {
        val up = HapticPattern.forCoaching(Coach.Verdict.SPEED_UP)
        val down = HapticPattern.forCoaching(Coach.Verdict.SLOW_DOWN)
        assertNotEquals(up, down)
        assertTrue(pulses(up) >= 2 * pulses(down))
        assertTrue(down.timings.max() >= 5 * up.timings.drop(1).max())
    }
}
