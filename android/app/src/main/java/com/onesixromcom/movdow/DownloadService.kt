package com.onesixromcom.movdow

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch

/** Keeps the process alive while the queue is downloading and shows the progress notification. */
class DownloadService : Service() {

    companion object {
        private const val CHANNEL_ID = "downloads"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
        }
    }

    private val scope = MainScope()
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        DownloadManager.init(this)
        AppLog.i("Download service started")

        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW),
        )
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification("Starting…", null, 0, 0),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MovDow:download")
            .apply { acquire(6 * 60 * 60 * 1000L) }

        scope.launch {
            combine(DownloadManager.items, DownloadManager.paused) { items, paused -> items to paused }
                .conflate()
                .collect { (items, paused) ->
                    val active = items.firstOrNull { it.status == DownloadStatus.DOWNLOADING }
                    val queued = items.count { it.status == DownloadStatus.QUEUED }
                    if (active == null && (paused || queued == 0)) {
                        stopSelf()
                        return@collect
                    }
                    val title = active?.title ?: "Starting…"
                    val text = listOfNotNull(
                        active?.stage ?: active?.let { "${it.done} / ${it.total} segments" },
                        if (queued > 0) "$queued in queue" else null,
                    ).joinToString(" · ")
                    notifications.notify(
                        NOTIFICATION_ID,
                        notification(title, text, active?.done ?: 0, active?.total ?: 0),
                    )
                    // Android drops too frequent notification updates.
                    delay(1000)
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    // Android 15 limits dataSync services to 6h a day.
    override fun onTimeout(startId: Int, fgsType: Int) {
        AppLog.e("Android background download limit reached (6h a day), queue paused")
        DownloadManager.setPaused(true)
        stopSelf()
    }

    override fun onDestroy() {
        AppLog.i("Download service stopped")
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    private fun notification(title: String, text: String?, done: Int, total: Int): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(total, done, total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp)
            .build()
    }
}
