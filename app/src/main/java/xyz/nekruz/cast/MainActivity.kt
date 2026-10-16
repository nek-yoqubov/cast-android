package xyz.nekruz.cast

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedDispatcher

/**
 * A full-screen WebView around https://nekruz.xyz/cast/screen.html — all the logic lives on the site,
 * so updating the site never requires reinstalling the APK.
 */
class MainActivity : Activity() {

    private companion object {
        const val TAG = "Cast"
        const val HOST = "nekruz.xyz"
        const val URL = "https://$HOST/cast/screen.html?app=1"
        const val RETRY_MS = 5000L
        const val BACK_EXIT_WINDOW_MS = 2000L
        const val RENDERER_RESTART_MS = 1000L
        const val RENDERER_BACKOFF_MS = 10000L
        const val RENDERER_CRASH_WINDOW_MS = 60000L
        const val BACKGROUND = 0xFF0B0D12.toInt()
        const val FOREGROUND = 0xFFF2F4F8.toInt()
        const val MUTED = 0xFF8A93A6.toInt()
    }

    private lateinit var root: FrameLayout
    private lateinit var messageView: TextView
    private var webView: WebView? = null
    private var crashView: View? = null
    private val handler = Handler(Looper.getMainLooper())
    private val retry = Runnable { load() }
    private val rendererDeaths = ArrayDeque<Long>()
    private var loadFailed = false
    private var lastBackPress = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        CrashReporter.note(this, "Запуск ${versionName()}")

        messageView = TextView(this).apply {
            setBackgroundColor(BACKGROUND)
            setTextColor(FOREGROUND)
            textSize = 28f
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            visibility = View.GONE
        }
        root = FrameLayout(this).apply {
            setBackgroundColor(BACKGROUND)
            addView(messageView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        setContentView(root)

        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { onBack() }
        }
        hideSystemBars()

        val report = CrashReporter.pendingReport(this)
        if (report != null) showCrashReport(report) else start()
    }

    private fun start() {
        createWebView()
        load()
    }

    /* ---------- WebView ---------- */

