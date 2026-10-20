package dev.pacer.core

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechTest {
    @Test fun intervalAnnouncement() {
        assertEquals("Run, one minute, target ten minutes per mile", Speech.intervalStart(Workout.STARTER.intervals()[1]))
        assertEquals("Walk, one minute 30 seconds, target sixteen minutes per mile", Speech.intervalStart(Workout.STARTER.intervals()[2]))
        assertEquals("Warm up, 5 minutes", Speech.intervalStart(Workout.STARTER.intervals()[0]))
    }

    @Test fun paces() {
        assertEquals("ten thirty two", Speech.pace(632.0))
        assertEquals("nine oh five", Speech.pace(545.0))
        assertEquals("eight flat", Speech.pace(480.2))
        assertEquals("nine forty five per mile", Speech.targetPace(585))
    }

    @Test fun coachingAndMile() {
        assertEquals("Speed up. ten forty", Speech.coach(Coach.Cue(Coach.Verdict.SPEED_UP, 640.0)))
        assertEquals("Mile 2. Split nine fifty. Average ten oh one", Speech.mile(2, 590.0, 601.0))
    }
}
