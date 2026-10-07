package com.gohsd.app

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object Notifier {
    const val CH_ALERT = "gohsd_alerts"
    const val CH_MONITOR = "gohsd_monitor"
    const val FOREGROUND_ID = 1

    fun createChannels(ctx: Context) {
        val nm = NotificationManagerCompat.from(ctx)
        nm.createNotificationChannel(
            NotificationChannelCompat.Builder(CH_ALERT, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName(ctx.getString(R.string.ch_alerts)).build()
        )
        nm.createNotificationChannel(
            NotificationChannelCompat.Builder(CH_MONITOR, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(ctx.getString(R.string.ch_monitor)).build()
        )
    }

    private fun openApp(ctx: Context) = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, RouterActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun foreground(ctx: Context): Notification =
        NotificationCompat.Builder(ctx, CH_MONITOR)
            .setSmallIcon(R.drawable.ic_logo)
            .setContentTitle(ctx.getString(R.string.monitor_running))
            .setContentIntent(openApp(ctx))
            .setOngoing(true)
            .build()

    private fun action(ctx: Context, act: String, mac: String, title: Int): NotificationCompat.Action {
        val i = Intent(act).setComponent(ComponentName(ctx, DeviceActionReceiver::class.java))
            .putExtra("mac", mac)
        val pi = PendingIntent.getBroadcast(
            ctx, (act + mac).hashCode(), i,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Action.Builder(0, ctx.getString(title), pi).build()
    }

    fun alert(ctx: Context, c: Client, nickname: String?, blocked: Boolean) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val who = nickname ?: c.name ?: c.ip ?: c.mac
        val b = NotificationCompat.Builder(ctx, CH_ALERT)
            .setSmallIcon(R.drawable.ic_logo)
            .setContentTitle(ctx.getString(if (blocked) R.string.alert_blocked_title else R.string.alert_new_title))
            .setContentText(ctx.getString(R.string.alert_body, who, c.mac))
            .setStyle(NotificationCompat.BigTextStyle().bigText(ctx.getString(R.string.alert_body_long, who, c.mac)))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true)
        if (!blocked) {
            b.addAction(action(ctx, DeviceActionReceiver.ACTION_BLOCK, c.mac, R.string.act_block))
            b.addAction(action(ctx, DeviceActionReceiver.ACTION_TRUST, c.mac, R.string.act_trust))
        }
        NotificationManagerCompat.from(ctx).notify(c.mac.hashCode(), b.build())
    }
}
