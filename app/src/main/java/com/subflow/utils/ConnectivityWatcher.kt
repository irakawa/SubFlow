package com.subflow.utils

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.subflow.R
import com.subflow.data.SearchQueue
import com.subflow.ui.MainActivity

/**
 * On reconnect, if the queue isn't empty, posts a notification to run it.
 * We don't auto-start a foreground service from the background (Android 12+
 * forbids it), the user taps to run in-app.
 */
object ConnectivityWatcher {

    private const val CHANNEL_ID = "subflow_queue"
    private const val NOTIF_ID = 44

    fun start(context: Context) {
        val appContext = context.applicationContext
        val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return
        try {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (SearchQueue.size > 0 && !AppForeground.isForeground) {
                        notifyQueueReady(appContext, SearchQueue.size)
                    }
                }
            })
        } catch (e: Exception) {
            // connectivity monitoring is best-effort
        }
    }

    fun isOnline(context: Context): Boolean = try {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    } catch (e: Exception) {
        true // if the check itself fails, let the caller try
    }

    /**
     * true when the active network charges for or counts traffic (mobile data, or a
     * hotspot the user marked as metered). Unknown state reads as metered: a wrong
     * "free" answer spends the user's money, a wrong "metered" one only costs a retry.
     */
    fun isMetered(context: Context): Boolean = try {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        if (caps == null) true
        else !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    } catch (e: Exception) {
        true
    }

    private fun notifyQueueReady(context: Context, count: Int) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.queue_run), NotificationManager.IMPORTANCE_DEFAULT)
        )
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        nm.notify(
            NOTIF_ID,
            Notification.Builder(context, CHANNEL_ID)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(context.getString(R.string.queue_notif, count))
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setAutoCancel(true)
                .setContentIntent(open)
                .build()
        )
    }
}
