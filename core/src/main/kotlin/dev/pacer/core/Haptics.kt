package dev.pacer.core

/**
 * Vibration vocabulary shared by phone and watch. Each cue differs in rhythm, not just length,
 * so it can be told apart through a sleeve without looking:
 * speed up = rapid flutter, slow down = two slow heavy throbs, run = three sharp taps,
 * walk = one long buzz, warm-up/cool-down = long then short, mile = two quick taps.
 * [timings] alternate off/on in ms starting with an off; [amplitudes] are 0..255 per segment.
 */
enum class HapticPattern(val timings: LongArray, val amplitudes: IntArray) {
    TICK(longArrayOf(0, 45), intArrayOf(0, 255)),
    RUN(longArrayOf(0, 120, 90, 120, 90, 120), intArrayOf(0, 255, 0, 255, 0, 255)),
    WALK(longArrayOf(0, 650), intArrayOf(0, 200)),
    EASY(longArrayOf(0, 400, 160, 110), intArrayOf(0, 220, 0, 255)),
    SPEED_UP(longArrayOf(0, 60, 55, 60, 55, 60, 55, 60, 55, 60), intArrayOf(0, 255, 0, 255, 0, 255, 0, 255, 0, 255)),
    SLOW_DOWN(longArrayOf(0, 450, 450, 450), intArrayOf(0, 230, 0, 230)),
    MILE(longArrayOf(0, 90, 110, 90), intArrayOf(0, 255, 0, 255)),
    DONE(longArrayOf(0, 600, 180, 150, 180, 600), intArrayOf(0, 255, 0, 255, 0, 255)),
    ;

    companion object {
        fun forInterval(kind: IntervalKind) = when (kind) {
            IntervalKind.RUN -> RUN
            IntervalKind.WALK -> WALK
            else -> EASY
        }

        fun forCoaching(v: Coach.Verdict) = if (v == Coach.Verdict.SPEED_UP) SPEED_UP else SLOW_DOWN
    }
}
