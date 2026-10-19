package dev.pacer.service

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings.Global
import android.util.Log
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dev.pacer.container
import dev.pacer.core.Codec
import dev.pacer.core.Fix
import dev.pacer.core.Plan
import dev.pacer.core.RunSession
import dev.pacer.core.TrackPoint
import dev.pacer.data.Settings
import dev.pacer.data.clock
import dev.pacer.data.decodedPlan
import dev.pacer.location.GpsMode
import dev.pacer.location.LocationSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Owns an active run: GPS, the once-per-second interval/cue tick, the wake lock, the ongoing
 * notification and batched persistence. It is the single source of truth ([state]); the UI only
 * observes. Everything runs on the main thread, so [RunSession] needs no locking.
 */
class RunService : LifecycleService() {

    companion object {
        const val ACTION_START = "dev.pacer.START"
        const val ACTION_PAUSE = "dev.pacer.PAUSE"
        const val ACTION_RESUME = "dev.pacer.RESUME"
        const val ACTION_STOP = "dev.pacer.STOP"
        const val ACTION_RECOVER = "dev.pacer.RECOVER"
        private const val EXTRA_PLAN = "plan"
        private const val TAG = "RunService"

        private const val FLUSH_INTERVAL_MS = 30_000L
        private const val WAKE_TIMEOUT_MS = 10 * 60_000L
        private const val WAKE_RENEW_MS = 60_000L
        private const val SCREEN_OFF_NOTIFY_MS = 15_000L

        private val _state = MutableStateFlow<RunState>(RunState.Idle)
        val state: StateFlow<RunState> = _state.asStateFlow()

        /** The recorded route for the map. Main thread only; grows while a run is active. */
        val track: MutableList<TrackPoint> = ArrayList()

        fun start(context: Context, plan: Plan) {
            context.startForegroundService(
                Intent(context, RunService::class.java).setAction(ACTION_START).putExtra(EXTRA_PLAN, Codec.encodePlan(plan)),
            )
        }

        /** Called from the foreground UI when the database holds a run that no service owns. */
        fun recover(context: Context) {
            context.startForegroundService(Intent(context, RunService::class.java).setAction(ACTION_RECOVER))
        }

        fun send(context: Context, action: String) {
            context.startService(Intent(context, RunService::class.java).setAction(action))
        }

        /** The summary screen has taken over a finished run. */
        fun acknowledgeFinished() {
            if (_state.value is RunState.Finished) _state.value = RunState.Idle
        }
    }

    private var session: RunSession? = null
    private var runId = 0L
    private var title = ""
    private val unsaved = ArrayList<TrackPoint>()
    private var settings = Settings()
    private var location: LocationSource? = null
    private var cues: Cues = Cues.None
    private var tickJob: Job? = null
    private var flushJob: Job? = null
    private var lastNotifyMs = 0L
    private var lastWakeRenewMs = 0L

    private val power by lazy { getSystemService(PowerManager::class.java) }
    private val wakeLock by lazy {
        power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Pacer:run").apply { setReferenceCounted(false) }
    }

