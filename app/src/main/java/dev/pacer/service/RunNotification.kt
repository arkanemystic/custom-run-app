package dev.pacer.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dev.pacer.MainActivity
import dev.pacer.R
import dev.pacer.core.RunSession
import dev.pacer.core.formatDuration
import dev.pacer.core.formatMiles
import dev.pacer.core.formatPace
import dev.pacer.core.formatRemaining

object RunNotification {
    const val ID = 1
    private const val CHANNEL = "run"

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Active run", NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                },
            )
        }
    }

    fun build(context: Context, title: String, s: RunSession.Snapshot?): Notification {
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val b = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_run)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_WORKOUT)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
        if (s == null) return b.setContentTitle(title).setContentText("Starting…").build()

        val interval = s.interval?.let {
            if (it.finished) "Workout complete" else "${it.interval!!.label} · ${formatRemaining(it.remainingMs)} left"
        }
        b.setContentTitle(
            "${formatDuration(s.movingMs)} · ${formatMiles(s.distanceM)} mi · ${formatPace(s.livePaceSec)}/mi" +
                if (s.paused) " · Paused" else "",
        )
        b.setContentText(interval ?: title)
        if (s.paused) {
            b.addAction(action(context, RunService.ACTION_RESUME, "Resume", 2))
        } else {
            b.addAction(action(context, RunService.ACTION_PAUSE, "Pause", 1))
        }
        b.addAction(action(context, RunService.ACTION_STOP, "Stop", 3))
        return b.build()
    }

    private fun action(context: Context, action: String, label: String, code: Int): Notification.Action {
        val pi = PendingIntent.getService(
            context, code, Intent(context, RunService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Action.Builder(null, label, pi).build()
    }
}
