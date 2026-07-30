package com.iwx.panel

import android.annotation.SuppressLint
import android.app.*
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.*
import android.webkit.*
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

class FloatingWindowService : Service() {

    private lateinit var wm: WindowManager
    private var root: View? = null
    private var webView: WebView? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var bubbleView: View
    private lateinit var panelView: View
    private lateinit var params: WindowManager.LayoutParams

    companion object {
        const val CH_ID   = "iwx_overlay_ch"
        const val NOTIF_ID = 2001
        const val ACTION_STOP = "IWX_STOP_OVERLAY"
        // Cookie injected to skip login — value = SHA-256(password)
        const val SESSION_COOKIE = "iwx_auth=dfa3cf6eb60e9ef0815963a8160181432fe1ba87e44b10f77f4d4a6248c31f2a; Path=/; SameSite=Strict"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIF_ID, buildNotif())
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        setupFloatWindow()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        return START_STICKY
    }

    private fun createChannel() {
        val ch = NotificationChannel(CH_ID, getString(R.string.overlay_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false)
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
    }

    private fun buildNotif(): Notification {
        val pi = PendingIntent.getService(this, 0,
            Intent(this, FloatingWindowService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CH_ID)
            .setContentTitle(getString(R.string.overlay_notif_title))
            .setContentText(getString(R.string.overlay_notif_text))
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.btn_close_overlay), pi)
            .setOngoing(true)
            .build()
    }

    @SuppressLint("InflateParams", "SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun setupFloatWindow() {
        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.overlay_panel, null)
        root = view

        bubbleView = view.findViewById(R.id.bubbleView)
        panelView  = view.findViewById(R.id.panelView)
        webView    = view.findViewById<WebView>(R.id.overlayWebView)
        val btnMin   = view.findViewById<TextView>(R.id.btnMinimize)
        val btnClose = view.findViewById<TextView>(R.id.btnClose)
        val dragHand = view.findViewById<View>(R.id.dragHandle)

        // WindowManager params — start as bubble size
        params = WindowManager.LayoutParams(
            dpToPx(62), dpToPx(62),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dpToPx(16); y = dpToPx(120)
        }
        wm.addView(view, params)

        // Setup WebView with cookie injection
        setupWebView()

        // Bubble tap — open panel
        var dX = 0f; var dY = 0f; var moved = false
        bubbleView.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { dX = e.rawX - params.x; dY = e.rawY - params.y; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val nx = (e.rawX - dX).toInt(); val ny = (e.rawY - dY).toInt()
                    if (Math.abs(nx - params.x) > 8 || Math.abs(ny - params.y) > 8) moved = true
                    params.x = nx; params.y = ny
                    wm.updateViewLayout(view, params)
                    true
                }
                MotionEvent.ACTION_UP -> { if (!moved) openPanel(); true }
                else -> false
            }
        }

        // Drag handle — move panel
        var pX = 0f; var pY = 0f
        dragHand.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { pX = e.rawX - params.x; pY = e.rawY - params.y; true }
                MotionEvent.ACTION_MOVE -> {
                    params.x = (e.rawX - pX).toInt(); params.y = (e.rawY - pY).toInt()
                    wm.updateViewLayout(view, params)
                    true
                }
                else -> false
            }
        }

        // Minimize → back to bubble
        btnMin.setOnClickListener { closePanelToBubble() }

        // Close → stop service entirely
        btnClose.setOnClickListener { stopSelf() }
    }

    private fun openPanel() {
        bubbleView.visibility = View.GONE
        panelView.visibility  = View.VISIBLE
        params.width  = dpToPx(340)
        params.height = dpToPx(520)
        params.flags  = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        root?.let { wm.updateViewLayout(it, params) }
        scope.launch {
            val url = withContext(Dispatchers.IO) { RemoteConfig.fetch() }
            injectCookieAndLoad(url)
        }
    }

    private fun closePanelToBubble() {
        panelView.visibility  = View.GONE
        bubbleView.visibility = View.VISIBLE
        params.width  = dpToPx(62)
        params.height = dpToPx(62)
        params.flags  = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        root?.let { wm.updateViewLayout(it, params) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView?.settings?.apply {
            javaScriptEnabled    = true
            domStorageEnabled    = true
            databaseEnabled      = true
            useWideViewPort      = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls  = true
            displayZoomControls  = false
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
    }

    private fun injectCookieAndLoad(serverUrl: String) {
        val cm = CookieManager.getInstance()
        cm.setCookie(serverUrl, SESSION_COOKIE)
        cm.flush()
        webView?.loadUrl(serverUrl)
    }

    override fun onDestroy() {
        scope.cancel()
        webView?.destroy()
        root?.let { runCatching { wm.removeView(it) } }
        super.onDestroy()
    }

    private fun dpToPx(dp: Int) = (dp * resources.displayMetrics.density).toInt()
}
