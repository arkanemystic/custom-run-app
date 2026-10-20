package dev.pacer.core

/**
 * Pace coaching decisions. Stays quiet in the target band, during the grace period at the
 * start of an interval, in the last seconds of an interval, and within [minSpacingMs] of the
 * previous cue. Times are moving time.
 */
class Coach(
    var minSpacingMs: Long = 20_000,
    private val graceMs: Long = 15_000,
    private val quietTailMs: Long = 10_000,
) {
    enum class Verdict { SPEED_UP, SLOW_DOWN }

    data class Cue(val verdict: Verdict, val paceSec: Double)

    private var lastCueMs: Long? = null

    fun reset() { lastCueMs = null }

    /**
     * [remainingMs] is null for an open-ended free run. [paceSec] is the smoothed live pace,
     * null when stationary or GPS has stalled (no cue then: we can't tell the runner a pace).
     */
    fun evaluate(
        movingMs: Long,
        elapsedInIntervalMs: Long,
        remainingMs: Long?,
        targetPaceSec: Int?,
        toleranceSec: Int,
        paceSec: Double?,
    ): Cue? {
        if (targetPaceSec == null || paceSec == null) return null
        if (elapsedInIntervalMs < graceMs) return null
        if (remainingMs != null && remainingMs <= quietTailMs) return null
        lastCueMs?.let { if (movingMs - it < minSpacingMs) return null }
        val verdict = when {
            paceSec > targetPaceSec + toleranceSec -> Verdict.SPEED_UP
            paceSec < targetPaceSec - toleranceSec -> Verdict.SLOW_DOWN
            else -> return null
        }
        lastCueMs = movingMs
        return Cue(verdict, paceSec)
    }
}
