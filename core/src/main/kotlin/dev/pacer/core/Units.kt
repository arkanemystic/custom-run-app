package dev.pacer.core

import kotlin.math.roundToInt
import kotlin.math.roundToLong

const val METERS_PER_MILE = 1609.344

/** Seconds per mile for [movingMs] spent covering [meters], or null if there is no distance yet. */
fun paceSecPerMile(movingMs: Long, meters: Double): Double? =
    if (meters < 1.0 || movingMs <= 0) null else (movingMs / 1000.0) / (meters / METERS_PER_MILE)

fun speedToPace(mps: Double): Double = METERS_PER_MILE / mps

/** "m:ss" per mile, or "--:--" when unknown or slower than 59:59. */
fun formatPace(secPerMile: Double?): String {
    if (secPerMile == null || secPerMile.isNaN() || secPerMile <= 0 || secPerMile >= 3600) return "--:--"
    val total = secPerMile.roundToInt()
    return "%d:%02d".format(total / 60, total % 60)
}

/** "m:ss" below an hour, "h:mm:ss" above. Rounds down so a countdown never shows a second early. */
fun formatDuration(ms: Long): String {
    val total = (ms.coerceAtLeast(0) / 1000)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** Countdown display: rounds up so "0:00" only shows once the interval is actually over. */
fun formatRemaining(ms: Long): String = formatDuration(((ms.coerceAtLeast(0) + 999) / 1000) * 1000)

fun formatMiles(meters: Double): String = "%.2f".format(meters / METERS_PER_MILE)

fun formatMmSs(totalSec: Int): String = "%d:%02d".format(totalSec / 60, totalSec % 60)

fun roundSec(secPerMile: Double): Int = secPerMile.roundToLong().toInt()
