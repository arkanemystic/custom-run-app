package dev.pacer.core

import org.junit.Assert.assertEquals
import org.junit.Test

class RunDescriptionTest {
    @Test fun describesIntervalRun() {
        val ivs = Workout.STARTER.intervals()
        val text = RunDescription.of(
            Plan.Intervals(Workout.STARTER), 2 * METERS_PER_MILE, 1_200_000, listOf(600_000, 600_000),
            listOf(RunSession.IntervalResult(ivs[1], 60_000, METERS_PER_MILE / 10)),
        )
        assertEquals(
            """
            8 x 1:00 run @ 10:00/mi + 1:30 walk @ 16:00/mi, 5:00 warm-up, 5:00 cool-down
            2.00 mi in 20:00, avg 10:00/mi
            Splits: 10:00, 10:00
            Run intervals: 10:00
            Recorded with Pacer
            """.trimIndent(),
            text,
        )
    }
}
