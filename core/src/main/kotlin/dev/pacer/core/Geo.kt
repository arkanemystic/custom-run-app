package dev.pacer.core

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private const val EARTH_RADIUS_M = 6_371_008.8

fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).let { it * it } +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).let { it * it }
    return 2 * EARTH_RADIUS_M * asin(sqrt(a.coerceIn(0.0, 1.0)))
}

/** Moves a point [meters] along [bearingDeg]. Used by tests and the debug mock route. */
fun offset(lat: Double, lon: Double, meters: Double, bearingDeg: Double): Pair<Double, Double> {
    val d = meters / EARTH_RADIUS_M
    val b = Math.toRadians(bearingDeg)
    val la1 = Math.toRadians(lat)
    val lo1 = Math.toRadians(lon)
    val la2 = asin(sin(la1) * cos(d) + cos(la1) * sin(d) * cos(b))
    val lo2 = lo1 + kotlin.math.atan2(sin(b) * sin(d) * cos(la1), cos(d) - sin(la1) * sin(la2))
    return Math.toDegrees(la2) to Math.toDegrees(lo2)
}
