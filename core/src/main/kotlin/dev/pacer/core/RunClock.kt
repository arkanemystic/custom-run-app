package dev.pacer.core

/**
 * Elapsed and moving time from absolute SystemClock.elapsedRealtime() readings, never from
 * counting ticks, so nothing can drift. The fields are the whole state and are persisted for
 * recovery after process death (elapsedRealtime keeps running across it).
 */
data class RunClock(
    val startedAtMs: Long,
    val movingBeforeSegmentMs: Long = 0,
    /** Start of the current moving segment, or null while paused. */
    val segmentStartMs: Long? = startedAtMs,
) {
    val isPaused: Boolean get() = segmentStartMs == null

    fun movingMs(nowMs: Long): Long =
        movingBeforeSegmentMs + (segmentStartMs?.let { (nowMs - it).coerceAtLeast(0) } ?: 0)

    fun elapsedMs(nowMs: Long): Long = (nowMs - startedAtMs).coerceAtLeast(0)

    fun pause(nowMs: Long): RunClock =
        if (isPaused) this else RunClock(startedAtMs, movingMs(nowMs), null)

    fun resume(nowMs: Long): RunClock =
        if (!isPaused) this else copy(segmentStartMs = nowMs)
}
