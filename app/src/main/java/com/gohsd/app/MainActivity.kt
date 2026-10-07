package com.gohsd.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.HttpAuthHandler
import android.webkit.JsResult
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.gohsd.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    companion object {
        /** رابط لوحة الراوتر (مثل V-Link) ليُفتح داخل التطبيق */
        const val EXTRA_URL = "extra_url"
        const val EXTRA_USER = "extra_user"
        const val EXTRA_PASS = "extra_pass"
    }

    private var extraHost: String? = null
    private var routerUser: String? = null
    private var routerPass: String? = null
    private var authTried = false

    private lateinit var binding: ActivityMainBinding
    private val policy = UrlPolicy(BuildConfig.HOME_HOST)

    private var hadError = false
    private var lastBackPress = 0L

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var pendingGeoOrigin: String? = null
    private var pendingGeoCallback: GeolocationPermissions.Callback? = null
    private var pendingDownload: (() -> Unit)? = null

    private val connectivityManager by lazy { getSystemService(ConnectivityManager::class.java) }

    // ---------- Activity result launchers ----------
    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            filePathCallback?.onReceiveValue(uris)
            filePathCallback = null
        }

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            pendingGeoCallback?.invoke(pendingGeoOrigin, granted, false)
            pendingGeoCallback = null
            pendingGeoOrigin = null
        }

    private val storagePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) pendingDownload?.invoke() else toast(R.string.storage_denied)
            pendingDownload = null
        }

    // ---------- Network callback: إعادة التحميل تلقائياً عند عودة الاتصال ----------
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            runOnUiThread { if (hadError) binding.webView.reload() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.mainContent.applySystemBarsPadding()
        setSupportActionBar(binding.toolbar)

        setupWebView()
        setupSwipeRefresh()
        setupBackHandling()
        binding.retryButton.setOnClickListener { binding.webView.reload() }

        val restored = savedInstanceState?.let { binding.webView.restoreState(it) }
        if (restored == null) binding.webView.loadUrl(resolveStartUrl(intent))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.data != null || intent.hasExtra(EXTRA_URL)) binding.webView.loadUrl(resolveStartUrl(intent))
    }

    private fun resolveStartUrl(intent: Intent?): String {
        intent?.getStringExtra(EXTRA_URL)?.let {
            extraHost = Uri.parse(it).host
            routerUser = intent.getStringExtra(EXTRA_USER)
            routerPass = intent.getStringExtra(EXTRA_PASS)
            authTried = false
            return it
        }
        val data = intent?.data
        val ok = data != null &&
            (data.scheme == "https" || data.scheme == "http") &&
            policy.isInternal(data.host)
        return if (ok) data.toString() else BuildConfig.HOME_URL
    }

    // ---------- WebView ----------
    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val web = binding.webView
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = "$userAgentString GOHSD/${BuildConfig.VERSION_NAME}"
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(web.settings, true)
        }

        web.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            startDownload(url, userAgent, contentDisposition, mimeType)
        }
        web.webViewClient = AppWebViewClient()
        web.webChromeClient = AppChromeClient()
    }

    private fun setupSwipeRefresh() {
        binding.swipe.setColorSchemeResources(R.color.primary)
        binding.swipe.setOnRefreshListener { binding.webView.reload() }
        binding.swipe.setOnChildScrollUpCallback { _, _ -> binding.webView.scrollY > 0 }
    }

    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    customView != null -> hideCustomView()
                    binding.webView.canGoBack() -> binding.webView.goBack()
                    SystemClock.elapsedRealtime() - lastBackPress < 2000 -> finish()
                    else -> {
                        lastBackPress = SystemClock.elapsedRealtime()
                        toast(R.string.press_again_to_exit)
                    }
                }
            }
        })
    }

    private inner class AppWebViewClient : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
            handleUrl(request.url)

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            hadError = false
            binding.progress.visibility = View.VISIBLE
        }

        override fun onReceivedHttpAuthRequest(
            view: WebView, handler: HttpAuthHandler, host: String?, realm: String?
        ) {
            val u = routerUser
            val p = routerPass
            if (!authTried && host == extraHost && u != null && p != null) {
                authTried = true
                handler.proceed(u, p)
                return
            }
            val box = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(48, 24, 48, 0)
            }
            val user = EditText(this@MainActivity).apply {
                hint = getString(R.string.username); setText(u ?: "admin")
            }
            val pass = EditText(this@MainActivity).apply {
                hint = getString(R.string.password)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            box.addView(user); box.addView(pass)
            MaterialAlertDialogBuilder(this@MainActivity).setTitle(host).setView(box)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    handler.proceed(user.text.toString(), pass.text.toString())
                }
                .setNegativeButton(android.R.string.cancel) { _, _ -> handler.cancel() }
                .setOnCancelListener { handler.cancel() }
                .show()
        }

        override fun onPageFinished(view: WebView, url: String?) {
            authTried = false
            binding.progress.visibility = View.GONE
            binding.swipe.isRefreshing = false
            if (!hadError) binding.offlineView.visibility = View.GONE
            supportActionBar?.subtitle = url?.let { Uri.parse(it).host }
            invalidateOptionsMenu()
        }

        override fun onReceivedError(
            view: WebView, request: WebResourceRequest, error: WebResourceError
        ) {
            if (request.isForMainFrame) showError()
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            handler.cancel()
            showError()
        }
    }

    private fun showError() {
        hadError = true
        binding.swipe.isRefreshing = false
        binding.progress.visibility = View.GONE
        binding.offlineView.visibility = View.VISIBLE
    }

    private inner class AppChromeClient : WebChromeClient() {

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            binding.progress.setProgressCompat(newProgress, true)
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            supportActionBar?.title = title?.takeIf { it.isNotBlank() } ?: getString(R.string.app_name)
        }

        override fun onShowFileChooser(
            webView: WebView,
            callback: ValueCallback<Array<Uri>>,
            params: FileChooserParams
        ): Boolean {
            filePathCallback?.onReceiveValue(null)
            filePathCallback = callback
            return try {
                fileChooserLauncher.launch(params.createIntent())
                true
            } catch (e: ActivityNotFoundException) {
                filePathCallback = null
                false
            }
        }

        override fun onGeolocationPermissionsShowPrompt(
            origin: String, callback: GeolocationPermissions.Callback
        ) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                callback.invoke(origin, true, false)
            } else {
                pendingGeoOrigin = origin
                pendingGeoCallback = callback
                locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (customView != null) {
                callback.onCustomViewHidden()
                return
            }
            customView = view
            customViewCallback = callback
            binding.fullscreenContainer.addView(view)
            binding.fullscreenContainer.visibility = View.VISIBLE
            binding.mainContent.visibility = View.GONE
            systemBars().apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }

        override fun onHideCustomView() = hideCustomView()

        override fun onJsAlert(
            view: WebView, url: String?, message: String?, result: JsResult
        ): Boolean {
            MaterialAlertDialogBuilder(this@MainActivity)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
                .setOnCancelListener { result.cancel() }
                .show()
            return true
        }

        override fun onJsConfirm(
            view: WebView, url: String?, message: String?, result: JsResult
        ): Boolean {
            MaterialAlertDialogBuilder(this@MainActivity)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
                .setNegativeButton(android.R.string.cancel) { _, _ -> result.cancel() }
                .setOnCancelListener { result.cancel() }
                .show()
            return true
        }
    }

    private fun systemBars() = WindowCompat.getInsetsController(window, window.decorView)

    private fun hideCustomView() {
        val view = customView ?: return
        binding.fullscreenContainer.removeView(view)
        binding.fullscreenContainer.visibility = View.GONE
        binding.mainContent.visibility = View.VISIBLE
        systemBars().show(WindowInsetsCompat.Type.systemBars())
        customViewCallback?.onCustomViewHidden()
        customView = null
        customViewCallback = null
    }

    // ---------- Links ----------
    private fun handleUrl(uri: Uri): Boolean {
        return when (uri.scheme) {
            "http", "https" -> {
                if (policy.isInternal(uri.host) || uri.host == extraHost) false else { openExternal(uri); true }
            }
            "intent" -> { openIntentUri(uri); true }
            else -> { openExternal(uri); true } // tel: mailto: sms: geo: ...
        }
    }

    private fun openExternal(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: ActivityNotFoundException) {
            toast(R.string.no_app_found)
        }
    }

    private fun openIntentUri(uri: Uri) {
        val parsed = try {
            Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
        } catch (e: Exception) {
            return
        }
        parsed.addCategory(Intent.CATEGORY_BROWSABLE)
        parsed.component = null
        parsed.selector = null
        try {
            startActivity(parsed)
        } catch (e: ActivityNotFoundException) {
            parsed.getStringExtra("browser_fallback_url")?.let { binding.webView.loadUrl(it) }
                ?: toast(R.string.no_app_found)
        }
    }

    // ---------- Downloads ----------
    private fun startDownload(url: String, userAgent: String?, disposition: String?, mime: String?) {
        if (url.startsWith("blob:") || url.startsWith("data:")) {
            toast(R.string.download_unsupported)
            return
        }
        val action = {
            val fileName = URLUtil.guessFileName(url, disposition, mime)
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mime)
                userAgent?.let { addRequestHeader("User-Agent", it) }
                CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
                setTitle(fileName)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            }
            getSystemService(DownloadManager::class.java).enqueue(request)
            toast(R.string.download_started)
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingDownload = action
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            action()
        }
    }

    // ---------- Menu ----------
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_forward)?.isEnabled = binding.webView.canGoForward()
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val web = binding.webView
        when (item.itemId) {
            R.id.action_home -> web.loadUrl(BuildConfig.HOME_URL)
            R.id.action_forward -> if (web.canGoForward()) web.goForward()
            R.id.action_refresh -> web.reload()
            R.id.action_share -> startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, web.url ?: BuildConfig.HOME_URL)
                    },
                    getString(R.string.share)
                )
            )
            R.id.action_copy -> {
                val cm = getSystemService(ClipboardManager::class.java)
                cm.setPrimaryClip(ClipData.newPlainText("url", web.url ?: BuildConfig.HOME_URL))
                toast(R.string.link_copied)
            }
            R.id.action_browser -> openExternal(Uri.parse(web.url ?: BuildConfig.HOME_URL))
            R.id.action_clear -> {
                web.clearCache(true)
                CookieManager.getInstance().removeAllCookies(null)
                toast(R.string.cache_cleared)
                web.reload()
            }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    // ---------- Lifecycle ----------
    override fun onStart() {
        super.onStart()
        try { connectivityManager.registerDefaultNetworkCallback(networkCallback) } catch (_: Exception) {}
    }

    override fun onStop() {
        super.onStop()
        try { connectivityManager.unregisterNetworkCallback(networkCallback) } catch (_: Exception) {}
    }

    override fun onResume() {
        super.onResume()
        binding.webView.onResume()
    }

    override fun onPause() {
        binding.webView.onPause()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.webView.saveState(outState)
    }

    override fun onDestroy() {
        filePathCallback?.onReceiveValue(null)
        filePathCallback = null
        binding.webView.apply {
            stopLoading()
            (parent as? ViewGroup)?.removeView(this)
            destroy()
        }
        super.onDestroy()
    }

    private fun toast(resId: Int) = Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
}