    private fun createWebView() {
        if (webView != null) return
        val wv = try {
            WebView(this)
        } catch (e: Throwable) {
            // e.g. no WebView provider installed on the firmware
            Log.e(TAG, "WebView unavailable", e)
            CrashReporter.note(this, "WebView недоступен: $e")
            showMessage(getString(R.string.webview_missing) + "\n\n" + e)
            return
        }
        wv.setBackgroundColor(BACKGROUND)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false // sound starts without a tap
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        wv.addJavascriptInterface(AppBridge(), "CastApp")
        wv.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread { request.grant(request.resources) }
            }
        }
        wv.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                loadFailed = false
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (loadFailed || view !== webView) return
                messageView.visibility = View.GONE
                checkWebRtc(view)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) onLoadFailed("${error.errorCode} ${error.description}")
            }

            @Deprecated("Used only below API 23")
            override fun onReceivedError(view: WebView, errorCode: Int, description: String?, failingUrl: String?) {
                if (Build.VERSION.SDK_INT < 23) onLoadFailed("$errorCode $description")
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame && response.statusCode >= 400) onLoadFailed("HTTP ${response.statusCode}")
            }

            // Stay on the cast page: the JS bridge must never be exposed to other sites.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.url.host != HOST

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                onRendererGone(view, if (Build.VERSION.SDK_INT >= 26) detail.didCrash() else true)
                return true // handled: never let the app be killed for it
            }
        }
        // Below the message/crash views, which are already in the root.
        root.addView(wv, 0, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        webView = wv
    }

    // The renderer died (crash or killed for memory). The old WebView must not be touched again:
    // drop it and build a fresh one; back off if it keeps dying.
    private fun onRendererGone(view: WebView, crashed: Boolean) {
        Log.w(TAG, "WebView renderer gone, crashed=$crashed")
        CrashReporter.note(this, "Процесс WebView ${if (crashed) "упал" else "убит системой"} — пересоздаю WebView")
        if (view === webView) webView = null
        root.removeView(view)
        try { view.destroy() } catch (e: Throwable) { Log.w(TAG, "destroy after renderer gone", e) }

        val now = SystemClock.elapsedRealtime()
        rendererDeaths.addLast(now)
        while (rendererDeaths.isNotEmpty() && now - rendererDeaths.first() > RENDERER_CRASH_WINDOW_MS) rendererDeaths.removeFirst()
        val delay = if (rendererDeaths.size >= 3) RENDERER_BACKOFF_MS else RENDERER_RESTART_MS
        showMessage(getString(R.string.renderer_restarting))
        handler.removeCallbacks(retry)
        handler.postDelayed(retry, delay)
    }

    private fun load() {
        handler.removeCallbacks(retry)
        if (crashView != null) return
        createWebView()
        webView?.loadUrl(URL)
    }

    private fun onLoadFailed(reason: String) {
        Log.w(TAG, "Load failed: $reason")
        CrashReporter.note(this, "Ошибка загрузки: $reason")
        loadFailed = true
        showMessage(getString(R.string.no_connection))
        handler.removeCallbacks(retry)
        handler.postDelayed(retry, RETRY_MS)
    }

    private fun checkWebRtc(view: WebView) {
        view.evaluateJavascript("typeof RTCPeerConnection") { type ->
            if (type == "\"undefined\"") showMessage(getString(R.string.webview_outdated))
        }
    }

    private fun showMessage(text: String) {
        messageView.text = text
        messageView.visibility = View.VISIBLE
    }

    /* ---------- hidden settings (called from the page's settings dialog) ---------- */

    private inner class AppBridge {
        @JavascriptInterface
        fun version(): String = versionName()

        @JavascriptInterface
        fun autostartAllowed(): Boolean = Build.VERSION.SDK_INT < 29 || Settings.canDrawOverlays(this@MainActivity)

        @JavascriptInterface
        fun openAutostartSettings() {
            runOnUiThread { openOverlaySettings() }
        }
    }

    // Since Android 10 BootReceiver may open this Activity only with "display over other apps".
    // Some panel firmwares have no such screen — then simply live without autostart.
    private fun openOverlaySettings() {
        if (Build.VERSION.SDK_INT < 23) return
        val intents = listOf(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
        )
        for (intent in intents) {
            try {
                startActivity(intent)
                return
            } catch (e: Exception) {
                Log.w(TAG, "Overlay settings unavailable: $intent", e)
            }
        }
        CrashReporter.note(this, "Экран «Поверх других приложений» недоступен на этой прошивке")
        Toast.makeText(this, R.string.autostart_unavailable, Toast.LENGTH_LONG).show()
    }

    /* ---------- crash report screen ---------- */

    private fun showCrashReport(report: String) {
        val title = TextView(this).apply {
            text = getString(R.string.crash_title)
            setTextColor(FOREGROUND)
            textSize = 30f
            setTypeface(typeface, Typeface.BOLD)
        }
        val hint = TextView(this).apply {
            text = getString(R.string.crash_hint)
            setTextColor(MUTED)
            textSize = 20f
            setPadding(0, 12, 0, 24)
        }
        val body = TextView(this).apply {
            text = report
            setTextColor(FOREGROUND)
            textSize = 18f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this).apply { addView(body) }
        val button = Button(this).apply {
            text = getString(R.string.crash_continue)
            textSize = 24f
            setOnClickListener {
                CrashReporter.clear(this@MainActivity)
                root.removeView(crashView)
                crashView = null
                start()
            }
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BACKGROUND)
            setPadding(48, 40, 48, 32)
            addView(title)
            addView(hint)
            addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            addView(button, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                gravity = Gravity.END
                topMargin = 24
            })
        }
        root.addView(layout, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        crashView = layout
        button.requestFocus()
    }

    /* ---------- system UI, back ---------- */

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.apply {
                hide(WindowInsets.Type.systemBars())
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        }
    }

    // Back exits only when pressed twice, so a stray tap doesn't close the panel's app.
    @Deprecated("Used only below API 33; newer versions go through OnBackInvokedCallback")
    override fun onBackPressed() = onBack()

    private fun onBack() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastBackPress < BACK_EXIT_WINDOW_MS) {
            finish()
        } else {
            lastBackPress = now
            Toast.makeText(this, R.string.press_back_again, Toast.LENGTH_SHORT).show()
        }
    }

    private fun versionName(): String = try {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (_: Exception) {
        "?"
    }

    /* ---------- lifecycle ---------- */

    override fun onResume() {
        super.onResume()
        webView?.onResume()
        hideSystemBars()
    }

    override fun onPause() {
        webView?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        webView?.let {
            webView = null
            root.removeView(it)
            it.destroy()
        }
        super.onDestroy()
    }
}
