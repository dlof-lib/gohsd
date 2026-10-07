package com.gohsd.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.gohsd.app.databinding.ActivityRouterBinding
import com.gohsd.app.databinding.DialogWifiBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RouterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRouterBinding
    private lateinit var prefs: Prefs
    private var api: RouterApi? = null
    private var refreshJob: Job? = null
    private var sections: List<WifiSection> = emptyList()
    private val adapter = ClientAdapter { showClientActions(it) }
    private val last = HashMap<String, Triple<Long, Long, Long>>() // mac -> (rx, tx, time)

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        var ready = false
        splash.setKeepOnScreenCondition { !ready }
        Handler(Looper.getMainLooper()).postDelayed({ ready = true }, 700)
        splash.setOnExitAnimationListener { provider ->
            provider.view.animate().alpha(0f).scaleX(1.15f).scaleY(1.15f).setDuration(300)
                .withEndAction { provider.remove() }.start()
        }
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityRouterBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarsPadding()
        setSupportActionBar(binding.toolbar)
        prefs = Prefs(this)

        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        binding.inputUrl.setText(prefs.routerUrl)
        binding.inputUser.setText(prefs.username)
        binding.inputPass.setText(prefs.password)
        binding.connectButton.setOnClickListener { connect() }
        binding.btnVlink1.setOnClickListener { binding.inputUrl.setText("192.168.0.1"); binding.inputUser.setText("admin") }
        binding.btnVlink2.setOnClickListener { binding.inputUrl.setText("192.168.15.1"); binding.inputUser.setText("admin") }
        binding.diagButton.setOnClickListener {
            val addr = binding.inputUrl.text.toString().trim().ifEmpty { return@setOnClickListener }
            val user = binding.inputUser.text.toString().trim()
            val pass = binding.inputPass.text.toString()
            busy(true)
            lifecycleScope.launch {
                try {
                    val report = withContext(Dispatchers.IO) { VlinkDiagnostics.run(addr, user, pass) }
                    startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "GOHSD V-Link diagnostics")
                                putExtra(Intent.EXTRA_TEXT, report)
                            },
                            getString(R.string.diag_share_title)
                        )
                    )
                } catch (e: Exception) {
                    toast(getString(R.string.action_failed, e.message ?: ""))
                } finally {
                    busy(false)
                }
            }
        }
        binding.openWebButton.setOnClickListener {
            val addr = binding.inputUrl.text.toString().trim().ifEmpty { return@setOnClickListener }
            val url = if (addr.startsWith("http")) addr else "http://$addr"
            startActivity(
                Intent(this, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_URL, url)
                    .putExtra(MainActivity.EXTRA_USER, binding.inputUser.text.toString().trim())
                    .putExtra(MainActivity.EXTRA_PASS, binding.inputPass.text.toString())
            )
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)

        if (prefs.password.isNotEmpty()) connect()
        if (prefs.monitorEnabled) startMonitor()
    }

    // ---------- الاتصال ----------
    private fun connect() {
        val url = binding.inputUrl.text.toString().trim()
        val user = binding.inputUser.text.toString().trim()
        val pass = binding.inputPass.text.toString()
        if (url.isEmpty() || pass.isEmpty()) { toast(R.string.fill_fields); return }
        busy(true)
        lifecycleScope.launch {
            try {
                val a = withContext(Dispatchers.IO) { RouterApi(url).also { it.login(user, pass) } }
                api = a
                prefs.routerUrl = url; prefs.username = user; prefs.password = pass
                binding.loginView.visibility = View.GONE
                binding.dashView.visibility = View.VISIBLE
                startRefreshing()
            } catch (e: Exception) {
                toast(getString(R.string.login_failed, e.message ?: ""))
            } finally {
                busy(false)
            }
        }
    }

    private fun startRefreshing() {
        refreshJob?.cancel()
        refreshJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    val a = api ?: return@repeatOnLifecycle
                    try {
                        val (secs, clients) = withContext(Dispatchers.IO) { a.wifiSections() to a.clients() }
                        sections = secs
                        render(secs, clients)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        try {
                            withContext(Dispatchers.IO) { a.login(prefs.username, prefs.password) }
                        } catch (e2: CancellationException) {
                            throw e2
                        } catch (e2: Exception) {
                            binding.tvSsid.text = getString(R.string.connection_lost)
                        }
                    }
                    delay(5000)
                }
            }
        }
    }

    private fun render(secs: List<WifiSection>, clients: List<Client>) {
        val now = SystemClock.elapsedRealtime()
        val known = prefs.knownMacs
        val nicks = prefs.nicknames()
        val rows = clients.map { c ->
            val p = last[c.mac]
            var down = 0.0
            var up = 0.0
            if (p != null && now > p.third) {
                val dt = (now - p.third) / 1000.0
                down = maxOf(0L, c.tx - p.second).toDouble() / dt
                up = maxOf(0L, c.rx - p.first).toDouble() / dt
            }
            last[c.mac] = Triple(c.rx, c.tx, now)
            ClientRow(c, nicks[c.mac] ?: c.name ?: getString(R.string.unknown_device), down, up, c.mac in known)
        }.sortedByDescending { it.down + it.up }

        adapter.submit(rows)
        binding.tvSsid.text = getString(R.string.network_name, secs.firstOrNull()?.ssid ?: "-")
        binding.tvCount.text = getString(R.string.subscribers_count, clients.size)
        binding.tvTotal.text =
            getString(R.string.total_speed, Format.speed(rows.sumOf { it.down }), Format.speed(rows.sumOf { it.up }))
    }

    // ---------- إجراءات الأجهزة ----------
    private fun showClientActions(row: ClientRow) {
        val items = arrayOf(
            getString(R.string.act_block), getString(R.string.act_kick),
            getString(R.string.act_rename), getString(R.string.act_trust)
        )
        MaterialAlertDialogBuilder(this).setTitle(row.name)
            .setItems(items) { _, i ->
                when (i) {
                    0 -> runApi(R.string.blocked_ok) { it.setBlocked(row.client.mac, true) }
                    1 -> runApi(R.string.kicked_ok) { it.kick(row.client.mac) }
                    2 -> rename(row)
                    3 -> { prefs.knownMacs = prefs.knownMacs + row.client.mac; toast(R.string.trusted_ok) }
                }
            }.show()
    }

    private fun rename(row: ClientRow) {
        val input = EditText(this).apply { setText(row.name); setPadding(48, 32, 48, 32) }
        MaterialAlertDialogBuilder(this).setTitle(R.string.act_rename).setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                prefs.setNickname(row.client.mac, input.text.toString().trim())
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun showWifiDialog() {
        val d = DialogWifiBinding.inflate(layoutInflater)
        d.inputSsid.setText(sections.firstOrNull()?.ssid)
        MaterialAlertDialogBuilder(this).setTitle(R.string.change_wifi).setView(d.root)
            .setPositiveButton(R.string.save) { _, _ ->
                val ssid = d.inputSsid.text.toString().trim().ifEmpty { null }
                val key = d.inputKey.text.toString().ifEmpty { null }
                when {
                    ssid == null && key == null -> {}
                    ssid != null && ssid.length > 32 -> toast(R.string.ssid_invalid)
                    key != null && key.length !in 8..63 -> toast(R.string.key_invalid)
                    else -> runApi(R.string.wifi_updated) { it.updateWifi(ssid, key) }
                }
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun showBlocked() {
        val macs = sections.filter { it.macfilter == "deny" }.flatMap { it.maclist }.distinct()
        if (macs.isEmpty()) { toast(R.string.no_blocked); return }
        MaterialAlertDialogBuilder(this).setTitle(R.string.blocked_devices)
            .setItems(macs.toTypedArray()) { _, i ->
                runApi(R.string.unblocked_ok) { it.setBlocked(macs[i], false) }
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun confirm(title: Int, msg: Int, onOk: () -> Unit) {
        MaterialAlertDialogBuilder(this).setTitle(title).setMessage(msg)
            .setPositiveButton(android.R.string.ok) { _, _ -> onOk() }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun runApi(okMsg: Int, block: (RouterApi) -> Unit) {
        val a = api ?: return
        busy(true)
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { block(a) }
                toast(okMsg)
            } catch (e: Exception) {
                toast(getString(R.string.action_failed, e.message ?: ""))
            } finally {
                busy(false)
            }
        }
    }

    // ---------- المراقبة ----------
    private fun startMonitor() =
        ContextCompat.startForegroundService(this, Intent(this, MonitorService::class.java))

    private fun stopMonitor() {
        stopService(Intent(this, MonitorService::class.java))
    }

    // ---------- القائمة ----------
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.router_menu, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_monitor).isChecked = prefs.monitorEnabled
        menu.findItem(R.id.action_autoblock).isChecked = prefs.autoBlock
        val connected = api != null
        for (id in listOf(
            R.id.action_wifi, R.id.action_optimize, R.id.action_blocked,
            R.id.action_autoblock, R.id.action_reboot
        )) menu.findItem(id).isEnabled = connected
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_wifi -> showWifiDialog()
            R.id.action_optimize -> confirm(R.string.optimize, R.string.optimize_msg) {
                runApi(R.string.optimized_ok) { it.optimizeWifi() }
            }
            R.id.action_blocked -> showBlocked()
            R.id.action_monitor -> {
                prefs.monitorEnabled = !prefs.monitorEnabled
                if (prefs.monitorEnabled) startMonitor() else stopMonitor()
            }
            R.id.action_autoblock -> prefs.autoBlock = !prefs.autoBlock
            R.id.action_reboot -> confirm(R.string.reboot, R.string.reboot_msg) {
                runApi(R.string.reboot_sent) { it.reboot() }
            }
            R.id.action_web -> startActivity(Intent(this, MainActivity::class.java))
            R.id.action_logout -> {
                prefs.password = ""
                api = null
                refreshJob?.cancel()
                binding.dashView.visibility = View.GONE
                binding.loginView.visibility = View.VISIBLE
            }
            else -> return super.onOptionsItemSelected(item)
        }
        invalidateOptionsMenu()
        return true
    }

    private fun busy(on: Boolean) {
        binding.busy.visibility = if (on) View.VISIBLE else View.GONE
    }

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
