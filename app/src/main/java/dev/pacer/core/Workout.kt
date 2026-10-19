package dev.pacer.core

/** One editable step. A null target pace disables coaching for it. */
data class Step(val durationSec: Int, val targetPaceSec: Int? = null)

/**
 * A preset: warm-up, [repeats] x (run + walk), cool-down. Zero-length steps are skipped.
 * [id] is 0 for a preset that hasn't been saved.
 */
data class Workout(
    val id: Long = 0,
    val name: String,
    val warmup: Step,
    val repeats: Int,
    val run: Step,
    val walk: Step,
    val cooldown: Step,
    val toleranceSec: Int = DEFAULT_TOLERANCE_SEC,
    /** Walk pace wobbles by about ±15 s/mi from GPS noise alone, so walks get a wider band. */
    val walkToleranceSec: Int = DEFAULT_WALK_TOLERANCE_SEC,
) {
    fun intervals(): List<Interval> = buildList {
        if (warmup.durationSec > 0) add(Interval(IntervalKind.WARMUP, 0, warmup))
        for (i in 1..repeats) {
            if (run.durationSec > 0) add(Interval(IntervalKind.RUN, i, run))
            if (walk.durationSec > 0) add(Interval(IntervalKind.WALK, i, walk))
        }
        if (cooldown.durationSec > 0) add(Interval(IntervalKind.COOLDOWN, 0, cooldown))
    }

    /** Short activity title, e.g. "Run/Walk 8x1:00". */
    fun title(): String = when {
        repeats <= 0 || run.durationSec <= 0 -> name
        walk.durationSec > 0 -> "Run/Walk ${repeats}x${formatMmSs(run.durationSec)}"
        else -> "Intervals ${repeats}x${formatMmSs(run.durationSec)}"
    }

    val totalSec: Int get() = intervals().sumOf { it.durationSec }

    companion object {
        const val DEFAULT_TOLERANCE_SEC = 15
        const val DEFAULT_WALK_TOLERANCE_SEC = 30

        val STARTER = Workout(
            name = "Run/Walk 8x1:00",
            warmup = Step(5 * 60, null),
            repeats = 8,
            run = Step(60, 10 * 60),
            walk = Step(90, 16 * 60),
            cooldown = Step(5 * 60, null),
        )
    }
}

enum class IntervalKind { WARMUP, RUN, WALK, COOLDOWN, FREE }

data class Interval(val kind: IntervalKind, val rep: Int, val durationSec: Int, val targetPaceSec: Int?) {
    constructor(kind: IntervalKind, rep: Int, step: Step) : this(kind, rep, step.durationSec, step.targetPaceSec)

    val durationMs: Long get() = durationSec * 1000L

    val label: String get() = when (kind) {
        IntervalKind.WARMUP -> "Warm-up"
        IntervalKind.RUN -> "Run $rep"
        IntervalKind.WALK -> "Walk $rep"
        IntervalKind.COOLDOWN -> "Cool-down"
        IntervalKind.FREE -> "Free run"
    }
}

/** What the run is following: a preset's intervals, or a free run with an optional target. */
sealed interface Plan {
    val title: String

    /** Tolerance band for [interval] (null for a free run). */
    fun toleranceFor(interval: Interval?): Int

    data class Intervals(val workout: Workout) : Plan {
        override val title get() = workout.title()
        override fun toleranceFor(interval: Interval?) =
            if (interval?.kind == IntervalKind.WALK) workout.walkToleranceSec else workout.toleranceSec
    }

    data class Free(val targetPaceSec: Int?, val toleranceSec: Int = Workout.DEFAULT_TOLERANCE_SEC) : Plan {
        override val title get() = "Free run"
        override fun toleranceFor(interval: Interval?) = toleranceSec
    }
}
