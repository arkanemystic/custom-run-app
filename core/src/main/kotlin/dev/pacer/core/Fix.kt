package dev.pacer.core

/**
 * One location fix, independent of android.location.Location so the run logic is plain Kotlin.
 * [timeMs] is on the SystemClock.elapsedRealtime() timebase; [wallTimeMs] is UTC epoch for GPX.
 */
data class Fix(
    val timeMs: Long,
    val wallTimeMs: Long,
    val lat: Double,
    val lon: Double,
    val accuracyM: Float,
    val speedMps: Float? = null,
    val speedAccuracyMps: Float? = null,
    val altitudeM: Double? = null,
)

/** A fix that made it into the recorded track, with the run totals at that moment. */
data class TrackPoint(
    val wallTimeMs: Long,
    val lat: Double,
    val lon: Double,
    val altitudeM: Double?,
    val accuracyM: Float,
    val distanceM: Double,
    val movingMs: Long,
    /** True for the first point after a resume: the line from the previous point is not run distance. */
    val segmentStart: Boolean,
)
