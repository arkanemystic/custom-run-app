package dev.pacer.core

/**
 * Compact text encodings for values stored as single Room columns: the plan a run followed
 * (a snapshot, so editing the preset later doesn't change history), splits and interval results.
 * Plain Kotlin so it is unit tested without Android.
 */
object Codec {
    fun encodePlan(plan: Plan): String = when (plan) {
        is Plan.Free -> "F|${plan.targetPaceSec ?: ""}|${plan.toleranceSec}"
        is Plan.Intervals -> with(plan.workout) {
            listOf(
                "I", id, warmup.durationSec, warmup.targetPaceSec ?: "", repeats,
                run.durationSec, run.targetPaceSec ?: "", walk.durationSec, walk.targetPaceSec ?: "",
                cooldown.durationSec, cooldown.targetPaceSec ?: "", toleranceSec, walkToleranceSec, name,
            ).joinToString("|")
        }
    }

    fun decodePlan(s: String): Plan {
        val p = s.split("|", limit = 14)
        fun i(n: Int) = p[n].toInt()
        fun o(n: Int) = p[n].toIntOrNull()
        return when (p[0]) {
            "F" -> Plan.Free(o(1), i(2))
            "I" -> Plan.Intervals(
                Workout(
                    id = p[1].toLong(), name = p[13],
                    warmup = Step(i(2), o(3)), repeats = i(4), run = Step(i(5), o(6)),
                    walk = Step(i(7), o(8)), cooldown = Step(i(9), o(10)),
                    toleranceSec = i(11), walkToleranceSec = i(12),
                ),
            )
            else -> error("bad plan '$s'")
        }
    }

    fun encodeSplits(ms: List<Long>): String = ms.joinToString(",")

    fun decodeSplits(s: String): List<Long> = if (s.isEmpty()) emptyList() else s.split(",").map { it.toLong() }

    fun encodeIntervals(r: List<RunSession.IntervalResult>): String = r.joinToString(";") {
        with(it.interval) { "$kind:$rep:$durationSec:${targetPaceSec ?: ""}:${it.movingMs}:${"%.1f".format(java.util.Locale.ROOT, it.distanceM)}" }
    }

    fun decodeIntervals(s: String): List<RunSession.IntervalResult> =
        if (s.isEmpty()) emptyList() else s.split(";").map { e ->
            val p = e.split(":")
            RunSession.IntervalResult(
                Interval(IntervalKind.valueOf(p[0]), p[1].toInt(), p[2].toInt(), p[3].toIntOrNull()),
                p[4].toLong(), p[5].toDouble(),
            )
        }
}
