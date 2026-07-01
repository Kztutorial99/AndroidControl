package com.iwx.panel

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.webkit.*
import androidx.appcompat.app.AppCompatActivity
import com.iwx.panel.databinding.ActivityMainBinding
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    companion object {
        const val SESSION_COOKIE =
            "iwx_auth=dfa3cf6eb60e9ef0815963a8160181432fe1ba87e44b10f77f4d4a6248c31f2a; Path=/; SameSite=Strict"
        const val REQ_OVERLAY = 1001
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        setupWebView()
        loadPanel()

        b.swipeRefresh.setColorSchemeColors(0xFF00c853.toInt())
        b.swipeRefresh.setOnRefreshListener { loadPanel() }
        b.retryBtn.setOnClickListener { loadPanel() }
        b.floatBtn.setOnClickListener { handleOverlayToggle() }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        b.webView.settings.apply {
            javaScriptEnabled       = true
            domStorageEnabled       = true
            databaseEnabled         = true
            allowFileAccess         = false
            mixedContentMode        = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode               = WebSettings.LOAD_DEFAULT
            useWideViewPort         = true
            loadWithOverviewMode    = true
            setSupportZoom(false)
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(b.webView, true)

        b.webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                b.progressBar.visibility = View.VISIBLE
                b.errorLayout.visibility = View.GONE
            }
            override fun onPageFinished(view: WebView, url: String) {
                b.progressBar.visibility = View.GONE
                b.swipeRefresh.isRefreshing = false
            }
            override fun onReceivedError(view: WebView, req: WebResourceRequest, err: WebResourceError) {
                if (req.isForMainFrame) showError(err.description.toString())
            }
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, err: SslError) {
                handler.cancel(); showError("SSL error")
            }
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest) = false
        }

        b.webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, p: Int) {
                b.progressBar.progress = p
                if (p == 100) b.progressBar.visibility = View.GONE
            }
        }
    }

    private fun loadPanel() {
        b.errorLayout.visibility = View.GONE
        b.progressBar.visibility = View.VISIBLE
        b.swipeRefresh.isRefreshing = false
        scope.launch {
            val url = withContext(Dispatchers.IO) { RemoteConfig.fetch() }
            injectCookieAndLoad(url)
        }
    }

    private fun injectCookieAndLoad(serverUrl: String) {
        val cm = CookieManager.getInstance()
        cm.setCookie(serverUrl, SESSION_COOKIE)
        cm.flush()
        b.webView.loadUrl(serverUrl)
    }

    // ── Floating Overlay ──────────────────────────────────────────────────────

    private fun handleOverlayToggle() {
        if (!Settings.canDrawOverlays(this)) {
            showOverlayPermDialog()
        } else {
            startFloatingService()
        }
    }

    private fun showOverlayPermDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.overlay_perm_title))
            .setMessage(getString(R.string.overlay_perm_msg))
            .setPositiveButton(getString(R.string.btn_grant)) { _, _ ->
                startActivity(Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                ))
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun startFloatingService() {
        val intent = Intent(this, FloatingWindowService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            startForegroundService(intent)
        else
            startService(intent)
        // Optionally minimize app so overlay is visible
        moveTaskToBack(true)
    }

    override fun onActivityResult(req: Int, result: Int, data: Intent?) {
        super.onActivityResult(req, result, data)
        if (req == REQ_OVERLAY && Settings.canDrawOverlays(this)) startFloatingService()
    }

    private fun showError(msg: String) {
        b.progressBar.visibility    = View.GONE
        b.swipeRefresh.isRefreshing = false
        b.errorLayout.visibility    = View.VISIBLE
        b.errorMsg.text             = msg
    }

    override fun onBackPressed() {
        if (b.webView.canGoBack()) b.webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        scope.cancel(); b.webView.destroy(); super.onDestroy()
    }
}
