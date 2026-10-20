package dev.pacer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UnitsTest {
    @Test fun formatsPace() {
        assertEquals("10:00", formatPace(600.0))
        assertEquals("9:05", formatPace(544.6))
        assertEquals("--:--", formatPace(null))
        assertEquals("--:--", formatPace(4000.0))
    }

    @Test fun formatsDurations() {
        assertEquals("0:59", formatDuration(59_999))
        assertEquals("1:00:00", formatDuration(3_600_000))
        assertEquals("1:00", formatRemaining(59_001))
        assertEquals("0:00", formatRemaining(0))
    }

    @Test fun paceFromTimeAndDistance() {
        assertEquals(600.0, paceSecPerMile(600_000, METERS_PER_MILE)!!, 1e-9)
        assertNull(paceSecPerMile(10_000, 0.0))
    }
}
