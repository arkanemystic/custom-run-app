package dev.pacer.core

/**
 * Pure state machine over moving time. Because it is driven by moving time, pausing the run
 * pauses the intervals. [advance] is called once per tick and returns what happened since the
 * previous call; if a tick is late it reports only the latest transition/countdown, never a burst.
 */
class IntervalEngine(val intervals: List<Interval>) {
    private val starts: LongArray = LongArray(intervals.size).also { arr ->
        var t = 0L
        intervals.forEachIndexed { i, iv -> arr[i] = t; t += iv.durationMs }
    }
    val totalMs: Long = intervals.sumOf { it.durationMs }

    data class State(
        /** -1 once the workout is complete. */
        val index: Int,
        val interval: Interval?,
        val elapsedInIntervalMs: Long,
        val remainingMs: Long,
        val next: Interval?,
    ) {
        val finished: Boolean get() = index < 0
    }

    sealed interface Event {
        /** Announced before each transition: 3, 2, 1. */
        data class Countdown(val n: Int, val next: Interval?) : Event
        data class Started(val index: Int, val interval: Interval) : Event
        data object Finished : Event
    }

    fun state(movingMs: Long): State {
        if (intervals.isEmpty() || movingMs >= totalMs) return State(-1, null, 0, 0, null)
        val i = starts.indexOfLast { it <= movingMs }.coerceAtLeast(0)
        val iv = intervals[i]
        val inMs = movingMs - starts[i]
        return State(i, iv, inMs, iv.durationMs - inMs, intervals.getOrNull(i + 1))
    }

    private var lastIndex: Int = Int.MIN_VALUE
    private var lastCountdown: Int = Int.MAX_VALUE

    /** For recovery: mark the current position as already announced. */
    fun resumeAt(movingMs: Long) {
        val s = state(movingMs)
        lastIndex = s.index
        lastCountdown = countdownValue(s)
    }

    fun advance(movingMs: Long): List<Event> {
        if (intervals.isEmpty()) return emptyList()
        val s = state(movingMs)
        val events = mutableListOf<Event>()
        if (s.index != lastIndex) {
            lastIndex = s.index
            lastCountdown = Int.MAX_VALUE
            events += if (s.finished) Event.Finished else Event.Started(s.index, s.interval!!)
        }
        if (!s.finished) {
            val n = countdownValue(s)
            if (n in 1..3 && n < lastCountdown && s.interval!!.durationMs > 3_000) {
                events += Event.Countdown(n, s.next)
            }
            lastCountdown = minOf(lastCountdown, n)
        }
        return events
    }

    /** Whole seconds left, rounded up: 3 during (2 s, 3 s]. */
    private fun countdownValue(s: State): Int =
        if (s.finished) 0 else ((s.remainingMs + 999) / 1000).toInt()
}
