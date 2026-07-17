package com.subflow.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.subflow.R
import com.subflow.pipeline.PipelineRunner
import com.subflow.ui.MainActivity
import com.subflow.utils.FileUtils

/**
 * "subtitles ready" notification with Save/Share, posted after a background run.
 * separate from the foreground-service notification that dies when the run ends.
 */
object ResultNotifier {

    const val NOTIF_ID = 43
    private const val CHANNEL_ID = "subflow_results"

    fun notifyReady(context: Context, count: Int) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.notif_ready), NotificationManager.IMPORTANCE_DEFAULT)
        )
        nm.notify(NOTIF_ID, build(context, count))
    }

    private fun build(context: Context, count: Int): Notification {
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.notif_ready))
            .setContentText(context.getString(R.string.notif_ready_body, count))
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(action(context, NotificationActionReceiver.ACTION_SAVE, R.string.notif_action_save, 1))
            .addAction(action(context, NotificationActionReceiver.ACTION_SHARE, R.string.notif_action_share, 2))
            .build()
    }

    private fun action(context: Context, act: String, labelRes: Int, req: Int): Notification.Action {
        val pi = PendingIntent.getBroadcast(
            context, req,
            Intent(context, NotificationActionReceiver::class.java).setAction(act),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Action.Builder(null, context.getString(labelRes), pi).build()
    }
}

class NotificationActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_SAVE = "com.subflow.action.SAVE"
        const val ACTION_SHARE = "com.subflow.action.SHARE"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val results = PipelineRunner.results.value
        if (results.isEmpty()) {
            context.getSystemService(NotificationManager::class.java).cancel(ResultNotifier.NOTIF_ID)
            return
        }
        when (intent.action) {
            ACTION_SAVE -> {
                // save off the main thread, onReceive can't do disk I/O inline
                val pending = goAsync()
                Thread {
                    try {
                        results.forEach { FileUtils.saveToDownloads(context, it.fileName, it.content) }
                        updateToSaved(context)
                    } finally {
                        pending.finish()
                    }
                }.start()
            }
            ACTION_SHARE -> {
                // write share files off the main thread, then launch the chooser
                val pending = goAsync()
                Thread {
                    try {
                        val uris = FileUtils.writeShareFiles(context, results.map { it.fileName to it.content })
                        FileUtils.fireShareMultiple(context, uris)
                        context.getSystemService(NotificationManager::class.java).cancel(ResultNotifier.NOTIF_ID)
                    } finally {
                        pending.finish()
                    }
                }.start()
            }
        }
    }

    private fun updateToSaved(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        // recreate the channel in case this runs in a cold process
        nm.createNotificationChannel(
            NotificationChannel("subflow_results", context.getString(R.string.notif_ready), NotificationManager.IMPORTANCE_DEFAULT)
        )
        val n = Notification.Builder(context, "subflow_results")
            .setContentTitle(context.getString(R.string.notif_ready))
            .setContentText(context.getString(R.string.notif_saved))
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .build()
        nm.notify(ResultNotifier.NOTIF_ID, n)
    }
}
