package dev.pacer.wear

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dev.pacer.core.Coach
import dev.pacer.core.IntervalKind
import dev.pacer.core.RunSession.Event
import dev.pacer.core.Speech
import dev.pacer.core.SyncConfig
import java.util.Locale

/**
 * Haptics on the wrist, plus voice only when Bluetooth headphones are connected (the watch speaker
 * is too quiet to hear while running). Text-to-speech starts on the first spoken cue, so a
 * haptics-only run never loads a speech engine.
 */
class WearCues(private val context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val vibrator: Vibrator = context.getSystemService(VibratorManager::class.java).defaultVibrator
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attrs).setOnAudioFocusChangeListener { }.build()

    private var tts: TextToSpeech? = null
    private var ready = false
    private val waiting = ArrayList<String>()
    private var pending = 0
    private var nextId = 0

    fun handle(events: List<Event>, cfg: SyncConfig) {
        for (e in events) when (e) {
            is Event.Countdown -> { say("${e.n}", cfg); buzz(TICK, cfg) }
            is Event.IntervalStarted -> {
                say(Speech.intervalStart(e.interval), cfg)
                buzz(
                    when (e.interval.kind) {
                        IntervalKind.RUN -> RUN
                        IntervalKind.WALK -> WALK
                        else -> EASY
                    },
                    cfg,
                )
            }
            Event.WorkoutFinished -> { say(Speech.WORKOUT_DONE, cfg); buzz(DONE, cfg) }
            is Event.Coaching -> if (cfg.coaching) {
                say(Speech.coach(e.cue), cfg)
                buzz(if (e.cue.verdict == Coach.Verdict.SPEED_UP) SPEED_UP else SLOW_DOWN, cfg)
            }
            is Event.MileSplit -> if (cfg.mileCues) { say(Speech.mile(e.mile, e.splitMs / 1000.0, e.avgPaceSec), cfg); buzz(EASY, cfg) }
        }
    }

    private fun buzz(p: LongArray, cfg: SyncConfig) {
        if (cfg.haptics) vibrator.vibrate(VibrationEffect.createWaveform(p, -1))
    }

    private fun headphones() = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
    }

    private fun say(text: String, cfg: SyncConfig) {
        if (!cfg.voice || !headphones()) return
        val engine = tts ?: TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) onReady()
        }.also { tts = it }
        if (!ready) { waiting += text; return }
        pending++
        audio.requestAudioFocus(focus)
        engine.speak(text, TextToSpeech.QUEUE_ADD, Bundle(), "w${nextId++}")
    }

    private fun onReady() {
        val engine = tts ?: return
        engine.language = Locale.US
        engine.setAudioAttributes(attrs)
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) = Unit
            override fun onDone(id: String?) = done()
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) = done()
        })
        ready = true
        val queued = ArrayList(waiting)
        waiting.clear()
        queued.forEach {
            pending++
            audio.requestAudioFocus(focus)
            engine.speak(it, TextToSpeech.QUEUE_ADD, Bundle(), "w${nextId++}")
        }
    }

    private fun done() {
        pending = (pending - 1).coerceAtLeast(0)
        if (pending == 0) audio.abandonAudioFocusRequest(focus)
    }

    fun release() {
        tts?.shutdown()
        tts = null
        audio.abandonAudioFocusRequest(focus)
    }

    private companion object {
        val TICK = longArrayOf(0, 60)
        val RUN = longArrayOf(0, 150, 100, 150, 100, 150)
        val WALK = longArrayOf(0, 700)
        val EASY = longArrayOf(0, 300, 200, 300)
        val DONE = longArrayOf(0, 600, 200, 200, 200, 600)
        val SPEED_UP = longArrayOf(0, 90, 90, 90)
        val SLOW_DOWN = longArrayOf(0, 400)
    }
}
