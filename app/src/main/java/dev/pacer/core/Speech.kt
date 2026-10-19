package dev.pacer.core

import kotlin.math.roundToInt

/** Text for spoken cues. Words chosen to read naturally through Android TextToSpeech. */
object Speech {
    fun duration(sec: Int): String {
        val m = sec / 60
        val s = sec % 60
        val parts = buildList {
            if (m > 0) add(if (m == 1) "one minute" else "$m minutes")
            if (s > 0) add("$s seconds")
        }
        return parts.joinToString(" ").ifEmpty { "0 seconds" }
    }

    /** "ten minutes per mile", "nine forty five per mile". */
    fun targetPace(sec: Int): String {
        val m = sec / 60
        val s = sec % 60
        return if (s == 0) "${minutes(m)} per mile" else "${clock(m, s)} per mile"
    }

    /** Short form for a measured pace: "ten thirty two", "nine oh five". */
    fun pace(secPerMile: Double): String {
        val t = secPerMile.roundToInt()
        return clock(t / 60, t % 60)
    }

    fun intervalStart(iv: Interval): String {
        val name = when (iv.kind) {
            IntervalKind.WARMUP -> "Warm up"
            IntervalKind.RUN -> "Run"
            IntervalKind.WALK -> "Walk"
            IntervalKind.COOLDOWN -> "Cool down"
            IntervalKind.FREE -> "Free run"
        }
        val target = iv.targetPaceSec?.let { ", target ${targetPace(it)}" } ?: ""
        return "$name, ${duration(iv.durationSec)}$target"
    }

    fun coach(cue: Coach.Cue): String = when (cue.verdict) {
        Coach.Verdict.SPEED_UP -> "Speed up. ${pace(cue.paceSec)}"
        Coach.Verdict.SLOW_DOWN -> "Slow down. ${pace(cue.paceSec)}"
    }

    fun mile(n: Int, splitSec: Double, avgSec: Double?): String {
        val avg = avgSec?.let { ". Average ${pace(it)}" } ?: ""
        return "Mile $n. Split ${pace(splitSec)}$avg"
    }

    const val WORKOUT_DONE = "Workout complete"

    private fun minutes(m: Int) = if (m == 1) "one minute" else "${words(m)} minutes"

    private fun clock(m: Int, s: Int): String = when {
        s == 0 -> "${words(m)} flat"
        s < 10 -> "${words(m)} oh ${words(s)}"
        else -> "${words(m)} ${words(s)}"
    }

    private val ones = listOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
        "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen",
    )
    private val tens = listOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")

    fun words(n: Int): String = when {
        n < 20 -> ones[n]
        n < 100 -> tens[n / 10] + if (n % 10 != 0) " " + ones[n % 10] else ""
        else -> n.toString()
    }
}
