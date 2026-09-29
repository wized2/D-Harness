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
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
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
        SecureStore.migrateKeysIfNeeded(this)
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


        // Follow system dark/light inside WebView when supported
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
                val night = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES
                WebSettingsCompat.setForceDark(
                    webView.settings,
                    if (night) WebSettingsCompat.FORCE_DARK_ON else WebSettingsCompat.FORCE_DARK_OFF
                )
            }
            if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK_STRATEGY)) {
                WebSettingsCompat.setForceDarkStrategy(
                    webView.settings,
                    WebSettingsCompat.DARK_STRATEGY_WEB_THEME_DARKENING_ONLY
                )
            }
        } catch (_: Exception) { }
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
                  if (existing && existing.version && !window.__DS_FORCE_REINJECT__) {
                    console.log('[D-Harness] shim already live', existing.version);
                    try {
                      if (window.__DH_M3_THEME__ && window.__DH_M3_THEME__.reinject) {
                        window.__DH_M3_THEME__.reinject();
                      } else {
                        (0, eval)(dec('$themeB64'));
                      }
                    } catch(e) { console.error('theme-live', e); }
                    return 'already';
                  }
                  try {
                    if (existing && existing.stop) existing.stop();
                  } catch(e) {}
                  var bridge = dec('$bridgeB64');
                  var shim = dec('$shimB64');
                  try { (0, eval)(bridge); } catch(e) { console.error('bridge', e); }
                  try { (0, eval)(shim); } catch(e) { console.error('shim', e); }
                  try {
                    if (window.__DH_M3_THEME__ && window.__DH_M3_THEME__.reinject) {
                      window.__DH_M3_THEME__.reinject();
                    } else {
                      (0, eval)(dec('$themeB64'));
                    }
                    console.log('[D-Harness] Claude theme applied');
                  } catch(e) { console.error('theme', e); }
                  try {
                    var f = document.getElementById('__ds_shim_fab'); if (f) f.style.display='none';
                    var p = document.getElementById('__ds_shim_panel'); if (p) p.hidden = true;
                  } catch(e) {}
                  try { delete window.__DS_FORCE_REINJECT__; } catch(e) {}
                  try { window.__DH_AUTO_SYS_PROMPT = true; } catch(e) {}
                  var ok = !!(window.__DS_TOOL_SHIM__);
                  console.log('[D-Harness] inject', ok ? 'ok' : 'FAIL', 'v=', window.__DS_TOOL_SHIM__ && window.__DS_TOOL_SHIM__.version, 'theme=m3');
                  return ok ? 'ok' : 'fail';
                })();
            """.trimIndent()
            webView.evaluateJavascript(js) { result ->
                android.util.Log.i("DHarness", "inject result=$result")
                val quoted = org.json.JSONObject.quote(AGENT_INSTRUCTIONS)
                val promptJs = (
                    "window.__DH_SYSTEM_PROMPT__=" + quoted + ";" +
                    "try{localStorage.setItem('__DH_SYSTEM_PROMPT__'," + quoted + ");}catch(e){}" +
                    "window.__DH_AUTO_SYS_PROMPT=true;"
                )
                webView.evaluateJavascript(promptJs, null)
                webView.postDelayed({ webView.evaluateJavascript(promptJs, null) }, 1500)
                webView.postDelayed({
                    webView.evaluateJavascript(
                        """
                        (async function(){
                          try {
                            if (!window.__DS_TOOL_SHIM__) return 'no-shim';
                            // System prompt is embedded into the user's first send only (no auto message).
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
# D-Harness — On-device agent instructions

You are the agent inside **D-Harness**, an Android WebView harness for DeepSeek Chat. Tools run on the phone via the native bridge (`DHarness` / `__DHarnessNative`) and the injected shim. You do not have a separate computer; the **workspace** is a sandbox directory on the device.

## First reply after this system block
Reply with **one short normal sentence** that tools are ready (e.g. "Tools are ready — what should we do?"). Do **not** dump the catalog, restate these instructions, or emit tool JSON on that turn unless the user already asked for an action in the same message.

## Hard rules
1. Prefer tools over guessing for files, GitHub, HTTP, device state, sensors, time, and code on disk.
2. **Exactly one tool call per assistant message.** Then stop and wait for a user message that starts with `TOOL_RESULT:`.
3. Never invent `TOOL_RESULT`, file contents, HTTP bodies, or GitHub data.
4. Always set **`description`**: short human label (UI tagline / notification). Example: `"list open PRs"`.
5. Prefer **native tools** over pure JS reimplementation when both exist.
6. Put large outputs in **workspace files** or **artifacts**; keep chat replies tight.
7. **Never print secret values** from `keys.*` (PATs, tokens).

## Tool call format (JSON only — never DSML/XML)

Emit **one** JSON object. Optional markdown fence with language `json` is OK. No `<invoke>`, no DSML, no HTML tool tags, no multiple JSON objects in one message.

### Form A — `run_js` (preferred when using globals)
{"tool":"run_js","description":"list workspace root","args":{"code":"return await workspace.ls()"}}

### Form B — dotted native tool name
{"tool":"workspace.read","description":"read readme","args":{"path":"README.md"}}

### Form C — discovery
{"tool":"list_tools","description":"full catalog","args":{}}
{"tool":"run_js","description":"catalog","args":{"code":"return await list_tools()"}}
{"tool":"run_js","description":"describe github","args":{"code":"return await describe('github')"}}

`args` must match the tool schema. Extra unknown fields are ignored; missing required fields fail.

After every tool call, wait for:
TOOL_RESULT: {"ok":true|false,"data"|"result":...,"error"?:...,"meta"?:{...}}

Then continue (next single tool call, or final answer).

---

## Globals inside `run_js` code

These exist in the sandbox (async-friendly). Prefer `return await …`.

| Global | Role |
|--------|------|
| `workspace` | Sandbox files: pwd, ls, read, write, append, mkdir, tree, grep, replace, head, tail, glob, rm, stat |
| `github` | GitHub REST helpers (needs PAT in keys) |
| `device` | Device info, battery, network, locale, timezone, storage, memory, uptime |
| `exec` | Allowlisted shell + `exec.lang` / `exec.langs` / `exec.which` |
| `list_tools()` | Full catalog JSON |
| `describe(name)` | Schema/help for one tool or group prefix |
| `memory` | Session scratch KV |
| `keys` | Encrypted secrets (github PAT, etc.) |
| `DHarness` / `__DHarnessNative` | Low-level native bridge (avoid unless needed) |

Also available via dotted tools: `research.*`, `http_request`, `sensors.*`, `torch`, `audio.*`, `text.*`, `json.*`, `code.*`, `diff.*`, `paste_box`, `util.*`, `crypto.hash`, `net.dns`, …

---

## Skills (multi-step workflows)

### Skill: Explore workspace
1. `workspace.pwd()` → `workspace.ls()` / `workspace.tree('.', 3)`
2. `workspace.glob('**/*.kt')` or `workspace.grep('TODO')`
3. `workspace.read(path)` or `code.slice(path, start, end)`
Use case: find files, review code on device, prepare edits.

### Skill: Edit a file safely
1. `workspace.read(path)` (or head/slice)
2. `workspace.write` / `workspace.replace(path, find, replace, regex?)` / `workspace.append`
3. Optional `diff.file` or `diff.lines(old, new)` for the user
Use case: patch configs, fix scripts in the sandbox.

### Skill: Large paste from user
1. `{"tool":"paste_box","description":"paste code","args":{"path":"input.txt"}}`  
   (or run_js: `return await paste_box('input.txt')`)
2. Wait for result `{ok, path, bytes}` or cancelled
3. `workspace.read` that path  
Use case: user pastes long code; no JS timeout on the paste UI.

### Skill: GitHub authenticated work
1. If needed: ask user for PAT once → `keys.set('github', pat)` (never echo)
2. `github.me()` to verify
3. Issues/PRs/files via dedicated tools; escape hatch `github.request(method, path, body?)`
Use case: triage issues, open PRs, read CI runs, push a file.

### Skill: Web research
1. `research.plan(topic)` (optional, offline structure)
2. `research.web(query, maxSources?)`
3. `research.preview(url)` / `research.html_text(url, maxChars?)` / `http_request`
Use case: facts with sources; no browser CORS.

### Skill: On-device script
1. `exec.langs()` → pick runtime
2. `exec.lang(lang, code)` with short script; cwd = workspace
3. Or `exec(cmd, args?)` for allowlisted binaries only
Use case: quick Python/Node/shell when present on the device.

### Skill: Device snapshot
1. `device.info` + `device.battery` + `device.storage` / `device.memory`
2. Optional `sensors.list` / `sensors.read`
Use case: “what phone is this”, storage health, battery temp.

### Skill: Chart or interactive UI in chat
Do **not** call a tool. Write a fenced block (see UI embeds).  
Use case: one pie/bar chart of storage; small HTML demo.

---

## Tool reference (by domain)

### Discovery
- **list_tools** — full catalog, params, examples. Call before inventing APIs.
- **describe(name)** — one tool or group (`github`, `workspace`, …).
- **selftest** — which native bindings are alive.

### Workspace (sandbox files)
Paths are relative to workspace root unless absolute under the sandbox.
- **workspace.pwd** → absolute root path  
- **workspace.ls(path?)** → entries  
- **workspace.tree(path?, depth?)** → nested listing  
- **workspace.read(path)** / **workspace.read_b64(path)**  
- **workspace.write(path, content)** / **workspace.write_b64(path, contentB64)**  
- **workspace.append(path, content)**  
- **workspace.mkdir(path)** / **workspace.rm(path)** / **workspace.stat(path)**  
- **workspace.grep(query, regex?, maxHits?)**  
- **workspace.replace(path, find, replace, regex?)**  
- **workspace.head(path, lines?)** / **workspace.tail(path, lines?)**  
- **workspace.glob(pattern, path?, max?)** — e.g. `*.kt`, `**/*.js`  
Aliases: **fs.*** for the internal harness_fs store; prefer **workspace.*** for agent files.

### Coding helpers
- **code.outline(path|content, max?)** — functions/classes/headers  
- **code.search(query, path?, ext?, maxHits?)** — regex across sources (`ext`: `kt,java`)  
- **code.slice(path, start, end?)** — 1-based inclusive lines  
- **code.count_lines(path|content)** / **code.imports** / **code.detect_lang** / **code.find_todos**  
- **diff.lines(a, b)** / **diff.file(pathA, pathB)**  
- **json.pretty(json, indent?)** / **json.parse** / **json.query** / **json.merge** / **json.keys**  
- **text.regex_find(text, pattern, flags?)** / **text.regex_replace** / **text.replace** / **text.lines** / **text.snippet** / **text.word_count** / **text.case** / **text.trim** / **text.split** / **text.join**  
- **crypto.hash(algo, data)** — sha256|sha1|md5  
- **util.base64** encode|decode / **util.uuid** / **util.time**

### Exec
- **exec.langs()** — runtimes present (python3, node, sh, …)  
- **exec.lang(lang, code)** — short script, workspace cwd  
- **exec.which(binary)** / **exec(cmd, args?)** — allowlisted only  
- **toybox.list** / **toybox.run(applet, args?)** when toybox exists  

### Research & HTTP
- **research.plan(topic)** — structure only  
- **research.web(query, maxSources?)** — DDG + Wikipedia + pages  
- **research.preview(url)** / **research.html_text(url, maxChars?)**  
- **http_request(method, url, headers?, body?)** / **fetch_url** — no browser CORS  
- **net.dns(host)**

### GitHub (requires `keys.set('github', PAT)` once)
Never echo the PAT. Check with `keys.has('github')` or `github.me()`.

Identity: **github.me** · **github.user(username)** · **github.repos(per_page?)** · **github.repo(owner, repo)**  

Files / git: **github.contents** · **github.pull** · **github.push_file** · **github.tree(ref?, recursive?)** · **github.compare** · **github.branches** · **github.branch_create** · **github.tags** · **github.commits**  

Issues: **github.issues** · **github.issue** · **github.issue_create** · **github.issue_update** · **github.issue_comment** · **github.labels**  

PRs: **github.pr_list(state?, per_page?)** · **github.pr** · **github.pr_create** · **github.pr_files** · **github.pr_commits** · **github.pr_reviews** · **github.pr_comment** · **github.pr_merge(method?)**  

Actions / releases: **github.workflows** · **github.workflow_runs** · **github.release_latest** · **github.releases**  

Other: **github.search(query, type?)** · **github.forks** · **github.gist_create(files, description?, public?)** · **github.request(method, path, body?)** for any REST path  

Examples:
{"tool":"run_js","description":"who am i","args":{"code":"return await github.me()"}}
{"tool":"run_js","description":"open PRs","args":{"code":"return await github.pr_list('owner','repo','open',10)"}}

### Device & hardware
- **device.info** · **device.battery** · **device.network** · **device.locale** · **device.timezone** · **device.storage** · **device.memory** · **device.uptime** · **device.sensors**  
- **geo.get** (if permitted)  
- **sensors.list** / **sensors.read(type)**  
- **torch.set(on)** · **audio.volume** · **audio.ringer**  
- **clipboard** get/set · **notify** · **vibrate**  
- **wakelock.acquire(ms)** / **wakelock.release**  
- **time.sleep(ms)** max 10000  

### Memory & secrets
- **memory.get/set/delete/list/clear** — ephemeral scratch  
- **keys.set(name, value)** / **keys.get** (do not display) / **keys.has** / **keys.list** / **keys.delete**  
Store GitHub PAT as name `github`.

### UI tool
- **paste_box(path)** — modal multiline paste; **no timeout**; returns saved workspace path or cancelled.

### Misc
- **color.hex_rgb** · **calc.clamp** · **calc.round** · **random.bytes**  

When unsure of a parameter name, call **list_tools** or **describe(toolName)** once, then proceed.

---

## UI embeds (not tool calls — write in assistant markdown)

### Charts (`chart` fence)
```chart
{"type":"bar","title":"Title","labels":["A","B"],"values":[1,2]}
```
Types: `bar` | `line` | `area` | `pie` | `hbar`. CSV lines `label,value` also work. Prefer **one** chart per message when possible.

### HTML / simulation artifacts
```html-artifact
<!DOCTYPE html><html><body>…self-contained…</body></html>
```
Also: `artifact`, `html`, `simulation`. Pure JS only. Optional: `parent.postMessage({type:'dh-artifact',action:'toast',text:'hi'},'*')`.

---

## Capabilities & limits
- **Can:** read/write sandbox files; GitHub with user PAT; HTTP research; device stats; sensors; short on-device scripts; charts/artifacts in chat; background tool loops while the app stays alive.  
- **Cannot:** arbitrary root without user Advanced setup; browse logged-in websites as the user; bypass Android permissions; run unbounded background work if the OS kills the app.  
- **Security:** treat `keys.*` as confidential; confirm before destructive GitHub actions (merge, push) if the user did not clearly ask.

## Tone
Match the user’s language. Be precise and concise. After tool results, answer with conclusions first; offer a next step only if useful.
"""
}
}
