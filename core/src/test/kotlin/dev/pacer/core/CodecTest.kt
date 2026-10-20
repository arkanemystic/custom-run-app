package dev.pacer.core

import org.junit.Assert.assertEquals
import org.junit.Test

class CodecTest {
    @Test fun planRoundTrips() {
        val w = Workout.STARTER.copy(id = 7, name = "Hills | with pipes", toleranceSec = 20, walkToleranceSec = 45)
        assertEquals(Plan.Intervals(w), Codec.decodePlan(Codec.encodePlan(Plan.Intervals(w))))
        assertEquals(Plan.Free(null, 15), Codec.decodePlan(Codec.encodePlan(Plan.Free(null))))
        assertEquals(Plan.Free(540, 10), Codec.decodePlan(Codec.encodePlan(Plan.Free(540, 10))))
    }

    @Test fun splitsAndIntervalsRoundTrip() {
        assertEquals(listOf(600_000L, 590_500L), Codec.decodeSplits(Codec.encodeSplits(listOf(600_000L, 590_500L))))
        assertEquals(emptyList<Long>(), Codec.decodeSplits(""))
        val r = listOf(
            RunSession.IntervalResult(Workout.STARTER.intervals()[0], 300_000, 450.5),
            RunSession.IntervalResult(Workout.STARTER.intervals()[1], 60_000, 160.9),
        )
        assertEquals(r, Codec.decodeIntervals(Codec.encodeIntervals(r)))
    }

    @Test fun walkIntervalsUseTheWiderTolerance() {
        val p = Plan.Intervals(Workout.STARTER)
        assertEquals(15, p.toleranceFor(Workout.STARTER.intervals()[1]))
        assertEquals(30, p.toleranceFor(Workout.STARTER.intervals()[2]))
    }
}
