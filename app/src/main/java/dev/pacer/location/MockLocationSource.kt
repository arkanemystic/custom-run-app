package dev.pacer.location

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.google.android.gms.location.LocationServices
import dev.pacer.core.Fix
import dev.pacer.core.GpxReader
import dev.pacer.core.METERS_PER_MILE
import dev.pacer.core.haversineMeters
import dev.pacer.core.offset
import java.io.File
import kotlin.math.PI
import kotlin.random.Random

/**
 * Debug testing aid: produces fixes without GPS so the whole flow (screen-off cues, recording,
 * GPX export) can be exercised at a desk. Replays [gpxFile] if it exists (honoring its timing),
 * otherwise runs laps of a 400 m loop at [paceSecPerMile]. Uses a main-thread Handler like the real
 * provider's callbacks, so with the screen off it relies on the run's wake lock exactly as GPS does.
 */
class MockLocationSource(
    context: Context,
    private val paceSecPerMile: Int,
    private val gpxFile: File,
) : LocationSource {
    private val handler = Handler(Looper.getMainLooper())
    private val rnd = Random(SystemClock.elapsedRealtime())
    private var listener: ((Fix) -> Unit)? = null
    private var mode = GpsMode.ACTIVE

    private val replay: List<GpxReader.Pt>? =
        gpxFile.takeIf { it.exists() }?.let { runCatching { GpxReader.parse(it.readText()) }.getOrNull() }?.takeIf { it.size >= 2 }
    private var replayIndex = 0

    // Synthetic loop, centered on the last known location when there is one (no new request).
    private var centerLat = 37.7694
    private var centerLon = -122.4862
    private var angle = 0.0
    private val radius = 400.0 / (2 * PI)
    private var lat = 0.0
    private var lon = 0.0

    init {
        @SuppressLint("MissingPermission")
        val last = runCatching { LocationServices.getFusedLocationProviderClient(context).lastLocation }.getOrNull()
        last?.addOnSuccessListener { l -> if (l != null && angle == 0.0) { centerLat = l.latitude; centerLon = l.longitude } }
        position()
    }

    private val tick = object : Runnable {
        override fun run() {
            val l = listener ?: return
            val delay = if (mode == GpsMode.ACTIVE) emitMoving(l) else emitStill(l)
            handler.postDelayed(this, delay)
        }
    }

    override fun start(mode: GpsMode, listener: (Fix) -> Unit) {
        handler.removeCallbacks(tick)
        this.mode = mode
        this.listener = listener
        handler.post(tick)
    }

    override fun stop() {
        handler.removeCallbacks(tick)
        listener = null
    }

    private fun emitMoving(l: (Fix) -> Unit): Long {
        replay?.let { pts ->
            val p = pts[replayIndex % pts.size]
            val next = pts[(replayIndex + 1) % pts.size]
            replayIndex++
            val t0 = p.timeMs
            val t1 = next.timeMs
            val dt = if (t0 != null && t1 != null && t1 > t0) (t1 - t0).coerceAtMost(5_000) else 1_000
            val speed = haversineMeters(p.lat, p.lon, next.lat, next.lon) / (dt / 1000.0)
            l(fix(p.lat, p.lon, speed, p.ele))
            return dt
        }
        val speed = METERS_PER_MILE / paceSecPerMile
        angle += speed / radius
        position()
        val (nLat, nLon) = offset(lat, lon, rnd.nextDouble(0.0, 1.5), rnd.nextDouble(0.0, 360.0))
        l(fix(nLat, nLon, speed + rnd.nextDouble(-0.1, 0.1), 15.0))
        return 1_000
    }

    private fun emitStill(l: (Fix) -> Unit): Long {
        val p = replay?.get(replayIndex % replay.size)
        l(fix(p?.lat ?: lat, p?.lon ?: lon, 0.0, p?.ele ?: 15.0))
        return mode.intervalMs
    }

    private fun position() {
        val (la, lo) = offset(centerLat, centerLon, radius, Math.toDegrees(angle))
        lat = la; lon = lo
    }

    private fun fix(la: Double, lo: Double, speed: Double, ele: Double?) = Fix(
        timeMs = SystemClock.elapsedRealtime(),
        wallTimeMs = System.currentTimeMillis(),
        lat = la, lon = lo, accuracyM = 4f,
        speedMps = speed.toFloat(), speedAccuracyMps = 0.3f, altitudeM = ele,
    )

}