    override fun onCreate() {
        super.onCreate()
        RunNotification.ensureChannel(this)
        cues = container.createCues(this)
        lifecycleScope.launch {
            container.settings.settings.collect {
                settings = it
                session?.coachSpacingMs = it.coachSpacingSec * 1000L
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> if (session == null && enterForeground()) {
                val plan = Codec.decodePlan(intent.getStringExtra(EXTRA_PLAN)!!)
                lifecycleScope.launch { begin(plan) }
            }
            ACTION_PAUSE -> pause()
            ACTION_RESUME -> resume()
            ACTION_STOP -> lifecycleScope.launch { stop() }
            // ACTION_RECOVER, or a sticky restart after the process was killed.
            else -> if (session == null && enterForeground()) lifecycleScope.launch { recover() }
        }
        if (session == null && intent?.action in listOf(ACTION_PAUSE, ACTION_RESUME)) stopSelf()
        return START_STICKY
    }

    private fun enterForeground(): Boolean {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return false
        }
        return try {
            startForeground(
                RunNotification.ID, RunNotification.build(this, "Pacer", null),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
            true
        } catch (e: Exception) {
            // E.g. a sticky restart while the app is in the background, which Android forbids for
            // location services. The run stays in the database and is recovered on next launch.
            Log.w(TAG, "Cannot enter foreground", e)
            stopSelf()
            false
        }
    }

    private suspend fun begin(plan: Plan) {
        val now = SystemClock.elapsedRealtime()
        val s = RunSession(plan, now, settings.coachSpacingSec * 1000L)
        session = s
        title = plan.title
        track.clear()
        unsaved.clear()
        location?.stop()
        location = null // Re-created from current settings (real or debug mock GPS).
        runId = container.runs.create(plan, System.currentTimeMillis(), s.clock, bootCount())
        startGps(GpsMode.ACTIVE)
        holdWakeLock()
        startLoops()
    }

    private suspend fun recover() {
        val r = container.runs.active()
        if (r == null) {
            finishService()
            return
        }
        if (r.bootCount != bootCount()) {
            // elapsedRealtime restarted with the device, so the clock can't be trusted. Keep what we have.
            container.runs.finishAbandoned(r)
            _state.value = RunState.Finished(r.id)
            finishService()
            return
        }
        val now = SystemClock.elapsedRealtime()
        val points = container.runs.points(r.id)
        val s = RunSession.restore(r.decodedPlan(), r.clock(), points, now, settings.coachSpacingSec * 1000L)
        session = s
        runId = r.id
        title = r.title
        track.clear()
        track.addAll(points)
        unsaved.clear()
        if (s.clock.isPaused) {
            startGps(GpsMode.PAUSED)
        } else {
            // Points from the gap are lost; the filter starts a new segment so it isn't counted as distance.
            s.pause(now)
            s.resume(now)
            startGps(GpsMode.ACTIVE)
            holdWakeLock()
        }
        startLoops()
    }

    private fun startGps(mode: GpsMode) {
        val src = location ?: container.createLocationSource(this, settings).also { location = it }
        src.start(mode, ::onFix)
    }

    private fun onFix(fix: Fix) {
        val s = session ?: return
        val events = s.onFix(fix)
        val pts = s.drainPoints()
        if (pts.isNotEmpty()) {
            track += pts
            unsaved += pts
        }
        if (events.isNotEmpty()) cues.handle(events, s, SystemClock.elapsedRealtime(), settings)
    }

    private fun startLoops() {
        tickJob?.cancel()
        tickJob = lifecycleScope.launch {
            while (isActive) {
                val s = session ?: break
                val now = SystemClock.elapsedRealtime()
                val events = s.tick(now)
                if (events.isNotEmpty()) cues.handle(events, s, now, settings)
                publish(now, force = events.isNotEmpty())
                if (!s.clock.isPaused && now - lastWakeRenewMs > WAKE_RENEW_MS) holdWakeLock()
                // Wake exactly on the next whole second of moving time so cues land on schedule.
                val delayMs = if (s.clock.isPaused) 1000 else 1000 - s.clock.movingMs(now) % 1000
                delay(delayMs)
            }
        }
        flushJob?.cancel()
        flushJob = lifecycleScope.launch {
            while (isActive) {
                delay(FLUSH_INTERVAL_MS)
                flush()
            }
        }
    }

    private fun publish(now: Long, force: Boolean = false) {
        val s = session ?: return
        val snap = s.snapshot(now)
        val fix = s.lastFix
        _state.value = RunState.Running(runId, s.plan, snap, fix?.lat, fix?.lon, track.size)
        // Nobody can see the notification with the screen off, so refresh it rarely then.
        if (force || power.isInteractive || now - lastNotifyMs >= SCREEN_OFF_NOTIFY_MS) {
            lastNotifyMs = now
            getSystemService(android.app.NotificationManager::class.java)
                .notify(RunNotification.ID, RunNotification.build(this, title, snap))
        }
    }

    private suspend fun flush() {
        val s = session ?: return
        if (runId == 0L) return
        val pts = ArrayList(unsaved)
        unsaved.clear()
        container.runs.checkpoint(runId, s, SystemClock.elapsedRealtime(), pts)
    }

    private fun pause() {
        val s = session ?: return
        if (s.clock.isPaused) return
        val now = SystemClock.elapsedRealtime()
        s.pause(now)
        startGps(GpsMode.PAUSED)
        releaseWakeLock()
        publish(now, force = true)
        lifecycleScope.launch { flush() }
    }

    private fun resume() {
        val s = session ?: return
        if (!s.clock.isPaused) return
        val now = SystemClock.elapsedRealtime()
        s.resume(now)
        startGps(GpsMode.ACTIVE)
        holdWakeLock()
        publish(now, force = true)
    }

    private suspend fun stop() {
        val s = session ?: return finishService()
        location?.stop()
        tickJob?.cancel()
        flushJob?.cancel()
        val now = SystemClock.elapsedRealtime()
        if (!s.clock.isPaused) s.pause(now)
        val pts = ArrayList(unsaved)
        unsaved.clear()
        if (runId != 0L) container.runs.finish(runId, s, now, System.currentTimeMillis(), pts)
        session = null
        _state.value = RunState.Finished(runId)
        finishService()
    }

    private fun finishService() {
        location?.stop()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    @SuppressLint("WakelockTimeout") // It has one: WAKE_TIMEOUT_MS, renewed each minute while running.
    private fun holdWakeLock() {
        wakeLock.acquire(WAKE_TIMEOUT_MS)
        lastWakeRenewMs = SystemClock.elapsedRealtime()
    }

    private fun releaseWakeLock() {
        if (wakeLock.isHeld) wakeLock.release()
    }

    private fun bootCount() = Global.getInt(contentResolver, Global.BOOT_COUNT, 0)

    override fun onDestroy() {
        location?.stop()
        releaseWakeLock()
        cues.release()
        super.onDestroy()
    }
}
