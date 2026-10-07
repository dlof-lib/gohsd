package com.gohsd.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** أزرار «حظر» و«اعتماد» داخل إشعار الجهاز الجديد. */
class DeviceActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_BLOCK = "com.gohsd.app.BLOCK"
        const val ACTION_TRUST = "com.gohsd.app.TRUST"
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        val mac = intent.getStringExtra("mac") ?: return
        val act = intent.action ?: return
        NotificationManagerCompat.from(ctx).cancel(mac.hashCode())
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val p = Prefs(ctx)
                when (act) {
                    ACTION_TRUST -> p.knownMacs = p.knownMacs + mac
                    ACTION_BLOCK -> RouterApi(p.routerUrl).also { it.login(p.username, p.password) }
                        .setBlocked(mac, true)
                }
            } catch (_: Exception) {
            } finally {
                pending.finish()
            }
        }
    }
}
