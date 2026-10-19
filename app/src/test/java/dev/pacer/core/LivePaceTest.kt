package dev.pacer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

class LivePaceTest {
    @Test fun smoothsNoisyDopplerSpeed() {
        val lp = LivePace()
        val rnd = Random(3)
        val v = paceToSpeed(600.0)
        for (t in 0..60) lp.onFix(t * 1000L, v * t, (v + rnd.nextDouble(-0.4, 0.4)).toFloat(), 0.5f)
        assertEquals(600.0, lp.paceSecPerMile(60_000)!!, 600 * 0.04)
    }

    @Test fun fallsBackToDistanceOverTimeWithoutReliableSpeed() {
        val lp = LivePace()
        val v = paceToSpeed(480.0)
        for (t in 0..30) lp.onFix(t * 1000L, v * t, 9f, 5f) // speed accuracy too poor to use
        assertEquals(480.0, lp.paceSecPerMile(30_000)!!, 1.0)
    }

    @Test fun reactsToPaceChangeWithinAFewSeconds() {
        val lp = LivePace()
        var d = 0.0
        for (t in 0..30) { d += paceToSpeed(960.0); lp.onFix(t * 1000L, d, paceToSpeed(960.0).toFloat(), 0.3f) }
        for (t in 31..45) { d += paceToSpeed(600.0); lp.onFix(t * 1000L, d, paceToSpeed(600.0).toFloat(), 0.3f) }
        assertEquals(600.0, lp.paceSecPerMile(45_000)!!, 20.0)
    }

    @Test fun stationaryShowsNoPace() {
        val lp = LivePace()
        for (t in 0..20) lp.onFix(t * 1000L, 0.0, 0.1f, 0.2f)
        assertNull(lp.paceSecPerMile(20_000))
    }

    @Test fun staleWhenFixesStop() {
        val lp = LivePace()
        for (t in 0..20) lp.onFix(t * 1000L, 3.0 * t, 3f, 0.3f)
        assertEquals(paceToSpeed(3.0), lp.paceSecPerMile(25_000)!!, 1.0)
        assertNull(lp.paceSecPerMile(29_000))
    }

    @Test fun coachingAverageOnlyUsesTheRequestedWindow() {
        val lp = LivePace()
        var d = 0.0
        for (t in 0..30) { d += 1.68; lp.onFix(t * 1000L, d, 1.68f, 0.3f) }
        for (t in 31..50) { d += 2.68; lp.onFix(t * 1000L, d, 2.68f, 0.3f) }
        assertEquals(speedToPace(2.68), lp.averagePaceSecPerMile(36_000, 50_000)!!, 0.5)
        assertNull(lp.averagePaceSecPerMile(46_000, 50_000)) // too few samples yet
    }
}
