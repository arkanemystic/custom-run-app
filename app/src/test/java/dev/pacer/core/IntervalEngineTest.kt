package dev.pacer.core

import dev.pacer.core.IntervalEngine.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IntervalEngineTest {
    private val w = Workout.STARTER
    private val engine get() = IntervalEngine(w.intervals())

    @Test fun starterExpandsToEighteenIntervals() {
        val ivs = w.intervals()
        assertEquals(18, ivs.size)
        assertEquals(IntervalKind.WARMUP, ivs.first().kind)
        assertEquals(IntervalKind.COOLDOWN, ivs.last().kind)
        assertEquals(30 * 60_000L, IntervalEngine(ivs).totalMs)
    }

    @Test fun stateTracksPositionAndRemaining() {
        val s = engine.state(5 * 60_000L + 20_000)
        assertEquals(1, s.index)
        assertEquals(IntervalKind.RUN, s.interval!!.kind)
        assertEquals(40_000, s.remainingMs)
        assertEquals(IntervalKind.WALK, s.next!!.kind)
    }

    @Test fun emitsCountdownThenTransitionOnOneSecondTicks() {
        val e = engine
        val events = mutableListOf<Pair<Long, Event>>()
        var t = 0L
        while (t <= e.totalMs + 1000) { e.advance(t).forEach { events += t to it }; t += 1000 }
        val starts = events.filter { it.second is Event.Started }
        assertEquals(18, starts.size)
        starts.forEachIndexed { i, (time, _) ->
            assertEquals(w.intervals().take(i).sumOf { it.durationMs }, time)
        }
        // 3-2-1 before every transition, including the end of the workout.
        val countdowns = events.filter { it.second is Event.Countdown }
        assertEquals(18 * 3, countdowns.size)
        assertEquals(listOf(3, 2, 1), countdowns.take(3).map { (it.second as Event.Countdown).n })
        assertEquals(5 * 60_000L - 3000, countdowns.first().first)
        assertTrue(events.last().second is Event.Finished)
    }

    @Test fun lateTickReportsOnlyTheLatestState() {
        val e = engine
        e.advance(0)
        val ev = e.advance(5 * 60_000L + 61_000) // skipped warm-up end and run 1 entirely
        assertEquals(listOf(Event.Started(2, w.intervals()[2])), ev)
    }

    @Test fun pausingFreezesIntervals() {
        // The engine only sees moving time; a 2-minute pause is two minutes of identical input.
        val e = engine
        e.advance(0)
        repeat(120) { assertTrue(e.advance(100_000).isEmpty()) }
        assertEquals(200_000, e.state(100_000).remainingMs)
    }

    @Test fun resumeAtSuppressesRepeatAnnouncement() {
        val e = engine
        e.resumeAt(400_000)
        assertTrue(e.advance(401_000).isEmpty())
    }

    @Test fun emptyWorkoutNeverEmits() {
        val e = IntervalEngine(emptyList())
        assertTrue(e.advance(0).isEmpty())
        assertTrue(e.state(0).finished)
    }
}
