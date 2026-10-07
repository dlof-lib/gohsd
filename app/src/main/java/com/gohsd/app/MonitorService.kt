package com.gohsd.app

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** يراقب الراوتر كل 15 ثانية، وينبّهك عند اتصال جهاز غير معتمد (مثلاً شاركه أحد المشتركين كلمة السر). */
class MonitorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val alerted = HashSet<String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Notifier.createChannels(this)
        val type = when {
            Build.VERSION.SDK_INT >= 34 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            else -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
        }
        ServiceCompat.startForeground(this, Notifier.FOREGROUND_ID, Notifier.foreground(this), type)
        if (job == null) job = scope.launch { loop() }
        return START_STICKY
    }

    private suspend fun loop() {
        val prefs = Prefs(this)
        if (prefs.password.isEmpty()) { stopSelf(); return }
        var api: RouterApi? = null
        while (scope.isActive) {
            try {
                val a = api ?: RouterApi(prefs.routerUrl).also { it.login(prefs.username, prefs.password) }
                api = a
                scan(a.clients(), prefs, a)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                api = null
            }
            delay(15_000)
        }
    }

    private fun scan(clients: List<Client>, prefs: Prefs, api: RouterApi) {
        val macs = clients.map { it.mac }.toSet()
        alerted.retainAll(macs)
        val known = prefs.knownMacs
        if (!prefs.baselineDone) {            // أول تشغيل: الأجهزة الحالية تُعتبر معتمدة
            prefs.knownMacs = known + macs
            prefs.baselineDone = true
            return
        }
        val nicks = prefs.nicknames()
        for (c in clients) {
            if (c.mac in known || c.mac in alerted) continue
            alerted += c.mac
            val block = prefs.autoBlock
            if (block) try { api.setBlocked(c.mac, true) } catch (_: Exception) { }
            Notifier.alert(this, c, nicks[c.mac], block)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
