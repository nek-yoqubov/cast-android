package xyz.nekruz.cast

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
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
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
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
        const val URL = "https://nekruz.xyz/cast/screen.html?app=1"
        const val RETRY_MS = 5000L
        const val BACK_EXIT_WINDOW_MS = 2000L
        const val BACKGROUND = 0xFF0B0D12.toInt()
    }

    private lateinit var webView: WebView
    private lateinit var messageView: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val retry = Runnable { load() }
    private var loadFailed = false
    private var lastBackPress = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        )
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        webView = WebView(this)
        messageView = TextView(this).apply {
            setBackgroundColor(BACKGROUND)
            setTextColor(Color.rgb(0xF2, 0xF4, 0xF8))
            textSize = 28f
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            visibility = View.GONE
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(BACKGROUND)
            addView(webView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(messageView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        })

        setUpWebView()
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { onBack() }
        }
        hideSystemBars()
        load()
        askForAutostartPermissionOnce()
    }

    private fun setUpWebView() {
        webView.setBackgroundColor(BACKGROUND)
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false // sound starts without a tap
            cacheMode = WebSettings.LOAD_DEFAULT
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread { request.grant(request.resources) }
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                loadFailed = false
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (loadFailed) return
                messageView.visibility = View.GONE
                checkWebRtc()
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

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = false

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                Log.w(TAG, "WebView renderer gone, restarting")
                recreate()
                return true
            }
        }
    }

    private fun load() {
        handler.removeCallbacks(retry)
        webView.loadUrl(URL)
    }

    private fun onLoadFailed(reason: String) {
        Log.w(TAG, "Load failed: $reason")
        loadFailed = true
        showMessage(getString(R.string.no_connection))
        handler.removeCallbacks(retry)
        handler.postDelayed(retry, RETRY_MS)
    }

    private fun checkWebRtc() {
        webView.evaluateJavascript("typeof RTCPeerConnection") { type ->
            if (type == "\"undefined\"") showMessage(getString(R.string.webview_outdated))
        }
    }

    private fun showMessage(text: String) {
        messageView.text = text
        messageView.visibility = View.VISIBLE
    }

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

    // Since Android 10 BootReceiver may start this Activity only with "display over other apps".
    // Ask once; if the panel has no such setting, just live without autostart.
    private fun askForAutostartPermissionOnce() {
        if (Build.VERSION.SDK_INT < 29 || Settings.canDrawOverlays(this)) return
        val prefs = getSharedPreferences("cast", MODE_PRIVATE)
        if (prefs.getBoolean("autostartAsked", false)) return
        prefs.edit().putBoolean("autostartAsked", true).apply()

        AlertDialog.Builder(this)
            .setTitle(R.string.autostart_title)
            .setMessage(R.string.autostart_message)
            .setPositiveButton(R.string.autostart_allow) { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                } catch (e: Exception) {
                    Log.w(TAG, "Overlay permission screen unavailable", e)
                    Toast.makeText(this, R.string.autostart_unavailable, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(R.string.autostart_skip, null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        hideSystemBars()
    }

    override fun onPause() {
        webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(retry)
        webView.destroy()
        super.onDestroy()
    }
}
