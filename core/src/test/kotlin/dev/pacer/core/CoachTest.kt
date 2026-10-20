package dev.pacer.core

import dev.pacer.core.Coach.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CoachTest {
    private fun Coach.at(moving: Long, inInterval: Long, pace: Double?, remaining: Long? = 60_000, target: Int? = 600) =
        evaluate(moving, inInterval, remaining, target, 15, pace)

    @Test fun quietInBand() {
        val c = Coach()
        assertNull(c.at(100_000, 30_000, 612.0))
        assertNull(c.at(100_000, 30_000, 585.0))
    }

    @Test fun tooSlowSaysSpeedUp() {
        assertEquals(Verdict.SPEED_UP, Coach().at(100_000, 30_000, 640.0)!!.verdict)
    }

    @Test fun tooFastSaysSlowDown() {
        assertEquals(Verdict.SLOW_DOWN, Coach().at(100_000, 30_000, 560.0)!!.verdict)
    }

    @Test fun quietDuringGracePeriod() {
        val c = Coach()
        assertNull(c.at(100_000, 14_999, 700.0))
        assertEquals(Verdict.SPEED_UP, c.at(100_001, 15_000, 700.0)!!.verdict)
    }

    @Test fun respectsCueSpacing() {
        val c = Coach(minSpacingMs = 20_000)
        c.at(100_000, 30_000, 700.0)!!
        assertNull(c.at(119_999, 49_999, 700.0, remaining = 200_000))
        assertEquals(Verdict.SPEED_UP, c.at(120_000, 50_000, 700.0, remaining = 200_000)!!.verdict)
        c.minSpacingMs = 60_000
        assertNull(c.at(170_000, 100_000, 700.0, remaining = 200_000))
    }

    @Test fun quietInLastTenSeconds() {
        assertNull(Coach().at(100_000, 50_000, 700.0, remaining = 10_000))
    }

    @Test fun noTargetOrNoPaceMeansNoCue() {
        assertNull(Coach().at(100_000, 30_000, 700.0, target = null))
        assertNull(Coach().at(100_000, 30_000, null))
    }

    @Test fun inBandDoesNotConsumeSpacing() {
        val c = Coach()
        assertNull(c.at(100_000, 30_000, 600.0))
        assertEquals(Verdict.SPEED_UP, c.at(101_000, 31_000, 700.0)!!.verdict)
    }
}
