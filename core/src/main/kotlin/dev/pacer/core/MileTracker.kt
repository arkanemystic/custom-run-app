package dev.pacer.core

/**
 * Tracks mile splits and the rolling pace over the most recent 1.0 mi of distance.
 * Fed (movingMs, distanceM) after every accepted fix. Everything is in moving time, so
 * paused time never counts toward a pace.
 */
class MileTracker {
    private val samples = ArrayDeque<Pair<Long, Double>>() // movingMs to distanceM
    private val _splits = mutableListOf<Long>() // moving ms per completed mile
    private var lastMileMovingMs = 0L

    val splitsMs: List<Long> get() = _splits

    fun add(movingMs: Long, distanceM: Double) {
        val prev = samples.lastOrNull()
        if (prev != null) {
            // Mile boundaries crossed between prev and this sample, interpolated in time.
            var next = (_splits.size + 1) * METERS_PER_MILE
            while (distanceM >= next) {
                val t = interpolate(prev, movingMs to distanceM, next)
                _splits += t - lastMileMovingMs
                lastMileMovingMs = t
                next = (_splits.size + 1) * METERS_PER_MILE
            }
        }
        samples.addLast(movingMs to distanceM)
        // Keep exactly one sample at or before (distance - 1 mi) for interpolation.
        val cutoff = distanceM - METERS_PER_MILE
        while (samples.size > 2 && samples[1].second <= cutoff) samples.removeFirst()
    }

    /**
     * Pace over the last mile ending now. Before the first full mile, returns pace since start
     * with [isSinceStart] set so the UI can label it.
     */
    data class LastMile(val secPerMile: Double?, val isSinceStart: Boolean)

    fun lastMile(movingNowMs: Long): LastMile {
        val latest = samples.lastOrNull() ?: return LastMile(null, true)
        val d = latest.second
        if (d < METERS_PER_MILE) return LastMile(paceSecPerMile(movingNowMs, d), true)
        val target = d - METERS_PER_MILE
        val first = samples.first()
        val atMs = if (samples.size >= 2 && first.second <= target) {
            interpolate(first, samples[1], target)
        } else {
            first.first
        }
        return LastMile((movingNowMs - atMs) / 1000.0, false)
    }

    private fun interpolate(a: Pair<Long, Double>, b: Pair<Long, Double>, distance: Double): Long {
        val span = b.second - a.second
        if (span <= 0) return b.first
        val f = ((distance - a.second) / span).coerceIn(0.0, 1.0)
        return a.first + ((b.first - a.first) * f).toLong()
    }
}
