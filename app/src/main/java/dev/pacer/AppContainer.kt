package dev.pacer

import android.content.Context
import dev.pacer.data.PacerDatabase
import dev.pacer.data.PresetRepository
import dev.pacer.data.RunRepository
import dev.pacer.data.SettingsRepository

/** Manual dependency injection: one instance of each, created lazily on first use. */
class AppContainer(context: Context) {
    private val app = context.applicationContext
    private val db by lazy { PacerDatabase.create(app) }
    val settings by lazy { SettingsRepository(app) }
    val presets by lazy { PresetRepository(db.presets(), settings) }
    val runs by lazy { RunRepository(db.runs()) }
}
