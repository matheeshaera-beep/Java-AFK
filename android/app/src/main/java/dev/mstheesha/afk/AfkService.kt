package dev.mstheesha.afk

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class AfkService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private val scope = MainScope()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "AFK session", NotificationManager.IMPORTANCE_LOW),
            )
        }
        // Rebuild the notification whenever the active-server set changes.
        scope.launch {
            AppGraph.namesTick.collect { refreshNotification() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // Force-stop: disconnect every session first so nothing is left
            // showing a stale Connected state, then drop the foreground
            // notification, release the wake lock, and stop the service.
            // (The app window itself stays open; it is only a viewer.)
            AppGraph.stopAllSessions()
            stopForeground(STOP_FOREGROUND_REMOVE)
            releaseWakeLock()
            stopSelf()
        } else {
            startForegroundCompat()
        }
        return START_STICKY
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        acquireWakeLock()
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopService = PendingIntent.getService(
            this, 1,
            Intent(this, AfkService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val names = AppGraph.activeServerNames()
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Java AFK active")
            .setContentText(
                if (names.isEmpty()) "Preparing session…"
                else names.joinToString(", "),
            )
            .setSmallIcon(R.drawable.ic_stat_afk)
            .setContentIntent(openApp)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    "Stop service",
                    stopService,
                ).build(),
            )
            .build()
    }

    private fun refreshNotification() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "afk:session").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    override fun onDestroy() {
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "afk_channel"
        const val NOTIFICATION_ID = 1
        const val ACTION_START = "dev.mstheesha.afk.START"
        const val ACTION_STOP = "dev.mstheesha.afk.STOP"
    }
}