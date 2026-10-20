package dev.pacer.wear

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Build
import android.os.Looper
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dev.pacer.core.Fix

/** Same power modes as the phone: full GPS only while running. */
enum class GpsMode(val priority: Int, val intervalMs: Long, val maxDelayMs: Long) {
    SETUP(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 5_000, 0),
    ACTIVE(Priority.PRIORITY_HIGH_ACCURACY, 1_000, 0),
    PAUSED(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 10_000, 30_000),
}

/**
 * Fused location on the watch. With the phone nearby Wear OS may take fixes from the phone,
 * which saves watch battery; away from it, the watch's own GPS is used.
 */
class WearLocation(context: Context) {
    private val client = LocationServices.getFusedLocationProviderClient(context.applicationContext)
    private var callback: LocationCallback? = null

    @SuppressLint("MissingPermission") // Callers check ACCESS_FINE_LOCATION first.
    fun start(mode: GpsMode, listener: (Fix) -> Unit) {
        stop()
        val request = LocationRequest.Builder(mode.priority, mode.intervalMs)
            .setMinUpdateIntervalMillis(mode.intervalMs)
            .setMaxUpdateDelayMillis(mode.maxDelayMs)
            .build()
        val cb = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.locations.forEach { listener(it.toFix()) }
            }
        }
        callback = cb
        client.requestLocationUpdates(request, cb, Looper.getMainLooper())
    }

    fun stop() {
        callback?.let { client.removeLocationUpdates(it) }
        callback = null
    }
}

private fun Location.toFix() = Fix(
    timeMs = elapsedRealtimeNanos / 1_000_000,
    wallTimeMs = time,
    lat = latitude,
    lon = longitude,
    accuracyM = if (hasAccuracy()) accuracy else Float.MAX_VALUE,
    speedMps = if (hasSpeed()) speed else null,
    speedAccuracyMps = if (hasSpeedAccuracy()) speedAccuracyMetersPerSecond else null,
    altitudeM = when {
        Build.VERSION.SDK_INT >= 34 && hasMslAltitude() -> mslAltitudeMeters
        hasAltitude() -> altitude
        else -> null
    },
)
