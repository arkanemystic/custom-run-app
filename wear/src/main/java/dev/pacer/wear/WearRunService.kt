package dev.pacer.wear

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings.Global
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import dev.pacer.core.Codec
import dev.pacer.core.Fix
import dev.pacer.core.Plan
import dev.pacer.core.RunArchive
import dev.pacer.core.RunSession
import dev.pacer.core.SyncConfig
import dev.pacer.core.TrackPoint
import dev.pacer.core.formatDuration
import dev.pacer.core.formatMiles
import dev.pacer.core.formatPace
import dev.pacer.core.formatRemaining
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface WearRunState {
    data object Idle : WearRunState
    data class Running(val plan: Plan, val snapshot: RunSession.Snapshot) : WearRunState
    data class Finished(val archive: RunArchive) : WearRunState
}

/**
 * The watch's run owner, the same design as the phone's RunService: 1 Hz GPS only while running,
 * a partial wake lock only while running (timed cues must fire with the screen off), ticks on whole
 * seconds of moving time, batched writes every 30 s. The run shows on the watch face through an
 * Ongoing Activity.
 */
class WearRunService : LifecycleService() {
    companion object {
        const val ACTION_START = "dev.pacer.wear.START"
        const val ACTION_PAUSE = "dev.pacer.wear.PAUSE"
        const val ACTION_RESUME = "dev.pacer.wear.RESUME"
        const val ACTION_STOP = "dev.pacer.wear.STOP"
        const val ACTION_RECOVER = "dev.pacer.wear.RECOVER"
        private const val EXTRA_PLAN = "plan"
        private const val CHANNEL = "run"
        private const val NOTIFICATION_ID = 1
        private const val FLUSH_MS = 30_000L
        private const val WAKE_TIMEOUT_MS = 10 * 60_000L
        private const val WAKE_RENEW_MS = 60_000L
        private const val STATUS_INTERACTIVE_MS = 5_000L
        private const val STATUS_SCREEN_OFF_MS = 30_000L

        private val _state = MutableStateFlow<WearRunState>(WearRunState.Idle)
        val state: StateFlow<WearRunState> = _state.asStateFlow()

        fun start(context: Context, plan: Plan) = context.startForegroundService(
            Intent(context, WearRunService::class.java).setAction(ACTION_START).putExtra(EXTRA_PLAN, Codec.encodePlan(plan)),
        )

        fun recover(context: Context) =
            context.startForegroundService(Intent(context, WearRunService::class.java).setAction(ACTION_RECOVER))

        fun send(context: Context, action: String) =
            context.startService(Intent(context, WearRunService::class.java).setAction(action))

        fun acknowledgeFinished() {
            if (_state.value is WearRunState.Finished) _state.value = WearRunState.Idle
        }
    }

    private val app get() = application as WearApp
    private var session: RunSession? = null
    private var meta: RunStore.ActiveMeta? = null
    private val unsaved = ArrayList<TrackPoint>()
    private val allPoints = ArrayList<TrackPoint>()
    private var cfg = SyncConfig(emptyList())
    private val location by lazy { WearLocation(this) }
    private val cues by lazy { WearCues(this) }
    private var tickJob: Job? = null
    private var flushJob: Job? = null
    private var lastStatusMs = 0L
    private var lastWakeRenewMs = 0L
    private var ongoing: OngoingActivity? = null
    private var notification: NotificationCompat.Builder? = null

