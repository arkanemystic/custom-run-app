package dev.pacer.core

import kotlin.math.max

/**
 * Decides which fixes count. Rejects poor accuracy, waits for accuracy to settle after a
 * (re)start, rejects implausible jumps, and only advances the distance anchor once movement
 * exceeds the noise floor so standing still doesn't accumulate GPS jitter as distance.
 */
class LocationFilter(
    private val maxAccuracyM: Float = 20f,
    private val settleFixes: Int = 3,
    private val maxSpeedMps: Double = 7.0,
    private val reanchorAfterJumps: Int = 5,
    private val stationaryMps: Float = 0.3f,
) {
    sealed interface Decision {
        data class Rejected(val reason: String) : Decision
        /** [addedMeters] is the distance to add to the run (0 while within the noise floor). */
        data class Accepted(val addedMeters: Double, val segmentStart: Boolean) : Decision
    }

    private var goodStreak = 0
    private var anchor: Fix? = null
    private var last: Fix? = null
    private var jumpStreak = 0
    private var newSegment = true

    /** Call at start and on resume: distance never bridges a pause, and accuracy must settle again. */
    fun startSegment() {
        goodStreak = 0
        anchor = null
        last = null
        jumpStreak = 0
        newSegment = true
    }

    fun offer(fix: Fix): Decision {
        if (fix.accuracyM > maxAccuracyM) {
            goodStreak = 0
            return Decision.Rejected("accuracy ${fix.accuracyM} m")
        }
        last?.let { if (fix.timeMs <= it.timeMs) return Decision.Rejected("stale") }
        if (anchor == null) {
            goodStreak++
            if (goodStreak < settleFixes) return Decision.Rejected("settling")
            return acceptAsAnchor(fix)
        }
        val a = anchor!!
        val prev = last!!
        // Jump test against the previous accepted fix, which is close in time.
        val dPrev = haversineMeters(prev.lat, prev.lon, fix.lat, fix.lon)
        val dtPrev = (fix.timeMs - prev.timeMs) / 1000.0
        if (dPrev / dtPrev > maxSpeedMps) {
            jumpStreak++
            if (jumpStreak >= reanchorAfterJumps) {
                // The previous fixes were probably the bad ones. Start over from here without
                // crediting the unknowable distance in between.
                return acceptAsAnchor(fix)
            }
            return Decision.Rejected("jump ${"%.1f".format(dPrev / dtPrev)} m/s")
        }
        jumpStreak = 0
        last = fix
        // A reliable Doppler speed near zero means we're standing still, whatever the position says.
        if (fix.speedMps != null && fix.speedAccuracyMps != null &&
            fix.speedAccuracyMps <= 1f && fix.speedMps < stationaryMps
        ) {
            return Decision.Accepted(0.0, segmentStart = false)
        }
        val d = haversineMeters(a.lat, a.lon, fix.lat, fix.lon)
        // Moves smaller than the reported accuracy are indistinguishable from jitter. Holding the
        // anchor means real movement is still credited once it adds up (a longer chord).
        if (d < max(2.0, fix.accuracyM.toDouble())) return Decision.Accepted(0.0, segmentStart = false)
        anchor = fix
        return Decision.Accepted(d, segmentStart = false)
    }

    private fun acceptAsAnchor(fix: Fix): Decision {
        anchor = fix
        last = fix
        jumpStreak = 0
        val start = newSegment
        newSegment = false
        return Decision.Accepted(0.0, segmentStart = start)
    }
}
