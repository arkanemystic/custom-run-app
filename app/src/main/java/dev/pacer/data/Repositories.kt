package dev.pacer.data

import dev.pacer.core.Codec
import dev.pacer.core.Plan
import dev.pacer.core.RunArchive
import dev.pacer.core.RunClock
import dev.pacer.core.RunSession
import dev.pacer.core.TrackPoint
import dev.pacer.core.Workout
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class PresetRepository(private val dao: PresetDao, private val settings: SettingsRepository) {
    val presets: Flow<List<Workout>> = dao.observeAll().map { list -> list.map { it.toWorkout() } }

    suspend fun get(id: Long): Workout? = dao.get(id)?.toWorkout()

    /** Inserts or updates; returns the id. */
    suspend fun save(w: Workout): Long {
        val id = dao.upsert(PresetEntity.from(w))
        return if (w.id != 0L) w.id else id
    }

    suspend fun delete(id: Long) = dao.delete(id)

    /** Ships the starter preset once. Deleting it later is respected across updates. */
    suspend fun seedIfFirstLaunch() {
        if (settings.markSeeded() && dao.count() == 0) {
            settings.setSelectedPreset(save(Workout.STARTER))
        }
    }
}

class RunRepository(private val dao: RunDao) {
    val history: Flow<List<RunEntity>> = dao.observeHistory()

    fun observe(id: Long) = dao.observe(id)

    suspend fun get(id: Long) = dao.get(id)

    suspend fun active() = dao.active()

    suspend fun create(plan: Plan, startWallMs: Long, clock: RunClock, bootCount: Int): Long = dao.insert(
        RunEntity(
            status = RunStatus.ACTIVE, title = plan.title, plan = Codec.encodePlan(plan),
            startWallMs = startWallMs, clockStartMs = clock.startedAtMs, bootCount = bootCount,
        ),
    )

    /** Batched write: new track points plus the clock and totals needed to recover. */
    suspend fun checkpoint(runId: Long, session: RunSession, nowMs: Long, points: List<TrackPoint>) {
        if (points.isNotEmpty()) dao.insertPoints(points.map { PointEntity.from(runId, it) })
        val r = dao.get(runId) ?: return
        dao.update(r.withTotals(session, nowMs))
    }

    suspend fun finish(runId: Long, session: RunSession, nowMs: Long, endWallMs: Long, points: List<TrackPoint>) {
        if (points.isNotEmpty()) dao.insertPoints(points.map { PointEntity.from(runId, it) })
        val r = dao.get(runId) ?: return
        dao.update(r.withTotals(session, nowMs).copy(status = RunStatus.FINISHED, endWallMs = endWallMs))
    }

    /** Finishes a run that can't be resumed (device rebooted) with what was persisted. */
    suspend fun finishAbandoned(r: RunEntity) {
        val last = dao.points(r.id).lastOrNull()
        dao.update(r.copy(status = RunStatus.FINISHED, endWallMs = last?.wallTimeMs ?: r.startWallMs))
    }

    /**
     * Adds a run recorded on the watch as an unsaved run in History. Returns false if it was
     * already imported (the watch re-sends until the phone confirms).
     */
    suspend fun importArchive(a: RunArchive): Boolean {
        if (dao.countStartingAt(a.startWallMs) > 0) return false
        val id = dao.insert(
            RunEntity(
                status = RunStatus.FINISHED, title = a.title + " (watch)", plan = Codec.encodePlan(a.plan),
                startWallMs = a.startWallMs, endWallMs = a.endWallMs, clockStartMs = 0, clockSegmentStartMs = null,
                bootCount = -1, distanceM = a.distanceM, movingMs = a.movingMs, elapsedMs = a.elapsedMs,
                splits = Codec.encodeSplits(a.splitsMs), intervals = Codec.encodeIntervals(a.intervals),
            ),
        )
        dao.insertPoints(a.points.map { PointEntity.from(id, it) })
        return true
    }

    suspend fun markSaved(runId: Long) {
        dao.get(runId)?.let { dao.update(it.copy(status = RunStatus.SAVED)) }
    }

    suspend fun delete(runId: Long) = dao.delete(runId)

    suspend fun points(runId: Long): List<TrackPoint> = dao.points(runId).map { it.toTrackPoint() }

    private fun RunEntity.withTotals(s: RunSession, nowMs: Long) = copy(
        clockMovingBeforeMs = s.clock.movingBeforeSegmentMs,
        clockSegmentStartMs = s.clock.segmentStartMs,
        distanceM = s.distanceM,
        movingMs = s.clock.movingMs(nowMs),
        elapsedMs = s.clock.elapsedMs(nowMs),
        splits = Codec.encodeSplits(s.splitsMs),
        intervals = Codec.encodeIntervals(s.intervalResults(nowMs)),
    )
}

fun RunEntity.clock() = RunClock(clockStartMs, clockMovingBeforeMs, clockSegmentStartMs)
fun RunEntity.decodedPlan() = Codec.decodePlan(plan)
