package dev.pacer.core

import java.util.Locale

/**
 * A finished run as one self-contained text blob: how the watch stores runs and sends them to
 * the phone. Header line, then one CSV line per track point.
 */
data class RunArchive(
    val title: String,
    val plan: Plan,
    val startWallMs: Long,
    val endWallMs: Long,
    val distanceM: Double,
    val movingMs: Long,
    val elapsedMs: Long,
    val splitsMs: List<Long>,
    val intervals: List<RunSession.IntervalResult>,
    val points: List<TrackPoint>,
) {
    fun encode(): String = buildString {
        append(MAGIC).append('\n')
        append(
            listOf(
                clean(title), Codec.encodePlan(plan), startWallMs, endWallMs, "%.2f".format(Locale.ROOT, distanceM),
                movingMs, elapsedMs, Codec.encodeSplits(splitsMs), Codec.encodeIntervals(intervals),
            ).joinToString("\t"),
        ).append('\n')
        points.forEach { append(encodePoint(it)).append('\n') }
    }

    companion object {
        private const val MAGIC = "PACER-RUN 1"

        fun decode(s: String): RunArchive {
            val lines = s.lineSequence().filter { it.isNotEmpty() }.toList()
            require(lines.firstOrNull() == MAGIC) { "not a Pacer run" }
            val h = lines[1].split("\t")
            return RunArchive(
                title = h[0], plan = Codec.decodePlan(h[1]), startWallMs = h[2].toLong(), endWallMs = h[3].toLong(),
                distanceM = h[4].toDouble(), movingMs = h[5].toLong(), elapsedMs = h[6].toLong(),
                splitsMs = Codec.decodeSplits(h[7]), intervals = Codec.decodeIntervals(h[8]),
                points = lines.drop(2).map(::decodePoint),
            )
        }

        fun fromSession(session: RunSession, title: String, startWallMs: Long, endWallMs: Long, nowMs: Long, points: List<TrackPoint>) =
            RunArchive(
                title, session.plan, startWallMs, endWallMs, session.distanceM, session.clock.movingMs(nowMs),
                session.clock.elapsedMs(nowMs), session.splitsMs, session.intervalResults(nowMs), points,
            )

        fun encodePoint(p: TrackPoint): String = listOf(
            p.wallTimeMs, "%.7f".format(Locale.ROOT, p.lat), "%.7f".format(Locale.ROOT, p.lon),
            p.altitudeM?.let { "%.1f".format(Locale.ROOT, it) } ?: "", "%.1f".format(Locale.ROOT, p.accuracyM),
            "%.2f".format(Locale.ROOT, p.distanceM), p.movingMs, if (p.segmentStart) 1 else 0,
        ).joinToString(",")

        fun decodePoint(line: String): TrackPoint {
            val f = line.split(",")
            return TrackPoint(
                f[0].toLong(), f[1].toDouble(), f[2].toDouble(), f[3].toDoubleOrNull(), f[4].toFloat(),
                f[5].toDouble(), f[6].toLong(), f[7] == "1",
            )
        }

        private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ')
    }
}

/** What the phone sends the watch: presets plus the cue settings. Edited on the phone only. */
data class SyncConfig(
    val workouts: List<Workout>,
    val voice: Boolean = true,
    val haptics: Boolean = true,
    val coaching: Boolean = true,
    val mileCues: Boolean = true,
    val coachSpacingSec: Int = 20,
    val freeTargetPaceSec: Int? = null,
) {
    fun encode(): String = buildString {
        append("S|$voice|$haptics|$coaching|$mileCues|$coachSpacingSec|${freeTargetPaceSec ?: ""}\n")
        workouts.forEach { append(Codec.encodePlan(Plan.Intervals(it.copy(name = it.name.replace('\n', ' ')))) ).append('\n') }
    }

    companion object {
        fun decode(s: String): SyncConfig {
            val lines = s.lineSequence().filter { it.isNotEmpty() }.toList()
            val h = lines.first().split("|")
            require(h[0] == "S")
            return SyncConfig(
                workouts = lines.drop(1).map { (Codec.decodePlan(it) as Plan.Intervals).workout },
                voice = h[1].toBoolean(), haptics = h[2].toBoolean(), coaching = h[3].toBoolean(),
                mileCues = h[4].toBoolean(), coachSpacingSec = h[5].toInt(), freeTargetPaceSec = h[6].toIntOrNull(),
            )
        }
    }
}
