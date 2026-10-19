package dev.pacer.service

import dev.pacer.core.RunSession
import dev.pacer.data.Settings

/** Turns run events into voice and haptics. Implemented in stage 6. */
interface Cues {
    fun handle(events: List<RunSession.Event>, session: RunSession, nowMs: Long, settings: Settings)
    fun release()

    object None : Cues {
        override fun handle(events: List<RunSession.Event>, session: RunSession, nowMs: Long, settings: Settings) = Unit
        override fun release() = Unit
    }
}
