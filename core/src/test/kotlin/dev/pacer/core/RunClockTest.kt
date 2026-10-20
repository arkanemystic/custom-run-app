package dev.pacer.core

import org.junit.Assert.assertEquals
import org.junit.Test

class RunClockTest {
    @Test fun movingTimeExcludesPauses() {
        var c = RunClock(1_000)
        assertEquals(10_000, c.movingMs(11_000))
        c = c.pause(11_000)
        assertEquals(10_000, c.movingMs(50_000))
        c = c.resume(50_000)
        assertEquals(15_000, c.movingMs(55_000))
        assertEquals(54_000, c.elapsedMs(55_000))
    }

    @Test fun doublePauseOrResumeIsHarmless() {
        val c = RunClock(0).pause(5_000).pause(9_000).resume(10_000).resume(20_000)
        assertEquals(10_000, c.movingMs(15_000))
    }
}
