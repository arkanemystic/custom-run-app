package dev.pacer.service

import dev.pacer.core.Plan
import dev.pacer.core.RunSession

/** What the UI observes. Emitted by RunService once per second while a run is active. */
sealed interface RunState {
    data object Idle : RunState

    data class Running(
        val runId: Long,
        val plan: Plan,
        val snapshot: RunSession.Snapshot,
        /** Latest raw position (may be a rejected fix) for the map dot. */
        val lat: Double?,
        val lon: Double?,
        /** Number of points in [RunService.track]; bumps when the route grows. */
        val trackSize: Int,
    ) : RunState

    /** Stop was pressed; the summary screen takes it from here. */
    data class Finished(val runId: Long) : RunState
}
