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
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.Executors
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private lateinit var fab: View
    private lateinit var fabDot: View
    private lateinit var rootLayout: FrameLayout
    private var popupWebView: WebView? = null
    private var popupContainer: FrameLayout? = null
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
            injectShim(force = true)
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

        // Location optional for geo.get — request once (user can deny)
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            val need = arrayOf(
                android.Manifest.permission.ACCESS_COARSE_LOCATION,
                android.Manifest.permission.ACCESS_FINE_LOCATION
            ).filter {
                androidx.core.content.ContextCompat.checkSelfPermission(this, it) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            }
            if (need.isNotEmpty()) {
                androidx.core.app.ActivityCompat.requestPermissions(this, need.toTypedArray(), 42)
            }
        }
        prefs = getSharedPreferences("dharness_settings", MODE_PRIVATE)
        desktopMode = prefs.getBoolean("desktop", false)

        rootLayout = findViewById(R.id.root)
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

        // Keyboard: pad root so composer stays above IME (better-deepseek style)
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { v, insets ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val bottom = maxOf(ime.bottom, if (ime.bottom > 0) 0 else sys.bottom)
            // Only lift for keyboard; status bar stays immersive
            v.setPadding(0, 0, 0, ime.bottom)
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
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            textZoom = prefs.getInt("text_zoom", 100).coerceIn(80, 150)
            userAgentString = buildUa()
        }


        webView.addJavascriptInterface(HarnessBridge(this, webView), "DHarness")
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return false
                if (LinkRouting.shouldOpenExternally(uri)) {
                    return openExternalUrl(uri)
                }
                return false
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                progress.visibility = View.VISIBLE
                setFabStatus("#4AF")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                // Quiet update check once per process
                if (!prefs.getBoolean("update_checked_session", false)) {
                    prefs.edit().putBoolean("update_checked_session", true).apply()
                    checkForUpdates(false)
                }
                progress.visibility = View.GONE
                setFabStatus("#4DB")
                if (prefs.getBoolean("auto_inject", true)) {
                    // SPA: inject after DOM settles; retry if first pass races React
                    view?.postDelayed({ injectShim(force = false) }, 300)
                    view?.postDelayed({ injectShim(force = false) }, 1200)
                    view?.postDelayed({ injectShim(force = false) }, 3000)
                    view?.postDelayed({ injectShim(force = false) }, 7000)
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

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean {
                if (resultMsg == null) return false
                val popup = WebView(this@MainActivity).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = buildUa()
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            v: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            val uri = request?.url ?: return false
                            if (LinkRouting.shouldCapturePopupInApp(uri)) return false
                            if (LinkRouting.shouldOpenExternally(uri)) {
                                openExternalUrl(uri)
                                closePopup()
                                return true
                            }
                            return false
                        }
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onCloseWindow(window: WebView?) {
                            closePopup()
                        }
                    }
                }
                attachPopup(popup)
                val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                transport.webView = popup
                resultMsg.sendToTarget()
                return true
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
                if (popupWebView != null) {
                    closePopup()
                    return
                }
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })

        if (savedInstanceState != null) webView.restoreState(savedInstanceState)
        else webView.loadUrl(startUrl)
    }

    private fun buildUa(): String {
        val base = WebSettings.getDefaultUserAgent(this)
        return if (desktopMode) UserAgentHelper.desktopLike(base)
        else UserAgentHelper.chromeLikeMobile(base)
    }

    private fun openExternalUrl(uri: android.net.Uri): Boolean {
        return try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun closePopup() {
        val popup = popupWebView ?: return
        popupWebView = null
        popupContainer?.let { c ->
            c.removeView(popup)
            rootLayout.removeView(c)
        }
        popupContainer = null
        try { popup.destroy() } catch (_: Exception) { }
    }

    private fun attachPopup(popup: WebView) {
        closePopup()
        val container = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0xFF000000.toInt())
            addView(
                popup,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
        popupContainer = container
        popupWebView = popup
        rootLayout.addView(container)
    }

    private fun applySettingsFromPrefs() {
        desktopMode = prefs.getBoolean("desktop", false)
        webView.settings.userAgentString = buildUa()
        webView.settings.textZoom = prefs.getInt("text_zoom", 100).coerceIn(80, 150)
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
        // Prefer async shim.send; also force-click send so the message is actually submitted.
        val js = """
            (async function(){
              var t = $quoted;
              try {
                if (window.__DS_TOOL_SHIM__ && typeof window.__DS_TOOL_SHIM__.send === 'function') {
                  var ok = await window.__DS_TOOL_SHIM__.send(t);
                  return ok ? 'sent-shim' : 'shim-fail';
                }
              } catch (e) { /* fall through */ }
              var input = document.querySelector('textarea') ||
                document.querySelector('[contenteditable="true"]');
              if (!input) return 'no-input';
              input.focus();
              var proto = input.tagName === 'TEXTAREA'
                ? HTMLTextAreaElement.prototype
                : HTMLInputElement.prototype;
              var d = Object.getOwnPropertyDescriptor(proto, 'value');
              if (d && d.set) d.set.call(input, t); else if ('value' in input) input.value = t;
              else input.textContent = t;
              input.dispatchEvent(new Event('input', { bubbles: true }));
              input.dispatchEvent(new Event('change', { bubbles: true }));
              await new Promise(function(r){ setTimeout(r, 80); });
              var btn = document.querySelector('div[role="button"][aria-disabled="false"]') ||
                Array.prototype.find.call(document.querySelectorAll('button,[role="button"]'), function(b){
                  var al = (b.getAttribute('aria-label')||'') + ' ' + (b.textContent||'');
                  return /send|submit/i.test(al) && b.getAttribute('aria-disabled') !== 'true';
                });
              if (btn) { btn.click(); return 'sent-click'; }
              input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true }));
              return 'sent-enter';
            })();
        """.trimIndent()
        webView.postDelayed({
            webView.evaluateJavascript(js) { result ->
                android.util.Log.i("DHarness", "sendToolInstructions -> $result")
                val ok = result != null && (
                    result.contains("sent") || result.contains("filled")
                )
                if (!ok) {
                    Toast.makeText(this, "Could not send instructions — open chat first", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Tool instructions sent", Toast.LENGTH_SHORT).show()
                }
            }
        }, 400)
    }

    private fun injectShim(force: Boolean = false) {
        if (force) {
            webView.evaluateJavascript("window.__DS_FORCE_REINJECT__=true;", null)
        }

        try {
            val bridgeB64 = android.util.Base64.encodeToString(
                assets.open("native_bridge.js").readBytes(), android.util.Base64.NO_WRAP
            )
            val shimB64 = android.util.Base64.encodeToString(
                assets.open("shim.js").readBytes(), android.util.Base64.NO_WRAP
            )
            val themeB64 = android.util.Base64.encodeToString(
                assets.open("claude_theme.js").readBytes(), android.util.Base64.NO_WRAP
            )
            val autoTheme = prefs.getBoolean("auto_theme", false)
            val js = """
                (function(){
                  function dec(b){
                    try {
                      if (window.atob) {
                        var bin = atob(b);
                        var bytes = new Uint8Array(bin.length);
                        for (var i=0;i<bin.length;i++) bytes[i] = bin.charCodeAt(i);
                        if (window.TextDecoder) return new TextDecoder('utf-8').decode(bytes);
                        var s=''; for (var j=0;j<bytes.length;j++) s+=String.fromCharCode(bytes[j]); return s;
                      }
                    } catch(e) {}
                    return '';
                  }
                  window.__DS_SHIM_CONFIG__ = Object.assign(window.__DS_SHIM_CONFIG__ || {}, {
                    hideFab: true,
                    dedupe: true,
                    nativePreferred: true,
                    sendTimeoutMs: 5000,
                    hideFlashMs: 80,
                    scanThrottleMs: 800,
                    fallbackScanMs: 2500
                  });
                  // Skip full re-inject if same stable shim already live (avoids clearing session state)
                  var existing = window.__DS_TOOL_SHIM__;
                  if (existing && existing.version && String(existing.version).indexOf('7.5') === 0 && !window.__DS_FORCE_REINJECT__) {
                    console.log('[D-Harness] shim already live', existing.version);
                    return 'already';
                  }
                  try {
                    if (existing && existing.stop) existing.stop();
                  } catch(e) {}
                  var bridge = dec('$bridgeB64');
                  var shim = dec('$shimB64');
                  try { (0, eval)(bridge); } catch(e) { console.error('bridge', e); }
                  try { (0, eval)(shim); } catch(e) { console.error('shim', e); }
                  if ($autoTheme) {
                    try {
                      var theme = dec('$themeB64');
                      (0, eval)(theme);
                      console.log('[D-Harness] Claude theme injected');
                    } catch(e) { console.error('theme', e); }
                  } else {
                    // Remove theme if previously injected and now off
                    try {
                      var st = document.getElementById('claude-ds-theme-v3');
                      if (st) st.remove();
                      var ft = document.getElementById('claude-ds-fonts-v3');
                      if (ft) ft.remove();
                    } catch(e) {}
                  }
                  try {
                    var f = document.getElementById('__ds_shim_fab'); if (f) f.style.display='none';
                    var p = document.getElementById('__ds_shim_panel'); if (p) p.hidden = true;
                  } catch(e) {}
                  try { delete window.__DS_FORCE_REINJECT__; } catch(e) {}
                  var ok = !!(window.__DS_TOOL_SHIM__);
                  console.log('[D-Harness] inject', ok ? 'ok' : 'FAIL', 'v=', window.__DS_TOOL_SHIM__ && window.__DS_TOOL_SHIM__.version, 'theme=', $autoTheme);
                  return ok ? 'ok' : 'fail';
                })();
            """.trimIndent()
            webView.evaluateJavascript(js) { result ->
                android.util.Log.i("DHarness", "inject result=$result")
                webView.postDelayed({
                    webView.evaluateJavascript(
                        """
                        (async function(){
                          try {
                            if (!window.__DS_TOOL_SHIM__) return 'no-shim';
                            if (window.__DS_TOOL_SHIM__.ping) {
                              var r = await window.__DS_TOOL_SHIM__.ping();
                              return JSON.stringify(r);
                            }
                            return 'shim-v' + (window.__DS_TOOL_SHIM__.version || '?');
                          } catch(e) { return 'err:'+e; }
                        })();
                        """.trimIndent(),
                        { ping -> android.util.Log.i("DHarness", "ping=$ping") }
                    )
                }, 800)
            }
        } catch (e: Exception) {
            android.util.Log.e("DHarness", "inject failed", e)
        }
    }


    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onPause() {
        super.onPause()
        // Keep WebView JS alive while agent tool-chain runs in background
        if (!AgentService.running) {
            webView.onPause()
        }
        CookieManager.getInstance().flush()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        CookieManager.getInstance().flush()
        WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.statusBars())
    }

    override fun onDestroy() {
        closePopup()
        CookieManager.getInstance().flush()
        webView.destroy()
        super.onDestroy()
    }

    fun checkForUpdates(interactive: Boolean) {
        val installed = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "0"
        } catch (_: Exception) { "0" }
        Executors.newSingleThreadExecutor().execute {
            val info = UpdateChecker.fetchLatest()
            runOnUiThread {
                if (info == null) {
                    if (interactive) Toast.makeText(this, "Update check failed", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                if (!UpdateChecker.isNewer(info.tag, installed)) {
                    if (interactive) Toast.makeText(this, "Up to date (v$installed)", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                MaterialAlertDialogBuilder(this)
                    .setTitle("Update available: v${info.tag}")
                    .setMessage(info.body.take(800).ifBlank { info.name })
                    .setPositiveButton("Open release") { _, _ ->
                        openExternalUrl(android.net.Uri.parse(info.htmlUrl))
                    }
                    .setNegativeButton("Later", null)
                    .show()
            }
        }
    }

    companion object {
        private val AGENT_INSTRUCTIONS = """
# D-Harness agent

Native Android tool bridge for DeepSeek Chat. **Primary API: `DHarness`** (JavascriptInterface).

## Discover (do this first)
```
return await (async () => {
  const N = window.__DHarnessNative || window.DHarness;
  return {
    selftest: await N.selftest(),
    help: await N.help(''),
    caps: await N.capabilities()
  };
})()
```

## Four calling conventions
1. **DHarness.*** (best): `DHarness.workspaceLs(null)`, `DHarness.geoGet(8000)`, `DHarness.exec(...)`
2. **run_js globals**: `{"tool":"run_js","description":"List files","args":{"code":"return await workspace.ls()"}}`
3. **Flat dispatch**: `{"tool":"share","args":{"text":"hi"}}`
4. **Group dispatch**: `{"tool":"workspace","args":{"op":"ls"}}`

Prefer **native DHarness** over WebView APIs (clipboard, geo, sensors, wakelock).

## Result envelope
TOOL_RESULT is JSON: `{ok, data, result, error?, meta:{ms,tool}}`.

## Rules
- One tool JSON per reply; wait for TOOL_RESULT.
- Never invent results. Use description field on tool calls.
- Background agent keeps tool chains alive if the user leaves the app.
"""
    }
}
