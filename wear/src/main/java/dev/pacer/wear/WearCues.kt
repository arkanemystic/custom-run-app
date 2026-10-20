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
import dev.pacer.core.HapticPattern
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
            is Event.Countdown -> { say("${e.n}", cfg); buzz(HapticPattern.TICK, cfg) }
            is Event.IntervalStarted -> {
                say(Speech.intervalStart(e.interval), cfg)
                buzz(HapticPattern.forInterval(e.interval.kind), cfg)
            }
            Event.WorkoutFinished -> { say(Speech.WORKOUT_DONE, cfg); buzz(HapticPattern.DONE, cfg) }
            is Event.Coaching -> if (cfg.coaching) {
                say(Speech.coach(e.cue), cfg)
                buzz(HapticPattern.forCoaching(e.cue.verdict), cfg)
            }
            is Event.MileSplit -> if (cfg.mileCues) {
                say(Speech.mile(e.mile, e.splitMs / 1000.0, e.avgPaceSec), cfg)
                buzz(HapticPattern.MILE, cfg)
            }
        }
    }

    private fun buzz(p: HapticPattern, cfg: SyncConfig) {
        if (!cfg.haptics) return
        vibrator.vibrate(
            if (vibrator.hasAmplitudeControl()) VibrationEffect.createWaveform(p.timings, p.amplitudes, -1)
            else VibrationEffect.createWaveform(p.timings, -1),
        )
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
}