    private val power by lazy { getSystemService(PowerManager::class.java) }
    private val wakeLock by lazy {
        power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Pacer:wearrun").apply { setReferenceCounted(false) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> if (session == null && enterForeground()) {
                begin(Codec.decodePlan(intent.getStringExtra(EXTRA_PLAN)!!))
            }
            ACTION_PAUSE -> pause()
            ACTION_RESUME -> resume()
            ACTION_STOP -> lifecycleScope.launch { stop() }
            else -> if (session == null && enterForeground()) recover()
        }
        if (session == null && intent?.action in listOf(ACTION_PAUSE, ACTION_RESUME)) stopSelf()
        return START_STICKY
    }

    private fun enterForeground(): Boolean {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return false
        }
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Active run", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, WearActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val nb = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_run)
            .setContentTitle("Pacer")
            .setContentText("Starting…")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setContentIntent(open)
        val oa = OngoingActivity.Builder(this, NOTIFICATION_ID, nb)
            .setStaticIcon(R.drawable.ic_stat_run)
            .setTouchIntent(open)
            .setStatus(status("Starting…"))
            .build()
        oa.apply(this)
        notification = nb
        ongoing = oa
        return try {
            startForeground(NOTIFICATION_ID, nb.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            true
        } catch (e: Exception) {
            Log.w("WearRunService", "Cannot enter foreground", e)
            stopSelf()
            false
        }
    }

    private fun status(text: String) = Status.Builder().addTemplate("#t#").addPart("t", Status.TextPart(text)).build()

    private fun begin(plan: Plan) {
        cfg = app.store.config()
        val now = SystemClock.elapsedRealtime()
        val s = RunSession(plan, now, cfg.coachSpacingSec * 1000L)
        session = s
        val m = RunStore.ActiveMeta(plan, plan.title, System.currentTimeMillis(), s.clock, bootCount())
        meta = m
        unsaved.clear()
        allPoints.clear()
        lifecycleScope.launch(Dispatchers.IO) { app.store.startActive(m) }
        location.start(GpsMode.ACTIVE, ::onFix)
        holdWakeLock()
        startLoops()
    }

    private fun recover() {
        val active = app.store.readActive()
        if (active == null) {
            finishService()
            return
        }
        val (m, points) = active
        cfg = app.store.config()
        if (m.bootCount != bootCount()) {
            // The clock base restarted with the watch; keep what was recorded and finish it.
            val last = points.lastOrNull()
            val a = RunArchive(
                m.title, m.plan, m.startWallMs, last?.wallTimeMs ?: m.startWallMs, last?.distanceM ?: 0.0,
                last?.movingMs ?: 0, (last?.wallTimeMs ?: m.startWallMs) - m.startWallMs, emptyList(), emptyList(), points,
            )
            lifecycleScope.launch { archive(a) }
            finishService()
            return
        }
        val now = SystemClock.elapsedRealtime()
        val s = RunSession.restore(m.plan, m.clock, points, now, cfg.coachSpacingSec * 1000L)
        session = s
        meta = m
        allPoints.clear()
        allPoints.addAll(points)
        if (s.clock.isPaused) {
            location.start(GpsMode.PAUSED, ::onFix)
        } else {
            s.pause(now)
            s.resume(now)
            location.start(GpsMode.ACTIVE, ::onFix)
            holdWakeLock()
        }
        startLoops()
    }

    private fun onFix(fix: Fix) {
        val s = session ?: return
        val events = s.onFix(fix)
        val pts = s.drainPoints()
        unsaved += pts
        allPoints += pts
        if (events.isNotEmpty()) cues.handle(events, cfg)
    }

    private fun startLoops() {
        tickJob?.cancel()
        tickJob = lifecycleScope.launch {
            while (isActive) {
                val s = session ?: break
                val now = SystemClock.elapsedRealtime()
                val events = s.tick(now)
                if (events.isNotEmpty()) cues.handle(events, cfg)
                publish(now, force = events.isNotEmpty())
                if (!s.clock.isPaused && now - lastWakeRenewMs > WAKE_RENEW_MS) holdWakeLock()
                delay(if (s.clock.isPaused) 1000 else 1000 - s.clock.movingMs(now) % 1000)
            }
        }
        flushJob?.cancel()
        flushJob = lifecycleScope.launch {
            while (isActive) {
                delay(FLUSH_MS)
                flush()
            }
        }
    }

    private fun publish(now: Long, force: Boolean) {
        val s = session ?: return
        val snap = s.snapshot(now)
        _state.value = WearRunState.Running(s.plan, snap)
        // The watch face chip and notification: rarely with the screen off, nobody is looking.
        val every = if (power.isInteractive) STATUS_INTERACTIVE_MS else STATUS_SCREEN_OFF_MS
        if (force || now - lastStatusMs >= every) {
            lastStatusMs = now
            val iv = snap.interval?.takeUnless { it.finished }?.let { "${it.interval!!.label} ${formatRemaining(it.remainingMs)} · " } ?: ""
            val text = iv + "${formatPace(snap.livePaceSec)}/mi · ${formatMiles(snap.distanceM)} mi" + if (snap.paused) " · Paused" else ""
            ongoing?.update(this, status(text))
            notification?.let {
                it.setContentTitle(formatDuration(snap.movingMs)).setContentText(text)
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, it.build())
            }
        }
    }

    private suspend fun flush() {
        val s = session ?: return
        val m = meta ?: return
        val pts = ArrayList(unsaved)
        unsaved.clear()
        val updated = m.copy(clock = s.clock)
        meta = updated
        withContext(Dispatchers.IO) {
            app.store.appendPoints(pts)
            app.store.writeMeta(updated)
        }
    }

    private fun pause() {
        val s = session ?: return
        if (s.clock.isPaused) return
        val now = SystemClock.elapsedRealtime()
        s.pause(now)
        location.start(GpsMode.PAUSED, ::onFix)
        releaseWakeLock()
        publish(now, force = true)
        lifecycleScope.launch { flush() }
    }

    private fun resume() {
        val s = session ?: return
        if (!s.clock.isPaused) return
        val now = SystemClock.elapsedRealtime()
        s.resume(now)
        location.start(GpsMode.ACTIVE, ::onFix)
        holdWakeLock()
        publish(now, force = true)
    }

    private suspend fun stop() {
        val s = session ?: return finishService()
        val m = meta ?: return finishService()
        location.stop()
        tickJob?.cancel()
        flushJob?.cancel()
        val now = SystemClock.elapsedRealtime()
        if (!s.clock.isPaused) s.pause(now)
        val a = RunArchive.fromSession(s, m.title, m.startWallMs, System.currentTimeMillis(), now, ArrayList(allPoints))
        session = null
        archive(a)
        _state.value = WearRunState.Finished(a)
        finishService()
    }

    /** Moves a finished run to the outbox and queues it for the phone. */
    private suspend fun archive(a: RunArchive) {
        val file = withContext(Dispatchers.IO) {
            app.store.addToOutbox(a).also { app.store.clearActive() }
        }
        app.sync.send(file)
    }

    private fun finishService() {
        location.stop()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    @SuppressLint("WakelockTimeout") // Has one, renewed each minute while running.
    private fun holdWakeLock() {
        wakeLock.acquire(WAKE_TIMEOUT_MS)
        lastWakeRenewMs = SystemClock.elapsedRealtime()
    }

    private fun releaseWakeLock() {
        if (wakeLock.isHeld) wakeLock.release()
    }

    private fun bootCount() = Global.getInt(contentResolver, Global.BOOT_COUNT, 0)

    override fun onDestroy() {
        location.stop()
        releaseWakeLock()
        cues.release()
        super.onDestroy()
    }
}
