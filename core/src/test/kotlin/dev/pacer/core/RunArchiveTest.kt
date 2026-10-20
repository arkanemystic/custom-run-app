package dev.pacer.core

import org.junit.Assert.assertEquals
import org.junit.Test

class RunArchiveTest {
    @Test fun archiveRoundTrips() {
        val ivs = Workout.STARTER.intervals()
        val a = RunArchive(
            title = "Run/Walk\t8x1:00", plan = Plan.Intervals(Workout.STARTER.copy(id = 4)),
            startWallMs = 1_700_000_000_000, endWallMs = 1_700_001_800_000, distanceM = 4123.45,
            movingMs = 1_790_000, elapsedMs = 1_800_000, splitsMs = listOf(600_000, 590_000),
            intervals = listOf(RunSession.IntervalResult(ivs[1], 60_000, 160.9)),
            points = listOf(
                TrackPoint(1_700_000_000_000, 37.1234567, -122.7654321, 12.5, 4.5f, 0.0, 0, true),
                TrackPoint(1_700_000_001_000, 37.1234667, -122.7654221, null, 3.0f, 1.25, 1000, false),
            ),
        )
        val back = RunArchive.decode(a.encode())
        assertEquals(a.copy(title = "Run/Walk 8x1:00"), back)
    }

    @Test fun configRoundTrips() {
        val c = SyncConfig(
            workouts = listOf(Workout.STARTER.copy(id = 1), Workout.STARTER.copy(id = 2, name = "Tempo", walk = Step(0))),
            voice = false, haptics = true, coaching = false, mileCues = true, coachSpacingSec = 35, freeTargetPaceSec = 540,
        )
        assertEquals(c, SyncConfig.decode(c.encode()))
        assertEquals(SyncConfig(emptyList()), SyncConfig.decode(SyncConfig(emptyList()).encode()))
    }
}
