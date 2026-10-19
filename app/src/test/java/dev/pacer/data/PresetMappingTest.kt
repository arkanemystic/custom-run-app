package dev.pacer.data

import dev.pacer.core.PresetEditor
import dev.pacer.core.Step
import dev.pacer.core.Workout
import org.junit.Assert.assertEquals
import org.junit.Test

class PresetMappingTest {
    @Test fun editedPresetSurvivesEntityRoundTrip() {
        var w = Workout.STARTER.copy(id = 3)
        w = w.copy(
            name = "  Tempo  ",
            run = Step(PresetEditor.duration(w.run.durationSec, 5), PresetEditor.pace(w.run.targetPaceSec, -5)),
            walk = Step(w.walk.durationSec, null),
            repeats = PresetEditor.repeats(w.repeats, 2),
            walkToleranceSec = PresetEditor.tolerance(30, 5),
        )
        val back = PresetEntity.from(w).toWorkout()
        assertEquals(w.copy(name = "Tempo"), back)
        assertEquals(Step(65, 595), back.run)
        assertEquals(null, back.walk.targetPaceSec)
    }
}
