package dev.pacer.wear

import android.content.Context
import dev.pacer.core.Codec
import dev.pacer.core.Plan
import dev.pacer.core.RunArchive
import dev.pacer.core.RunClock
import dev.pacer.core.SyncConfig
import dev.pacer.core.TrackPoint
import dev.pacer.core.Workout
import java.io.File

/**
 * The watch's storage: plain files, no database. The active run is a small meta file plus an
 * append-only points file (batched writes, recoverable after process death); finished runs wait
 * in an outbox until the phone confirms it has them.
 */
class RunStore(context: Context) {
    private val dir = context.filesDir
    private val config = File(dir, "config.txt")
    private val activeMeta = File(dir, "active.meta")
    private val activePoints = File(dir, "active.points")
    private val outbox = File(dir, "outbox").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("wear", Context.MODE_PRIVATE)

    data class ActiveMeta(val plan: Plan, val title: String, val startWallMs: Long, val clock: RunClock, val bootCount: Int)

    fun config(): SyncConfig =
        runCatching { SyncConfig.decode(config.readText()) }.getOrNull() ?: SyncConfig(listOf(Workout.STARTER.copy(id = 1)))

    fun hasPhoneConfig(): Boolean = config.exists()

    fun saveConfig(text: String) {
        SyncConfig.decode(text) // Validate before replacing.
        config.writeText(text)
    }

    /** Preset id, or -1 for a free run. */
    var selectedId: Long
        get() = prefs.getLong("selected", 1)
        set(v) { prefs.edit().putLong("selected", v).apply() }

    fun startActive(meta: ActiveMeta) {
        activePoints.delete()
        writeMeta(meta)
    }

    fun writeMeta(m: ActiveMeta) {
        val c = m.clock
        activeMeta.writeText(
            listOf(m.title.replace('\t', ' '), Codec.encodePlan(m.plan), m.startWallMs, c.startedAtMs, c.movingBeforeSegmentMs, c.segmentStartMs ?: "", m.bootCount)
                .joinToString("\t"),
        )
    }

    fun appendPoints(points: List<TrackPoint>) {
        if (points.isEmpty()) return
        activePoints.appendText(points.joinToString("") { RunArchive.encodePoint(it) + "\n" })
    }

    fun readActive(): Pair<ActiveMeta, List<TrackPoint>>? {
        if (!activeMeta.exists()) return null
        return runCatching {
            val f = activeMeta.readText().split("\t")
            val meta = ActiveMeta(
                Codec.decodePlan(f[1]), f[0], f[2].toLong(),
                RunClock(f[3].toLong(), f[4].toLong(), f[5].toLongOrNull()), f[6].toInt(),
            )
            val pts = if (activePoints.exists()) activePoints.readLines().filter { it.isNotBlank() }.map(RunArchive::decodePoint) else emptyList()
            meta to pts
        }.getOrNull()
    }

    fun clearActive() {
        activeMeta.delete()
        activePoints.delete()
    }

    fun addToOutbox(a: RunArchive): File = File(outbox, "${a.startWallMs}.run").apply { writeText(a.encode()) }

    fun outboxFiles(): List<File> = outbox.listFiles()?.filter { it.name.endsWith(".run") }.orEmpty()

    fun removeFromOutbox(id: String) {
        File(outbox, "$id.run").delete()
    }
}
