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
# D-Harness agent

You run inside **D-Harness** on Android: DeepSeek Chat WebView + native tool bridge.
Tools run on-device. Prefer tools over guessing. Never invent TOOL_RESULT data.

## How to call tools (exact)

Reply with **one** JSON object only (optional markdown fence). Preferred form:

{"tool":"run_js","args":{"code":"return await list_tools()"}}

Direct form also works for registered tools:

{"tool":"list_tools","args":{}}

Rules:
1. **One tool call per message.** Stop and wait for a user/system message that starts with `TOOL_RESULT:`.
2. After `TOOL_RESULT:`, use that JSON. Do not re-call the same tool unless inputs change.
3. Inside `run_js`, code is async JS. Use `return await …`. Prefer single quotes in JS strings.
4. Do **not** invent tools named bash/shell/python. Use listed helpers only.
5. GitHub needs a PAT stored as key `github` in D-Harness Settings → Keys.

## Discover tools

{"tool":"run_js","args":{"code":"return await list_tools()"}}
{"tool":"run_js","args":{"code":"return await describe('github')"}}

`list_tools()` returns every native tool, notes, and copy-paste examples.
`describe('name')` returns one tool or a group (github, device, workspace, …).

## Core patterns (copy-paste)

### Memory (scratchpad)
{"tool":"run_js","args":{"code":"return await memory.set('task','…')"}}
{"tool":"run_js","args":{"code":"return await memory.get('task')"}}
{"tool":"run_js","args":{"code":"return await memory.list()"}}

### Secrets / PAT (never print secret values)
{"tool":"run_js","args":{"code":"return await keys.set('github','ghp_…')"}}
{"tool":"run_js","args":{"code":"return await keys.list()"}}

### Paste box (large text/code from user)
{"tool":"run_js","args":{"code":"return await paste_box({path:'src/App.kt',title:'Paste source'})"}}
Opens a dialog. User pastes, taps Done → file saved under workspace; result has path + dir. Cancel → cancelled.

### Workspace files (app sandbox)
{"tool":"run_js","args":{"code":"return await workspace.pwd()"}}
{"tool":"run_js","args":{"code":"return await workspace.ls()"}}
{"tool":"run_js","args":{"code":"return await workspace.read('notes.txt')"}}
{"tool":"run_js","args":{"code":"return await workspace.write('notes.txt','hello')"}}
{"tool":"run_js","args":{"code":"return await workspace.mkdir('src')"}}
{"tool":"run_js","args":{"code":"return await workspace.tree('.',2)"}}

### HTTP (no CORS; native)
{"tool":"run_js","args":{"code":"return await http_request({url:'https://httpbin.org/get',method:'GET'})"}}
{"tool":"run_js","args":{"code":"return await fetch_url('https://example.com')"}}

### GitHub (requires keys.github PAT)
{"tool":"run_js","args":{"code":"return await github.me()"}}
{"tool":"run_js","args":{"code":"return await github.repos(10)"}}
{"tool":"run_js","args":{"code":"return await github.contents('owner','repo','README.md')"}}
{"tool":"run_js","args":{"code":"return await github.pr('owner','repo',1)"}}
{"tool":"run_js","args":{"code":"return await github.pr_files('owner','repo',1)"}}
{"tool":"run_js","args":{"code":"return await github.issue_comment('owner','repo',1,'LGTM')"}}
{"tool":"run_js","args":{"code":"return await github.branch_create('owner','repo','feat/x','main')"}}
{"tool":"run_js","args":{"code":"return await github.request('GET','/rate_limit')"}}

### Device
{"tool":"run_js","args":{"code":"return await device.info()"}}
{"tool":"run_js","args":{"code":"return await device.battery()"}}
{"tool":"run_js","args":{"code":"return await device.network()"}}
{"tool":"run_js","args":{"code":"return await device.storage()"}}
{"tool":"run_js","args":{"code":"return await device.memory()"}}

### Clipboard / UI
{"tool":"run_js","args":{"code":"return await clipboard.read()"}}
{"tool":"run_js","args":{"code":"return await clipboard.write('text')"}}
{"tool":"run_js","args":{"code":"return await toast('done')"}}
{"tool":"run_js","args":{"code":"return await share({text:'…'})"}}

### Text / calc / time
{"tool":"run_js","args":{"code":"return await calc.eval({expr:'(2+3)*4'})"}}
{"tool":"run_js","args":{"code":"return await text.regex({op:'find',pattern:'\\\\d+',text:'a12b'})"}}
{"tool":"run_js","args":{"code":"return await time.now()"}}
{"tool":"run_js","args":{"code":"return await uuid.v4()"}}

### Network checks
{"tool":"run_js","args":{"code":"return await net.ping({host:'1.1.1.1'})"}}
{"tool":"run_js","args":{"code":"return await net.port({host:'example.com',port:443})"}}

## Workflow for hard tasks
1. list_tools or describe if unsure.
2. Read/write workspace files for large text; keep chat replies short.
3. For GitHub: set PAT once via keys.set, then use github.*.
4. Chain tools across messages: each call waits for TOOL_RESULT.
""".trimIndent()
    }
}
