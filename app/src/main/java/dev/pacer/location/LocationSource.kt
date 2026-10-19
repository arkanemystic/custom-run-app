package dev.pacer.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dev.pacer.core.Fix

/**
 * GPS power modes. Only ACTIVE uses high accuracy at 1 Hz; everything else is balanced and slow.
 * PAUSED allows batched delivery so the radio and CPU can sleep between fixes.
 */
enum class GpsMode(val priority: Int, val intervalMs: Long, val maxDelayMs: Long) {
    SETUP(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 5_000, 0),
    ACTIVE(Priority.PRIORITY_HIGH_ACCURACY, 1_000, 0),
    PAUSED(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 10_000, 30_000),
}

interface LocationSource {
    /** Replaces any previous request. Callbacks arrive on the main thread. */
    fun start(mode: GpsMode, listener: (Fix) -> Unit)
    fun stop()
}

class FusedLocationSource(context: Context) : LocationSource {
    private val client = LocationServices.getFusedLocationProviderClient(context.applicationContext)
    private var callback: LocationCallback? = null

    @SuppressLint("MissingPermission") // Callers check ACCESS_FINE_LOCATION first.
    override fun start(mode: GpsMode, listener: (Fix) -> Unit) {
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

    override fun stop() {
        callback?.let { client.removeLocationUpdates(it) }
        callback = null
    }
}

fun Location.toFix() = Fix(
    timeMs = elapsedRealtimeNanos / 1_000_000,
    wallTimeMs = time,
    lat = latitude,
    lon = longitude,
    accuracyM = if (hasAccuracy()) accuracy else Float.MAX_VALUE,
    speedMps = if (hasSpeed()) speed else null,
    speedAccuracyMps = if (hasSpeedAccuracy()) speedAccuracyMetersPerSecond else null,
    altitudeM = when {
        hasMslAltitude() -> mslAltitudeMeters
        hasAltitude() -> altitude
        else -> null
    },
)
