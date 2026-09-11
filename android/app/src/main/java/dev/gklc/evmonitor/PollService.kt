package dev.gklc.evmonitor

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

/**
 * Foreground service: keeps the process (and the BLE link + polling in Engine)
 * alive with the screen off or the app in the background.
 */
class PollService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val channelId = "evm_live"
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(channelId) == null) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "Live monitoring", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notif: Notification = Notification.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("evMonitor")
            .setContentText("Connected to BMS — monitoring")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else
            startForeground(1, notif)
        return START_STICKY
    }

    companion object {
        fun start(ctx: Context) {
            try { ctx.startForegroundService(Intent(ctx, PollService::class.java)) } catch (e: Exception) {}
        }
        fun stop(ctx: Context) {
            try { ctx.stopService(Intent(ctx, PollService::class.java)) } catch (e: Exception) {}
        }
    }
}
