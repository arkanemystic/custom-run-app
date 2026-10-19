package dev.pacer.core

import dev.pacer.core.RunSession.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * End-to-end simulation of the service loop: 1 Hz fixes with jitter and 1 Hz ticks aligned to
 * moving-time seconds (as RunService schedules them), with a pause in the middle. This is the
 * screen-off path: nothing here depends on the UI.
 */
class RunSessionTest {
    private data class Timed(val realMs: Long, val movingMs: Long, val event: Event)

    private class Sim(val plan: Plan, val paceFor: (Interval?) -> Double, seed: Int = 11) {
        val start = 5_000_000L
        val session = RunSession(plan, start)
        val events = mutableListOf<Timed>()
        val points = mutableListOf<TrackPoint>()
        private val route = TestRoute()
        private val rnd = Random(seed)
        private var now = start
        private var nextFix = start
        private var nextTick = start

        fun runUntilMoving(targetMovingMs: Long) {
            while (session.clock.movingMs(now) < targetMovingMs) step()
        }

        fun pauseFor(ms: Long) {
            session.pause(now)
            session.drainPoints().let(points::addAll)
            val end = now + ms
            while (now < end) {
                now += 1000
                route.step(1.0) // walking around while paused must not count
                session.onFix(route.fix(now))
                assertTrue(session.tick(now).isEmpty())
            }
            session.resume(now)
            nextTick = now
            nextFix = now + 1000
        }

        private fun step() {
            now = minOf(nextFix, nextTick)
            if (now == nextFix) {
                val moving = session.clock.movingMs(now)
                val iv = session.engine.state(moving).interval
                val v = paceToSpeed(paceFor(iv))
                route.step(v * 1.0)
                // Doppler speed noise, std ~0.09 m/s (Pixel-class GNSS while moving).
                val noisy = v + rnd.nextDouble(-0.15, 0.15)
                session.onFix(route.fix(now, speed = noisy.toFloat(), speedAcc = 0.4f)).forEach {
                    events += Timed(now, moving, it)
                }
                nextFix += 1000 + rnd.nextLong(-30, 30)
            }
            if (now == nextTick) {
                val moving = session.clock.movingMs(now)
                session.tick(now).forEach { events += Timed(now, moving, it) }
                // Service: wake at the next whole second of moving time, plus scheduler jitter.
                nextTick = now + (1000 - moving % 1000) + rnd.nextLong(0, 40)
            }
            if (now % 30_000 < 1000) points += session.drainPoints()
        }
    }

    private val workout = Workout.STARTER
    private val onPace: (Interval?) -> Double = { it?.targetPaceSec?.toDouble() ?: 1080.0 }

    @Test fun everyTransitionAndCountdownFiresOnScheduleAcrossAPause() {
        val sim = Sim(Plan.Intervals(workout), onPace)
        sim.runUntilMoving(9 * 60_000L + 10_000) // into run 3
        sim.pauseFor(90_000)
        sim.runUntilMoving(workout.totalSec * 1000L + 2_000)

        val starts = sim.events.filter { it.event is Event.IntervalStarted }
        assertEquals(workout.intervals().size, starts.size)
        var nominal = 0L
        workout.intervals().forEachIndexed { i, iv ->
            val late = starts[i].movingMs - nominal
            assertTrue("interval $i fired ${late}ms late", late in 0..100)
            nominal += iv.durationMs
        }
        assertEquals(workout.intervals().size * 3, sim.events.count { it.event is Event.Countdown })
        assertEquals(1, sim.events.count { it.event is Event.WorkoutFinished })
        // Running on pace: no coaching during run intervals. (At a 16:00 walk, ±15 s/mi is ~1.5%
        // of speed, about the size of GPS speed noise, so an occasional walk cue is expected.)
        val runCues = sim.events.filter {
            it.event is Event.Coaching && sim.session.engine.state(it.movingMs).interval?.kind == IntervalKind.RUN
        }
        assertEquals(0, runCues.size)
    }

