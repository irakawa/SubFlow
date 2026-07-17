package com.subflow.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.subflow.R
import com.subflow.pipeline.PipelineRunner
import com.subflow.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class PipelineService : Service() {

    companion object {
        const val NOTIF_ID = 42
        const val CHANNEL_ID = "subflow_pipeline"
    }

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel_pipeline), NotificationManager.IMPORTANCE_LOW)
        )
        // collect once per instance. onStartCommand can fire repeatedly, launching the collector
        // there leaked a new one each time.
        scope.launch {
            PipelineRunner.currentSource.collect { source ->
                val text = source?.let { (name, i, total) ->
                    "$name · " + getString(R.string.notif_source, i, total)
                } ?: getString(R.string.notif_searching)
                getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // android 12+ throws if we start the fgs from the background
        try {
            startForeground(
                NOTIF_ID,
                buildNotification(getString(R.string.notif_searching)),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(text: String): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("SubFlow")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setContentIntent(contentIntent())
            .build()

    private fun contentIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}
