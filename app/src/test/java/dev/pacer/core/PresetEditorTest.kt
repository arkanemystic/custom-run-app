package dev.pacer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetEditorTest {
    @Test fun steppersClamp() {
        assertEquals(0, PresetEditor.duration(3, -5))
        assertEquals(PresetEditor.MAX_DURATION_SEC, PresetEditor.duration(PresetEditor.MAX_DURATION_SEC, 5))
        assertEquals(65, PresetEditor.duration(60, 5))
        assertEquals(PresetEditor.MIN_PACE_SEC, PresetEditor.pace(182, -5))
        assertEquals(PresetEditor.DEFAULT_PACE_SEC, PresetEditor.pace(null, 5))
        assertEquals(0, PresetEditor.tolerance(3, -5))
        assertEquals(1, PresetEditor.repeats(0, 1))
    }

    @Test fun duplicateGetsUniqueNameAndNoId() {
        val w = Workout.STARTER.copy(id = 4)
        val d = PresetEditor.duplicate(w, listOf(w.name, "${w.name} (copy)"))
        assertEquals(0, d.id)
        assertEquals("${w.name} (copy) 2", d.name)
        assertEquals(w.intervals(), d.intervals())
    }

    @Test fun zeroLengthStepsAreSkipped() {
        val w = Workout.STARTER.copy(warmup = Step(0), walk = Step(0))
        assertEquals(9, w.intervals().size)
        assertEquals("Intervals 8x1:00", w.title())
    }

    @Test fun validity() {
        assertTrue(PresetEditor.isValid(Workout.STARTER))
        assertFalse(PresetEditor.isValid(Workout.STARTER.copy(name = " ")))
        assertFalse(PresetEditor.isValid(Workout.STARTER.copy(warmup = Step(0), repeats = 0, cooldown = Step(0))))
    }
}
