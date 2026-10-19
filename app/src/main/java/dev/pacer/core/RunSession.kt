package dev.pacer.core

/**
 * All run logic in one place, free of Android: feed it fixes and once-per-second ticks with
 * elapsedRealtime timestamps and it returns the cues to play. The foreground service is only
 * plumbing around this, which is what lets the whole screen-off flow be unit tested.
 */
class RunSession(
    val plan: Plan,
    startMs: Long,
    coachSpacingMs: Long = 20_000,
) {
    sealed interface Event {
        data class Countdown(val n: Int, val next: Interval?) : Event
        data class IntervalStarted(val index: Int, val interval: Interval) : Event
        data object WorkoutFinished : Event
        data class Coaching(val cue: Coach.Cue) : Event
        data class MileSplit(val mile: Int, val splitMs: Long, val avgPaceSec: Double?) : Event
    }

    enum class PaceStatus { NONE, TOO_SLOW, IN_BAND, TOO_FAST }

    data class Snapshot(
        val movingMs: Long,
        val elapsedMs: Long,
        val distanceM: Double,
        val livePaceSec: Double?,
        val lastMile: MileTracker.LastMile,
        val lastSplitMs: Long?,
        val avgPaceSec: Double?,
        val interval: IntervalEngine.State?,
        val targetPaceSec: Int?,
        val paceStatus: PaceStatus,
        val paused: Boolean,
    )

    data class IntervalResult(val interval: Interval, val movingMs: Long, val distanceM: Double) {
        val paceSec: Double? get() = paceSecPerMile(movingMs, distanceM)
    }

    var clock = RunClock(startMs)
        private set
    var distanceM = 0.0
        private set
    var lastFix: Fix? = null
        private set

    val engine = IntervalEngine((plan as? Plan.Intervals)?.workout?.intervals().orEmpty())
    private val filter = LocationFilter().also { it.startSegment() }
    private val live = LivePace()
    private val miles = MileTracker()
    private val coach = Coach(coachSpacingMs)
    private val intervalDistance = DoubleArray(engine.intervals.size)
    private val pending = ArrayList<TrackPoint>()

    val splitsMs: List<Long> get() = miles.splitsMs

    var coachSpacingMs: Long
        get() = coach.minSpacingMs
        set(v) { coach.minSpacingMs = v }

    fun onFix(fix: Fix): List<Event> {
        lastFix = fix
        if (clock.isPaused) return emptyList()
        val d = filter.offer(fix) as? LocationFilter.Decision.Accepted ?: return emptyList()
        val moving = clock.movingMs(fix.timeMs)
        distanceM += d.addedMeters
        val idx = engine.state(moving).index
        if (idx >= 0) intervalDistance[idx] += d.addedMeters
        live.onFix(fix.timeMs, distanceM, fix.speedMps, fix.speedAccuracyMps)
        pending += TrackPoint(fix.wallTimeMs, fix.lat, fix.lon, fix.altitudeM, fix.accuracyM, distanceM, moving, d.segmentStart)
        val before = miles.splitsMs.size
        miles.add(moving, distanceM)
        return (before until miles.splitsMs.size).map { i ->
            Event.MileSplit(i + 1, miles.splitsMs[i], paceSecPerMile(moving, distanceM))
        }
    }

    fun tick(nowMs: Long): List<Event> {
        if (clock.isPaused) return emptyList()
        val moving = clock.movingMs(nowMs)
        val events = mutableListOf<Event>()
        for (e in engine.advance(moving)) {
            events += when (e) {
                is IntervalEngine.Event.Countdown -> Event.Countdown(e.n, e.next)
                is IntervalEngine.Event.Started -> Event.IntervalStarted(e.index, e.interval)
                IntervalEngine.Event.Finished -> Event.WorkoutFinished
            }
        }
        val cue = when (plan) {
            is Plan.Free -> coach.evaluate(
                moving, moving, null, plan.targetPaceSec, plan.toleranceSec,
                live.averagePaceSecPerMile(nowMs - COACH_WINDOW_MS, nowMs),
            )
            is Plan.Intervals -> engine.state(moving).takeUnless { it.finished }?.let { s ->
                // Average over the last 20 s, ignoring the first 5 s of the interval while speed changes.
                val from = maxOf(nowMs - COACH_WINDOW_MS, nowMs - s.elapsedInIntervalMs + COACH_SKIP_MS)
                coach.evaluate(
                    moving, s.elapsedInIntervalMs, s.remainingMs, s.interval!!.targetPaceSec, plan.toleranceSec,
                    live.averagePaceSecPerMile(from, nowMs),
                )
            }
        }
        if (cue != null) events += Event.Coaching(cue)
        return events
    }

    fun pause(nowMs: Long) {
        clock = clock.pause(nowMs)
        live.reset()
    }

    fun resume(nowMs: Long) {
        clock = clock.resume(nowMs)
        filter.startSegment()
    }

    /** Points recorded since the last call, for batched database writes. */
    fun drainPoints(): List<TrackPoint> = ArrayList(pending).also { pending.clear() }

    fun targetPaceSec(nowMs: Long): Int? = when (plan) {
        is Plan.Free -> plan.targetPaceSec
        is Plan.Intervals -> engine.state(clock.movingMs(nowMs)).interval?.targetPaceSec
    }

    fun snapshot(nowMs: Long): Snapshot {
        val moving = clock.movingMs(nowMs)
        val pace = live.paceSecPerMile(nowMs)
        val target = targetPaceSec(nowMs)
        val status = when {
            target == null || pace == null -> PaceStatus.NONE
            pace > target + plan.toleranceSec -> PaceStatus.TOO_SLOW
            pace < target - plan.toleranceSec -> PaceStatus.TOO_FAST
            else -> PaceStatus.IN_BAND
        }
        return Snapshot(
            movingMs = moving,
            elapsedMs = clock.elapsedMs(nowMs),
            distanceM = distanceM,
            livePaceSec = pace,
            lastMile = miles.lastMile(moving),
            lastSplitMs = miles.splitsMs.lastOrNull(),
            avgPaceSec = paceSecPerMile(moving, distanceM),
            interval = if (engine.intervals.isEmpty()) null else engine.state(moving),
            targetPaceSec = target,
            paceStatus = status,
            paused = clock.isPaused,
        )
    }

    fun intervalResults(nowMs: Long): List<IntervalResult> {
        val moving = clock.movingMs(nowMs)
        var start = 0L
        return engine.intervals.mapIndexedNotNull { i, iv ->
            val spent = (moving - start).coerceIn(0, iv.durationMs)
            start += iv.durationMs
            if (spent <= 0) null else IntervalResult(iv, spent, intervalDistance[i])
        }
    }

    companion object {
        private const val COACH_WINDOW_MS = 20_000L
        private const val COACH_SKIP_MS = 5_000L

        /**
         * Rebuilds a session after process death from its persisted clock and the track points
         * already in the database. The interval position is marked as announced so recovery
         * doesn't repeat a cue.
         */
        fun restore(plan: Plan, clock: RunClock, points: List<TrackPoint>, nowMs: Long, coachSpacingMs: Long): RunSession {
            val s = RunSession(plan, clock.startedAtMs, coachSpacingMs)
            s.clock = clock
            var prevDistance = 0.0
            for (p in points) {
                val idx = s.engine.state(p.movingMs).index
                if (idx >= 0) s.intervalDistance[idx] += p.distanceM - prevDistance
                prevDistance = p.distanceM
                s.miles.add(p.movingMs, p.distanceM)
            }
            s.distanceM = prevDistance
            s.engine.resumeAt(clock.movingMs(nowMs))
            return s
        }
    }
}
