package io.github.mangi.eta.agent.cloud

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import io.github.mangi.eta.R
import io.github.mangi.eta.data.repository.CloudSyncRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

internal class CloudSyncService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    private val syncLoop = object : Runnable {
        override fun run() {
            if (!running) return
            scope.launch {
                runCatching {
                    val cfg = CloudSyncRepository.config(applicationContext)
                    if (cfg.enabled && cfg.backgroundSync) {
                        CloudSyncRepository.syncNow(applicationContext)
                    } else {
                        stopSelf()
                    }
                }.onFailure {
                    runCatching { CloudSyncRepository.flushOutbox(applicationContext) }
                }
            }
            handler.postDelayed(this, SYNC_INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running) return START_STICKY
        running = true
        handler.post(syncLoop)
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(syncLoop)
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.cloud_sync_channel_name),
                    NotificationManager.IMPORTANCE_MIN,
                ),
            )
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.cloud_sync_notification_title))
            .setContentText(getString(R.string.cloud_sync_notification_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "eta_cloud_sync"
        private const val NOTIFICATION_ID = 4210
        private const val SYNC_INTERVAL_MS = 60_000L

        fun start(context: Context) {
            val intent = Intent(context, CloudSyncService::class.java)
            runCatching { context.startForegroundService(intent) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CloudSyncService::class.java))
        }
    }
}
