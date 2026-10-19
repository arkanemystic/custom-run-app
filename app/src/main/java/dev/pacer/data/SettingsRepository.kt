package dev.pacer.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class Settings(
    val voice: Boolean = true,
    val haptics: Boolean = true,
    val coaching: Boolean = true,
    val mileCues: Boolean = true,
    val coachSpacingSec: Int = 20,
    val followCamera: Boolean = true,
    /** -1 = free run. */
    val selectedPresetId: Long = -1,
    val freeTargetPaceSec: Int? = null,
    val mockLocation: Boolean = false,
    val mockPaceSec: Int = 600,
)

private val Context.dataStore by preferencesDataStore("settings")

class SettingsRepository(context: Context) {
    private val store = context.applicationContext.dataStore

    private object K {
        val voice = booleanPreferencesKey("voice")
        val haptics = booleanPreferencesKey("haptics")
        val coaching = booleanPreferencesKey("coaching")
        val mileCues = booleanPreferencesKey("mile_cues")
        val spacing = intPreferencesKey("coach_spacing_sec")
        val follow = booleanPreferencesKey("follow_camera")
        val preset = longPreferencesKey("selected_preset")
        val freeTarget = intPreferencesKey("free_target_pace")
        val seeded = booleanPreferencesKey("seeded")
        val mock = booleanPreferencesKey("mock_location")
        val mockPace = intPreferencesKey("mock_pace")
    }

    val settings: Flow<Settings> = store.data.map { p ->
        val d = Settings()
        Settings(
            voice = p[K.voice] ?: d.voice,
            haptics = p[K.haptics] ?: d.haptics,
            coaching = p[K.coaching] ?: d.coaching,
            mileCues = p[K.mileCues] ?: d.mileCues,
            coachSpacingSec = p[K.spacing] ?: d.coachSpacingSec,
            followCamera = p[K.follow] ?: d.followCamera,
            selectedPresetId = p[K.preset] ?: d.selectedPresetId,
            freeTargetPaceSec = p[K.freeTarget]?.takeIf { it > 0 },
            mockLocation = p[K.mock] ?: d.mockLocation,
            mockPaceSec = p[K.mockPace] ?: d.mockPaceSec,
        )
    }

    suspend fun current(): Settings = settings.first()

    private suspend fun <T> set(key: Preferences.Key<T>, v: T) { store.edit { it[key] = v } }

    suspend fun setVoice(v: Boolean) = set(K.voice, v)
    suspend fun setHaptics(v: Boolean) = set(K.haptics, v)
    suspend fun setCoaching(v: Boolean) = set(K.coaching, v)
    suspend fun setMileCues(v: Boolean) = set(K.mileCues, v)
    suspend fun setCoachSpacing(sec: Int) = set(K.spacing, sec.coerceIn(10, 120))
    suspend fun setFollowCamera(v: Boolean) = set(K.follow, v)
    suspend fun setSelectedPreset(id: Long) = set(K.preset, id)
    suspend fun setFreeTargetPace(sec: Int?) = set(K.freeTarget, sec ?: 0)
    suspend fun setMockLocation(v: Boolean) = set(K.mock, v)
    suspend fun setMockPace(sec: Int) = set(K.mockPace, sec)

    /** Returns true exactly once per install. */
    suspend fun markSeeded(): Boolean {
        var first = false
        store.edit { if (it[K.seeded] != true) { first = true; it[K.seeded] = true } }
        return first
    }
}
