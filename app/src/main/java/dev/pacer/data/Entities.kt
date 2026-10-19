package dev.pacer.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.pacer.core.Step
import dev.pacer.core.TrackPoint
import dev.pacer.core.Workout

@Entity(tableName = "presets")
data class PresetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val warmupSec: Int,
    val warmupPace: Int?,
    val repeats: Int,
    val runSec: Int,
    val runPace: Int?,
    val walkSec: Int,
    val walkPace: Int?,
    val cooldownSec: Int,
    val cooldownPace: Int?,
    val toleranceSec: Int,
    val walkToleranceSec: Int,
) {
    fun toWorkout() = Workout(
        id, name, Step(warmupSec, warmupPace), repeats, Step(runSec, runPace), Step(walkSec, walkPace),
        Step(cooldownSec, cooldownPace), toleranceSec, walkToleranceSec,
    )

    companion object {
        fun from(w: Workout) = PresetEntity(
            w.id, w.name.trim(), w.warmup.durationSec, w.warmup.targetPaceSec, w.repeats,
            w.run.durationSec, w.run.targetPaceSec, w.walk.durationSec, w.walk.targetPaceSec,
            w.cooldown.durationSec, w.cooldown.targetPaceSec, w.toleranceSec, w.walkToleranceSec,
        )
    }
}

enum class RunStatus { ACTIVE, FINISHED, SAVED }

/**
 * A run. While ACTIVE, the clock columns hold everything needed to resume after process death
 * (they are on the elapsedRealtime timebase, which survives that but not a reboot).
 */
@Entity(tableName = "runs")
data class RunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val status: RunStatus,
    val title: String,
    /** Codec.encodePlan snapshot of what was followed. */
    val plan: String,
    val startWallMs: Long,
    val endWallMs: Long? = null,
    val clockStartMs: Long,
    val clockMovingBeforeMs: Long = 0,
    val clockSegmentStartMs: Long? = clockStartMs,
    val bootCount: Int,
    val distanceM: Double = 0.0,
    val movingMs: Long = 0,
    val elapsedMs: Long = 0,
    val splits: String = "",
    val intervals: String = "",
)

@Entity(
    tableName = "points",
    foreignKeys = [ForeignKey(entity = RunEntity::class, parentColumns = ["id"], childColumns = ["runId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("runId")],
)
data class PointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val runId: Long,
    val wallTimeMs: Long,
    val lat: Double,
    val lon: Double,
    val altitudeM: Double?,
    val accuracyM: Float,
    val distanceM: Double,
    val movingMs: Long,
    val segmentStart: Boolean,
) {
    fun toTrackPoint() = TrackPoint(wallTimeMs, lat, lon, altitudeM, accuracyM, distanceM, movingMs, segmentStart)

    companion object {
        fun from(runId: Long, p: TrackPoint) =
            PointEntity(0, runId, p.wallTimeMs, p.lat, p.lon, p.altitudeM, p.accuracyM, p.distanceM, p.movingMs, p.segmentStart)
    }
}
