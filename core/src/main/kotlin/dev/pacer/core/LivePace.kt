package dev.pacer.core

import kotlin.math.exp

/**
 * Smoothed current speed. Prefers the GNSS Doppler speed (Location.speed) when its reported
 * accuracy is good; otherwise uses distance over the last ~10 s. Smoothed with a time-based EMA
 * so irregular fix intervals don't change the smoothing.
 */
class LivePace(
    private val tauSec: Double = 5.0,
    private val windowMs: Long = 10_000,
    private val maxSpeedAccuracyMps: Float = 1.5f,
    private val stationaryMps: Double = 0.5,
    private val staleMs: Long = 8_000,
) {
    private val samples = ArrayDeque<Pair<Long, Double>>() // timeMs to cumulative distance
    private val instants = ArrayDeque<Pair<Long, Double>>() // timeMs to unsmoothed speed
    private var ema: Double? = null
    private var lastUpdateMs = 0L

    fun reset() {
        samples.clear()
        instants.clear()
        ema = null
    }

    fun onFix(timeMs: Long, cumDistanceM: Double, speedMps: Float?, speedAccuracyMps: Float?) {
        samples.addLast(timeMs to cumDistanceM)
        while (samples.size > 2 && timeMs - samples[1].first >= windowMs) samples.removeFirst()

        val instant: Double? = if (speedMps != null && speedAccuracyMps != null &&
            speedAccuracyMps <= maxSpeedAccuracyMps
        ) {
            speedMps.toDouble()
        } else {
            val first = samples.first()
            val span = timeMs - first.first
            if (span >= 3_000) (cumDistanceM - first.second) / (span / 1000.0) else null
        }
        if (instant == null) return
        instants.addLast(timeMs to instant)
        while (instants.isNotEmpty() && timeMs - instants.first().first > MAX_AVERAGE_MS) instants.removeFirst()
        val prev = ema
        ema = if (prev == null) instant else {
            val dt = ((timeMs - lastUpdateMs) / 1000.0).coerceAtLeast(0.0)
            val alpha = 1 - exp(-dt / tauSec)
            prev + alpha * (instant - prev)
        }
        lastUpdateMs = timeMs
    }

    /** Smoothed speed, or null when stationary or when fixes have stopped arriving. */
    fun speedMps(nowMs: Long): Double? {
        val v = ema ?: return null
        if (nowMs - lastUpdateMs > staleMs) return null
        return if (v < stationaryMps) null else v
    }

    fun paceSecPerMile(nowMs: Long): Double? = speedMps(nowMs)?.let(::speedToPace)

    /**
     * Mean pace over samples taken at or after [fromMs] (at most the last 30 s), used for coaching
     * decisions. At walking pace a 15 s/mi band is about 1.5% of speed, close to GPS speed noise,
     * so coaching needs a longer, steadier average than the on-screen number.
     */
    fun averagePaceSecPerMile(fromMs: Long, nowMs: Long, minSamples: Int = 8): Double? {
        if (nowMs - lastUpdateMs > staleMs) return null
        var sum = 0.0
        var n = 0
        for ((t, v) in instants) if (t >= fromMs) { sum += v; n++ }
        if (n < minSamples) return null
        val v = sum / n
        return if (v < stationaryMps) null else speedToPace(v)
    }

    private companion object {
        const val MAX_AVERAGE_MS = 30_000L
    }
}
