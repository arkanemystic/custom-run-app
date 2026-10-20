package dev.pacer.core

import dev.pacer.core.LocationFilter.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class LocationFilterTest {
    private fun LocationFilter.feed(fixes: List<Fix>) = fixes.map { offer(it) }

    @Test fun waitsForAccuracyToSettle() {
        val f = LocationFilter()
        val r = TestRoute()
        val d = f.feed((1..4).map { r.fix(it * 1000L) })
        assertTrue(d[0] is Decision.Rejected && d[1] is Decision.Rejected)
        assertEquals(Decision.Accepted(0.0, segmentStart = true), d[2])
        assertTrue(d[3] is Decision.Accepted)
    }

    @Test fun rejectsPoorAccuracyAndRestartsSettling() {
        val f = LocationFilter()
        val r = TestRoute()
        f.offer(r.fix(1000)); f.offer(r.fix(2000))
        assertTrue(f.offer(r.fix(3000, accuracy = 25f)) is Decision.Rejected)
        assertTrue(f.offer(r.fix(4000)) is Decision.Rejected) // streak restarted
    }

    @Test fun rejectsImplausibleJump() {
        val f = LocationFilter()
        val r = TestRoute()
        repeat(3) { f.offer(r.fix(it * 1000L)) }
        r.step(3.0)
        assertTrue(f.offer(r.fix(3000)) is Decision.Accepted)
        r.step(50.0) // 50 m in 1 s
        assertTrue(f.offer(r.fix(4000)) is Decision.Rejected)
        r.step(-47.0) // back near the true path
        val back = f.offer(r.fix(5000)) as Decision.Accepted
        // The 3 m step was inside the 5 m noise floor, so the anchor held and both steps count now.
        assertEquals(6.0, back.addedMeters, 0.1)
    }

    @Test fun reanchorsAfterRepeatedJumps() {
        val f = LocationFilter()
        val r = TestRoute()
        repeat(3) { f.offer(r.fix(it * 1000L)) }
        r.step(500.0) // the earlier fixes were the bad ones
        val d = (3..7).map { f.offer(r.fix(it * 1000L)) }
        assertTrue(d.take(4).all { it is Decision.Rejected })
        assertEquals(Decision.Accepted(0.0, segmentStart = false), d[4])
        r.step(6.0)
        assertEquals(6.0, (f.offer(r.fix(8000)) as Decision.Accepted).addedMeters, 0.1)
    }

    @Test fun standingStillWithJitterAddsAlmostNoDistance() {
        val f = LocationFilter()
        val rnd = Random(1)
        val base = TestRoute()
        var total = 0.0
        for (t in 0 until 300) {
            val (la, lo) = offset(base.lat, base.lon, rnd.nextDouble(0.0, 4.0), rnd.nextDouble(0.0, 360.0))
            val d = f.offer(Fix(t * 1000L, 0, la, lo, 8f))
            if (d is Decision.Accepted) total += d.addedMeters
        }
        assertTrue("jitter added $total m", total < 15.0)
    }

    @Test fun noisyRunDistanceIsAccurate() {
        val f = LocationFilter()
        val rnd = Random(7)
        val truth = TestRoute()
        var total = 0.0
        // 10 min at 3 m/s with ~2 m position noise and occasional bad fixes.
        for (t in 0 until 600) {
            truth.step(3.0)
            val (la, lo) = offset(truth.lat, truth.lon, rnd.nextDouble(0.0, 2.0), rnd.nextDouble(0.0, 360.0))
            val acc = if (t % 37 == 0) 35f else 6f
            val d = f.offer(Fix(t * 1000L, 0, la, lo, acc))
            if (d is Decision.Accepted) total += d.addedMeters
        }
        assertEquals(1800.0, total, 1800 * 0.03)
    }

    @Test fun newSegmentAfterResumeDoesNotBridgeTheGap() {
        val f = LocationFilter(settleFixes = 1)
        val r = TestRoute()
        f.offer(r.fix(0)); r.step(3.0); f.offer(r.fix(1000))
        f.startSegment()
        r.step(200.0)
        assertEquals(Decision.Accepted(0.0, segmentStart = true), f.offer(r.fix(60_000)))
    }

    @Test fun dopplerStandstillIgnoresLargeWander() {
        val f = LocationFilter()
        val base = TestRoute()
        var total = 0.0
        for (t in 0 until 120) {
            // Multipath drift of up to 15 m while the receiver knows it isn't moving.
            val (la, lo) = offset(base.lat, base.lon, (t % 15).toDouble(), 45.0)
            val d = f.offer(Fix(t * 1000L, 0, la, lo, 5f, 0.05f, 0.2f))
            if (d is Decision.Accepted) total += d.addedMeters
        }
        assertEquals(0.0, total, 0.001)
    }
}