    @Test fun recordedTrackHasNoGapsExceptThePause() {
        val sim = Sim(Plan.Intervals(workout), onPace)
        sim.runUntilMoving(10 * 60_000L)
        sim.pauseFor(60_000)
        sim.runUntilMoving(20 * 60_000L)
        val pts = sim.points + sim.session.drainPoints()
        assertTrue(pts.size > 1150)
        assertEquals(2, pts.count { it.segmentStart })
        pts.zipWithNext().forEach { (a, b) ->
            if (!b.segmentStart) assertTrue("gap ${b.wallTimeMs - a.wallTimeMs}", b.wallTimeMs - a.wallTimeMs <= 1100)
        }
        // Distance walked during the pause is excluded.
        val expected = workout.intervals().fold(0.0 to 0L) { (d, t), iv ->
            val spent = (20 * 60_000L - t).coerceIn(0, iv.durationMs)
            (d + paceToSpeed(iv.targetPaceSec?.toDouble() ?: 1080.0) * spent / 1000) to (t + iv.durationMs)
        }.first
        assertEquals(expected, sim.session.distanceM, expected * 0.03)
    }

    @Test fun coachingCuesRespectGraceTailAndSpacing() {
        val sim = Sim(Plan.Intervals(workout), { if (it?.kind == IntervalKind.RUN) 700.0 else onPace(it) })
        sim.runUntilMoving(workout.totalSec * 1000L)
        val cues = sim.events.filter { it.event is Event.Coaching }
        cues.forEach {
            val s = sim.session.engine.state(it.movingMs)
            assertTrue(s.elapsedInIntervalMs >= 15_000)
            assertTrue(s.remainingMs > 10_000)
        }
        cues.zipWithNext().forEach { (a, b) -> assertTrue(b.movingMs - a.movingMs >= 20_000) }
        val runCues = cues.filter { sim.session.engine.state(it.movingMs).interval!!.kind == IntervalKind.RUN }
        assertTrue(runCues.all { (it.event as Event.Coaching).cue.verdict == Coach.Verdict.SPEED_UP })
        // Each 60 s run leaves room for at most two cues (15 s in and 20 s later); spacing from a
        // late walk cue can push the first one back, so at least one per run.
        assertTrue("run cues ${runCues.size}", runCues.size in 8..16)
    }

    @Test fun freeRunWithTargetCoachesAndSplitsMiles() {
        val sim = Sim(Plan.Free(targetPaceSec = 540), { 480.0 })
        sim.runUntilMoving(17 * 60_000L)
        val splits = sim.events.mapNotNull { it.event as? Event.MileSplit }
        assertEquals(2, splits.size)
        splits.forEach { assertEquals(480_000.0, it.splitMs.toDouble(), 6_000.0) }
        assertTrue(sim.events.any { (it.event as? Event.Coaching)?.cue?.verdict == Coach.Verdict.SLOW_DOWN })
        val snap = sim.session.snapshot(sim.start + 17 * 60_000L)
        assertEquals(RunSession.PaceStatus.TOO_FAST, snap.paceStatus)
        assertEquals(480.0, snap.lastMile.secPerMile!!, 8.0)
        assertEquals(480.0, snap.livePaceSec!!, 15.0)
    }

    @Test fun intervalResultsReportActualVsTarget() {
        val sim = Sim(Plan.Intervals(workout), onPace)
        sim.runUntilMoving(10 * 60_000L)
        val res = sim.session.intervalResults(sim.start + 10 * 60_000L)
        val run1 = res.first { it.interval.kind == IntervalKind.RUN }
        assertEquals(60_000, run1.movingMs)
        assertEquals(600.0, run1.paceSec!!, 15.0)
    }

    @Test fun restoreRebuildsTotalsWithoutRepeatingCues() {
        val sim = Sim(Plan.Intervals(workout), onPace)
        sim.runUntilMoving(12 * 60_000L)
        val now = sim.start + 12 * 60_000L
        val restored = RunSession.restore(sim.plan, sim.session.clock, sim.points + sim.session.drainPoints(), now, 20_000)
        assertEquals(sim.session.distanceM, restored.distanceM, 0.01)
        assertEquals(sim.session.splitsMs, restored.splitsMs)
        assertTrue(restored.tick(now + 200).none { it is Event.IntervalStarted })
    }
}
