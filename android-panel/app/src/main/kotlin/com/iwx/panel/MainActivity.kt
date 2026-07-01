package com.iwx.panel

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Bundle
import android.view.View
import android.webkit.*
import androidx.appcompat.app.AppCompatActivity
import com.iwx.panel.databinding.ActivityMainBinding
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

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
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) showError(error.description.toString())
            }
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel()
                showError("SSL error: ${error.primaryError}")
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return false
            }
        }

        b.webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                b.progressBar.progress = newProgress
                if (newProgress == 100) b.progressBar.visibility = View.GONE
            }
        }
    }

    private fun loadPanel() {
        b.errorLayout.visibility = View.GONE
        b.progressBar.visibility = View.VISIBLE
        b.swipeRefresh.isRefreshing = false
        scope.launch {
            val url = withContext(Dispatchers.IO) { RemoteConfig.fetch() }
            b.webView.loadUrl(url)
        }
    }

    private fun showError(msg: String) {
        b.progressBar.visibility   = View.GONE
        b.swipeRefresh.isRefreshing = false
        b.errorLayout.visibility   = View.VISIBLE
        b.errorMsg.text            = msg
    }

    override fun onBackPressed() {
        if (b.webView.canGoBack()) b.webView.goBack()
        else super.onBackPressed()
    }

    override fun onDestroy() {
        scope.cancel()
        b.webView.destroy()
        super.onDestroy()
    }
}
