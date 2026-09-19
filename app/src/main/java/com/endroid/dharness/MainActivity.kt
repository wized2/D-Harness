package com.endroid.dharness

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private lateinit var fab: View
    private lateinit var fabDot: View
    private lateinit var prefs: android.content.SharedPreferences
    private var desktopMode = false
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private val startUrl = "https://chat.deepseek.com/"

    private val fileChooser = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        filePathCallback?.onReceiveValue(uris)
        filePathCallback = null
    }

    private val settingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        applySettingsFromPrefs()
        if (prefs.getBoolean("pending_reload", false)) {
            prefs.edit().putBoolean("pending_reload", false).apply()
            webView.reload()
        }
        if (prefs.getBoolean("pending_inject", false)) {
            prefs.edit().putBoolean("pending_inject", false).apply()
            injectShim()
        }
        if (prefs.getBoolean("pending_clear_cache", false)) {
            prefs.edit().putBoolean("pending_clear_cache", false).apply()
            webView.clearCache(true)
            webView.reload()
        }
        if (prefs.getBoolean("pending_send_instructions", false)) {
            prefs.edit().putBoolean("pending_send_instructions", false).apply()
            webView.postDelayed({ sendToolInstructions() }, 600)
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let {
            it.hide(WindowInsetsCompat.Type.statusBars())
            it.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("dharness_settings", MODE_PRIVATE)
        desktopMode = prefs.getBoolean("desktop", false)

        webView = findViewById(R.id.webView)
        progress = findViewById(R.id.progress)
        fab = findViewById(R.id.fab)
        fabDot = findViewById(R.id.fabDot)

        // Keep FAB clear of gesture/nav insets
        ViewCompat.setOnApplyWindowInsetsListener(fab) { v, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            (v.layoutParams as FrameLayout.LayoutParams).bottomMargin = nav.bottom + 8
            v.requestLayout()
            insets
        }

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = WebSettings.LOAD_DEFAULT
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = false
            displayZoomControls = false
            setSupportZoom(false)
            allowFileAccess = true
            allowContentAccess = true
            javaScriptCanOpenWindowsAutomatically = false
            userAgentString = buildUa()
        }

        webView.addJavascriptInterface(HarnessBridge(this, webView), "DHarness")
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                if (url.startsWith("https://chat.deepseek.com") ||
                    url.startsWith("https://www.deepseek.com") ||
                    url.startsWith("https://deepseek.com")
                ) return false
                return try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    true
                } catch (_: Exception) {
                    true
                }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                progress.visibility = View.VISIBLE
                setFabStatus("#4AF")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                progress.visibility = View.GONE
                setFabStatus("#4DB")
                if (prefs.getBoolean("auto_inject", true)) {
                    injectShim()
                    view?.postDelayed({ injectShim() }, 800)
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?
            ) {
                callback?.invoke(origin, true, false)
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                request?.grant(request.resources)
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback
                val intent = fileChooserParams?.createIntent()
                    ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                        type = "*/*"
                        addCategory(Intent.CATEGORY_OPENABLE)
                    }
                return try {
                    fileChooser.launch(intent)
                    true
                } catch (_: Exception) {
                    this@MainActivity.filePathCallback = null
                    false
                }
            }
        }

        setupDraggableFab()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })

        if (savedInstanceState != null) webView.restoreState(savedInstanceState)
        else webView.loadUrl(startUrl)
    }

    private fun buildUa(): String {
        val base = WebSettings.getDefaultUserAgent(this)
        return if (desktopMode) {
            base.replace("; wv", "").replace("Mobile", "").replace("Android", "X11; Linux x86_64")
        } else "$base DHarness/1.1"
    }

    private fun applySettingsFromPrefs() {
        desktopMode = prefs.getBoolean("desktop", false)
        webView.settings.userAgentString = buildUa()
    }

    private fun setFabStatus(colorHex: String) {
        try {
            val c = android.graphics.Color.parseColor(colorHex)
            (fabDot.background as? android.graphics.drawable.GradientDrawable)?.setColor(c)
                ?: fabDot.setBackgroundColor(c)
        } catch (_: Exception) {
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDraggableFab() {
        var downX = 0f
        var downY = 0f
        var startX = 0f
        var startY = 0f
        var dragging = false
        val touchSlop = 12f

        fab.setOnTouchListener { v, e ->
            val parent = v.parent as ViewGroup
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = v.x
                    startY = v.y
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) dragging = true
                    if (dragging) {
                        v.x = (startX + dx).coerceIn(0f, (parent.width - v.width).toFloat())
                        v.y = (startY + dy).coerceIn(0f, (parent.height - v.height).toFloat())
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!dragging) openSettings()
                    else {
                        // snap horizontal edge
                        val mid = parent.width / 2f
                        v.animate()
                            .x(if (v.x + v.width / 2 < mid) 8f else (parent.width - v.width - 8f))
                            .setDuration(120)
                            .start()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun openSettings() {
        settingsLauncher.launch(Intent(this, SettingsActivity::class.java))
    }


    private fun sendToolInstructions() {
        val msg = AGENT_INSTRUCTIONS
        val quoted = org.json.JSONObject.quote(msg)
        val js = """
            (function(){
              var t = $quoted;
              if (window.__DS_TOOL_SHIM__ && window.__DS_TOOL_SHIM__.send) {
                window.__DS_TOOL_SHIM__.send(t);
                return 'sent';
              }
              var input = document.querySelector('textarea[placeholder="Message DeepSeek"]') || document.querySelector('textarea');
              if (!input) return 'no input';
              var d = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value');
              if (d && d.set) d.set.call(input, t); else input.value = t;
              input.dispatchEvent(new Event('input', { bubbles: true }));
              input.focus();
              return 'filled';
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun injectShim() {
        val bridge = assets.open("native_bridge.js").bufferedReader().readText()
        val shim = assets.open("shim.js").bufferedReader().readText()
        // Prefer native tools; hide in-page FAB from shim (we have native FAB)
        val bootstrap = """
            window.__DS_SHIM_CONFIG__ = Object.assign(window.__DS_SHIM_CONFIG__ || {}, {
              hideFab: true,
              dedupe: ${prefs.getBoolean("dedupe", true)},
              nativePreferred: true
            });
            try { $bridge } catch(e) { console.error(e); }
            try { $shim } catch(e) { console.error(e); }
            try {
              var f = document.getElementById('__ds_shim_fab');
              if (f) f.style.display = 'none';
              var p = document.getElementById('__ds_shim_panel');
              if (p) p.hidden = true;
            } catch(e) {}
        """.trimIndent()
        webView.evaluateJavascript("(function(){$bootstrap})();", null)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.statusBars())
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        private val AGENT_INSTRUCTIONS = """
You have native tools via D-Harness. When you need a tool, reply with ONLY this JSON (no markdown):
{"tool":"run_js","args":{"code":"/* async JS; return a value */"}}

After TOOL_RESULT, continue the answer. Never invent results.

In code you may await:
- list_tools() — exact names + parameter schemas
- http_request / fetch_url with headers: await fetch_url(url,{method:'GET',headers:{Authorization:'Bearer …'}})
- github.me / github.repos / github.issues(owner,repo) / github.issue_comment(owner,repo,n,body) / github.pr — needs PAT key "github" in Settings
- memory.* agent scratchpad; keys.* secrets (never print values)
- fs.read/write/list/delete
- clipboard.read / clipboard.write (alias copy)
- device.info / device.battery / device.network
- toast(msg) / vibrate(ms) / notify(title,body) / share(text)

Rules: call list_tools if unsure; use github.* for GitHub; keys≠memory; no shell/exec; keep run_js small and return serializable values.
""".trimIndent()
    }
}
