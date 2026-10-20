package dev.pacer.core

/** Synthetic straight-line route generator for tests. */
class TestRoute(
    var lat: Double = 37.7749,
    var lon: Double = -122.4194,
    private val bearing: Double = 90.0,
) {
    fun step(meters: Double) {
        val (la, lo) = offset(lat, lon, meters, bearing)
        lat = la; lon = lo
    }

    fun fix(timeMs: Long, accuracy: Float = 5f, speed: Float? = null, speedAcc: Float? = null, alt: Double? = 10.0) =
        Fix(timeMs, 1_700_000_000_000 + timeMs, lat, lon, accuracy, speed, speedAcc, alt)
}

fun paceToSpeed(secPerMile: Double) = METERS_PER_MILE / secPerMile
