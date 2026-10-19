package dev.pacer.core

/**
 * Stepper rules for the workout builder: every value moves in fixed increments within sane
 * bounds, so there is no free-text parsing to get wrong.
 */
object PresetEditor {
    const val DURATION_STEP_SEC = 5
    const val MAX_DURATION_SEC = 99 * 60 + 55
    const val PACE_STEP_SEC = 5
    const val MIN_PACE_SEC = 3 * 60
    const val MAX_PACE_SEC = 30 * 60
    const val DEFAULT_PACE_SEC = 10 * 60
    const val MAX_TOLERANCE_SEC = 120
    const val MAX_REPEATS = 50

    fun duration(sec: Int, delta: Int): Int = (sec + delta).coerceIn(0, MAX_DURATION_SEC)

    /** Stepping a blank pace starts it at a default; stepping below the minimum clamps. */
    fun pace(sec: Int?, delta: Int): Int =
        if (sec == null) DEFAULT_PACE_SEC else (sec + delta).coerceIn(MIN_PACE_SEC, MAX_PACE_SEC)

    fun tolerance(sec: Int, delta: Int): Int = (sec + delta).coerceIn(0, MAX_TOLERANCE_SEC)

    fun repeats(n: Int, delta: Int): Int = (n + delta).coerceIn(0, MAX_REPEATS)

    fun duplicate(w: Workout, existingNames: Collection<String>): Workout =
        w.copy(id = 0, name = uniqueName("${w.name} (copy)", existingNames))

    fun uniqueName(base: String, existing: Collection<String>): String {
        if (base !in existing) return base
        var i = 2
        while ("$base $i" in existing) i++
        return "$base $i"
    }

    fun isValid(w: Workout): Boolean = w.name.isNotBlank() && w.intervals().isNotEmpty()
}
