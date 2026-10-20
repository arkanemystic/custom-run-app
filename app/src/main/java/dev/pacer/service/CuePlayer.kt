package dev.pacer.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dev.pacer.core.HapticPattern
import dev.pacer.core.RunSession
import dev.pacer.core.RunSession.Event
import dev.pacer.core.Speech
import dev.pacer.data.Settings
import java.util.Locale

/**
 * Voice through TextToSpeech with transient ducking focus (music dips, then comes back as soon as
 * the queue is empty), and distinct vibration patterns. Voice, haptics and coaching are toggled
 * independently.
 */
class CuePlayer(context: Context) : Cues {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val vibrator: Vibrator = context.getSystemService(VibratorManager::class.java).defaultVibrator

    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attrs)
        .setOnAudioFocusChangeListener { }
        .build()

    private var ready = false
    private val waiting = ArrayList<String>()
    private var pending = 0
    private var nextId = 0
    private val lock = Any()

    private val tts = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) onReady()
    }

    private fun onReady() {
        tts.language = Locale.US
        tts.setAudioAttributes(attrs)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) = Unit
            override fun onDone(id: String?) = finished()
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) = finished()
            override fun onStop(id: String?, interrupted: Boolean) = finished()
        })
        val queued = synchronized(lock) { ready = true; ArrayList(waiting).also { waiting.clear() } }
        queued.forEach(::speak)
    }

    /** Release focus once nothing is left to say, so music returns to full volume right away. */
    private fun finished() {
        val idle = synchronized(lock) { pending = (pending - 1).coerceAtLeast(0); pending == 0 }
        if (idle) audio.abandonAudioFocusRequest(focus)
    }

    private fun speak(text: String) {
        synchronized(lock) {
            if (!ready) { waiting += text; return }
            pending++
        }
        audio.requestAudioFocus(focus)
        tts.speak(text, TextToSpeech.QUEUE_ADD, Bundle(), "c${nextId++}")
    }

    override fun handle(events: List<Event>, session: RunSession, nowMs: Long, settings: Settings) {
        for (e in events) {
            when (e) {
                is Event.Countdown -> {
                    if (settings.voice) speak("${e.n}")
                    if (settings.haptics) vibrate(HapticPattern.TICK)
                }
                is Event.IntervalStarted -> {
                    if (settings.voice) speak(Speech.intervalStart(e.interval))
                    if (settings.haptics) vibrate(HapticPattern.forInterval(e.interval.kind))
                }
                Event.WorkoutFinished -> {
                    if (settings.voice) speak(Speech.WORKOUT_DONE)
                    if (settings.haptics) vibrate(HapticPattern.DONE)
                }
                is Event.Coaching -> if (settings.coaching) {
                    if (settings.voice) speak(Speech.coach(e.cue))
                    if (settings.haptics) vibrate(HapticPattern.forCoaching(e.cue.verdict))
                }
                is Event.MileSplit -> if (settings.mileCues) {
                    if (settings.voice) speak(Speech.mile(e.mile, e.splitMs / 1000.0, e.avgPaceSec))
                    if (settings.haptics) vibrate(HapticPattern.MILE)
                }
            }
        }
    }

    private fun vibrate(p: HapticPattern) = vibrator.vibrate(
        if (vibrator.hasAmplitudeControl()) VibrationEffect.createWaveform(p.timings, p.amplitudes, -1)
        else VibrationEffect.createWaveform(p.timings, -1),
    )

    override fun release() {
        tts.stop()
        tts.shutdown()
        audio.abandonAudioFocusRequest(focus)
    }
}
