package dev.asl.app.terminal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import dev.asl.app.MainActivity
import dev.asl.app.R

/**
 * Foreground service that exists only while terminal sessions exist. It keeps the process at foreground
 * priority so Android is less likely to kill running shells. It does NOT guarantee indefinite execution:
 * Android and OEMs may still stop it (battery limits, phantom-process killer, user force-stop).
 */
class TerminalService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_EXIT) {
            SessionManager.closeAll(this)
            stopSelf()
            return START_NOT_STICKY
        }
        val count = SessionManager.sessions.value.size
        val notification = buildNotification(count)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(count: Int): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Terminal sessions", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while ASL terminal sessions are running"
            },
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val exit = PendingIntent.getService(
            this, 1, Intent(this, TerminalService::class.java).setAction(ACTION_EXIT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_terminal)
            .setContentTitle("ASL")
            .setContentText(if (count == 1) "1 terminal session running" else "$count terminal sessions running")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Close all", exit).build())
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "sessions"
        private const val NOTIFICATION_ID = 1
        const val ACTION_EXIT = "dev.asl.app.EXIT"
    }
}
