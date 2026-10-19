package dev.pacer.core

/** Text for the GPX/Strava description and the summary header. */
object RunDescription {
    fun of(plan: Plan, distanceM: Double, movingMs: Long, splitsMs: List<Long>, intervals: List<RunSession.IntervalResult>): String {
        val lines = mutableListOf<String>()
        when (plan) {
            is Plan.Intervals -> with(plan.workout) {
                fun p(sec: Int?) = sec?.let { " @ ${formatMmSs(it)}/mi" } ?: ""
                val walkPart = if (walk.durationSec > 0) " + ${formatMmSs(walk.durationSec)} walk${p(walk.targetPaceSec)}" else ""
                lines += "${repeats} x ${formatMmSs(run.durationSec)} run${p(run.targetPaceSec)}$walkPart" +
                    (if (warmup.durationSec > 0) ", ${formatMmSs(warmup.durationSec)} warm-up" else "") +
                    (if (cooldown.durationSec > 0) ", ${formatMmSs(cooldown.durationSec)} cool-down" else "")
            }
            is Plan.Free -> lines += "Free run" + (plan.targetPaceSec?.let { ", target ${formatMmSs(it)}/mi" } ?: "")
        }
        lines += "${formatMiles(distanceM)} mi in ${formatDuration(movingMs)}, avg ${formatPace(paceSecPerMile(movingMs, distanceM))}/mi"
        if (splitsMs.isNotEmpty()) lines += "Splits: " + splitsMs.joinToString(", ") { formatDuration(it) }
        val runs = intervals.filter { it.interval.kind == IntervalKind.RUN }
        if (runs.isNotEmpty()) lines += "Run intervals: " + runs.joinToString(", ") { formatPace(it.paceSec) }
        lines += "Recorded with Pacer"
        return lines.joinToString("\n")
    }
}
