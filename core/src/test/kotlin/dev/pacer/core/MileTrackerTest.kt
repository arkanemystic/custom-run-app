package dev.pacer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MileTrackerTest {
    /** Runs [miles] at [pace] s/mi from the tracker's current state, one sample per second. */
    private fun MileTracker.run(fromMs: Long, fromM: Double, miles: Double, pace: Double): Pair<Long, Double> {
        val v = paceToSpeed(pace)
        val secs = (miles * pace).toLong()
        var t = fromMs; var d = fromM
        repeat(secs.toInt()) { t += 1000; d += v; add(t, d) }
        return t to d
    }

    @Test fun beforeFirstMileReportsPaceSinceStart() {
        val m = MileTracker()
        val (t, _) = m.run(0, 0.0, 0.5, 540.0)
        val lm = m.lastMile(t)
        assertTrue(lm.isSinceStart)
        assertEquals(540.0, lm.secPerMile!!, 1.0)
        assertTrue(m.splitsMs.isEmpty())
    }

    @Test fun splitIsInterpolatedAtTheMileBoundary() {
        val m = MileTracker()
        m.add(0, 0.0)
        m.add(600_000, METERS_PER_MILE - 10)
        m.add(610_000, METERS_PER_MILE + 10) // boundary crossed halfway through this sample
        assertEquals(listOf(605_000L), m.splitsMs)
    }

    @Test fun rollingWindowCoversExactlyTheLastMile() {
        val m = MileTracker()
        var (t, d) = m.run(0, 0.0, 1.0, 600.0)
        val r = m.run(t, d, 0.5, 480.0); t = r.first; d = r.second
        val lm = m.lastMile(t)
        assertFalse(lm.isSinceStart)
        // Last mile = 0.5 mi @ 10:00 + 0.5 mi @ 8:00.
        assertEquals(540.0, lm.secPerMile!!, 2.0)
        assertEquals(600_000.0, m.splitsMs[0].toDouble(), 1500.0)
    }

    @Test fun multipleSplitsAndPausesUseMovingTime() {
        val m = MileTracker()
        // Pausing doesn't advance moving time, so a pause is invisible to the tracker.
        var (t, d) = m.run(0, 0.0, 1.0, 600.0)
        val r = m.run(t, d, 1.0, 570.0); t = r.first; d = r.second
        assertEquals(2, m.splitsMs.size)
        assertEquals(570_000.0, m.splitsMs[1].toDouble(), 1500.0)
    }

    @Test fun standingStillUnpausedSlowsTheRollingPace() {
        val m = MileTracker()
        val (t, _) = m.run(0, 0.0, 1.2, 600.0)
        val before = m.lastMile(t).secPerMile!!
        assertEquals(before + 60, m.lastMile(t + 60_000).secPerMile!!, 0.01)
    }
}
