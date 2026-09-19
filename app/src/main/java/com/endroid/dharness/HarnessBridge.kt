package com.endroid.dharness

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class HarnessBridge(
    private val context: Context,
    private val webView: WebView
) {
    private val io = Executors.newFixedThreadPool(6)
    private val mem = context.getSharedPreferences("dharness_mem", Context.MODE_PRIVATE)
    private val keys = context.getSharedPreferences("dharness_keys", Context.MODE_PRIVATE)
    private val settings = context.getSharedPreferences("dharness_settings", Context.MODE_PRIVATE)
    private val fsRoot = File(context.filesDir, "harness_fs").also { it.mkdirs() }
    private val inFlight = ConcurrentHashMap<String, Long>()

    private fun deliver(callbackId: String, json: String) {
        val idQ = JSONObject.quote(callbackId)
        val bodyQ = JSONObject.quote(json)
        webView.post {
            webView.evaluateJavascript(
                "window.__dHarnessCb&&window.__dHarnessCb($idQ,$bodyQ);",
                null
            )
        }
    }

    /** Exact tool catalog with schemas — no slash-joined fake names. */
    @JavascriptInterface
    fun listTools(): String {
        val tools = JSONArray()
        fun tool(name: String, desc: String, params: JSONObject) {
            tools.put(JSONObject().put("name", name).put("description", desc).put("params", params))
        }
        tool("list_tools", "List all tools with schemas", JSONObject())
        tool("describe", "Describe one tool by name", JSONObject().put("name", "string"))
        tool("http_request", "HTTP with method/headers/body (native, no CORS)",
            JSONObject().put("url", "string").put("method", "string?").put("headers", "object?")
                .put("body", "string?").put("timeout_ms", "number?"))
        tool("fetch_url", "Alias of http_request GET/POST", JSONObject().put("url", "string").put("method", "string?").put("headers", "object?").put("body", "string?"))
        tool("github.request", "GitHub REST API (uses PAT from settings)",
            JSONObject().put("method", "string?").put("path", "string").put("body", "object|string?"))
        tool("github.me", "GET /user", JSONObject())
        tool("github.repos", "List your repos", JSONObject().put("per_page", "number?"))
        tool("github.issues", "List issues", JSONObject().put("owner", "string").put("repo", "string").put("state", "string?"))
        tool("github.issue_comment", "Comment on issue/PR", JSONObject().put("owner", "string").put("repo", "string").put("number", "number").put("body", "string"))
        tool("github.pr", "Get pull request", JSONObject().put("owner", "string").put("repo", "string").put("number", "number"))
        tool("memory.get", "Agent scratch KV (session-ish)", JSONObject().put("key", "string"))
        tool("memory.set", "Set agent memory", JSONObject().put("key", "string").put("value", "string"))
        tool("memory.delete", "Delete memory key", JSONObject().put("key", "string"))
        tool("memory.list", "List memory keys", JSONObject())
        tool("memory.clear", "Clear all memory", JSONObject())
        tool("keys.get", "Get secret/API key stored in Settings", JSONObject().put("name", "string"))
        tool("keys.set", "Store secret (prefer Settings UI for PAT)", JSONObject().put("name", "string").put("value", "string"))
        tool("keys.delete", "Delete secret", JSONObject().put("name", "string"))
        tool("keys.list", "List secret names (not values)", JSONObject())
        tool("fs.read", "Read app-private file", JSONObject().put("path", "string"))
        tool("fs.write", "Write app-private file", JSONObject().put("path", "string").put("content", "string"))
        tool("fs.list", "List files under prefix", JSONObject().put("prefix", "string?"))
        tool("fs.delete", "Delete file", JSONObject().put("path", "string"))
        tool("clipboard.read", "Read clipboard text", JSONObject())
        tool("clipboard.write", "Write clipboard (alias: clipboard.copy)", JSONObject().put("text", "string"))
        tool("clipboard.copy", "Alias of clipboard.write", JSONObject().put("text", "string"))
        tool("file.save", "Share/save text via system sheet", JSONObject().put("filename", "string").put("content", "string").put("mime", "string?"))
        tool("share", "Share plain text", JSONObject().put("text", "string"))
        tool("device.info", "Device model/sdk/app version", JSONObject())
        tool("device.battery", "Battery percent + charging", JSONObject())
        tool("device.network", "Online / wifi / cellular", JSONObject())
        tool("toast", "Show Android toast", JSONObject().put("message", "string"))
        tool("vibrate", "Vibrate ms (10-800)", JSONObject().put("ms", "number?"))
        tool("notify", "System notification", JSONObject().put("title", "string").put("body", "string?"))
        tool("appInfo", "Same as device.info", JSONObject())
        return JSONObject()
            .put("tools", tools)
            .put("native", true)
            .put("version", "1.2.0")
            .put("notes", JSONObject()
                .put("memory", "Scratchpad for the agent during a chat")
                .put("keys", "Secrets (PAT, API keys) — set in Settings; never echo values")
                .put("github", "Requires PAT named github or github_pat in Settings")
                .put("exec", "Not available (no shell on device for safety)")
            )
            .toString()
    }

    @JavascriptInterface
    fun describeTool(name: String): String {
        val arr = JSONObject(listTools()).getJSONArray("tools")
        for (i in 0 until arr.length()) {
            val t = arr.getJSONObject(i)
            if (t.getString("name") == name) return t.toString()
        }
        return JSONObject().put("error", "unknown tool: $name").toString()
    }

    @JavascriptInterface
    fun appInfo(): String {
        val p = context.packageManager.getPackageInfo(context.packageName, 0)
        return JSONObject()
            .put("name", "D-Harness")
            .put("version", p.versionName)
            .put("package", context.packageName)
            .put("sdk", Build.VERSION.SDK_INT)
            .put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL)
            .toString()
    }

    @JavascriptInterface fun deviceInfo(): String = appInfo()

    @JavascriptInterface
    fun battery(): String {
        return try {
            val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            JSONObject().put("percent", if (scale > 0) level * 100.0 / scale else -1.0)
                .put("charging", charging).toString()
        } catch (e: Exception) {
            JSONObject().put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun network(): String {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            JSONObject()
                .put("online", caps != null)
                .put("wifi", caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true)
                .put("cellular", caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true)
                .toString()
        } catch (e: Exception) {
            JSONObject().put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun toast(message: String) {
        webView.post { Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show() }
    }

    @JavascriptInterface
    fun vibrate(ms: Int) {
        val v = if (Build.VERSION.SDK_INT >= 31) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        val d = ms.toLong().coerceIn(10, 800)
        if (Build.VERSION.SDK_INT >= 26) {
            v.vibrate(VibrationEffect.createOneShot(d, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(d)
        }
    }

    @JavascriptInterface
    fun notify(title: String, body: String): String {
        return try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val chId = "dharness"
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(
                    NotificationChannel(chId, "D-Harness", NotificationManager.IMPORTANCE_DEFAULT)
                )
            }
            val n = NotificationCompat.Builder(context, chId)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(body)
                .setAutoCancel(true)
                .build()
            nm.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), n)
            JSONObject().put("ok", true).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun clipboardWrite(text: String): String {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("dharness", text))
        return JSONObject().put("ok", true).toString()
    }

    @JavascriptInterface
    fun clipboardRead(): String {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val t = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() ?: ""
        return JSONObject().put("text", t).toString()
    }

    @JavascriptInterface fun memoryGet(key: String): String =
        JSONObject().put("value", mem.getString(key, null)).toString()
    @JavascriptInterface fun memorySet(key: String, value: String): String {
        mem.edit().putString(key, value).apply(); return JSONObject().put("ok", true).toString()
    }
    @JavascriptInterface fun memoryDelete(key: String): String {
        mem.edit().remove(key).apply(); return JSONObject().put("ok", true).toString()
    }
    @JavascriptInterface fun memoryList(): String {
        val arr = JSONArray(); mem.all.keys.forEach { arr.put(it) }
        return JSONObject().put("keys", arr).toString()
    }
    @JavascriptInterface fun memoryClear(): String {
        mem.edit().clear().apply(); return JSONObject().put("ok", true).toString()
    }

    @JavascriptInterface fun keysGet(name: String): String =
        JSONObject().put("value", keys.getString(name, null)).toString()
    @JavascriptInterface fun keysSet(name: String, value: String): String {
        keys.edit().putString(name, value).apply(); return JSONObject().put("ok", true).toString()
    }
    @JavascriptInterface fun keysDelete(name: String): String {
        keys.edit().remove(name).apply(); return JSONObject().put("ok", true).toString()
    }
    @JavascriptInterface fun keysList(): String {
        val arr = JSONArray(); keys.all.keys.forEach { arr.put(it) }
        return JSONObject().put("keys", arr).toString()
    }

    private fun githubToken(): String? =
        keys.getString("github", null)
            ?: keys.getString("github_pat", null)
            ?: keys.getString("GITHUB_TOKEN", null)
            ?: settings.getString("github_pat", null)

    @JavascriptInterface
    fun githubRequest(method: String, path: String, body: String?, callbackId: String) {
        val token = githubToken()
        if (token.isNullOrBlank()) {
            deliver(callbackId, JSONObject().put("ok", false)
                .put("error", "missing PAT — set github or github_pat in Settings").toString())
            return
        }
        val url = if (path.startsWith("http")) path else "https://api.github.com${if (path.startsWith("/")) path else "/$path"}"
        val headers = JSONObject()
            .put("Authorization", "Bearer $token")
            .put("Accept", "application/vnd.github+json")
            .put("X-GitHub-Api-Version", "2022-11-28")
            .toString()
        httpRequest(url, method.ifBlank { "GET" }, headers, body, callbackId)
    }

    /**
     * Full HTTP. headersJson is a JSON object string, e.g. {"Authorization":"Bearer …"}.
     * Authorization and custom headers are preserved (unlike old fetch_url).
     */
    @JavascriptInterface
    fun httpRequest(url: String, method: String, headersJson: String?, body: String?, callbackId: String) {
        if (inFlight.size > 10) {
            deliver(callbackId, JSONObject().put("ok", false).put("error", "too many in-flight requests").toString())
            return
        }
        inFlight[callbackId] = System.currentTimeMillis()
        io.execute {
            val payload = try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = method.ifBlank { "GET" }.uppercase()
                    connectTimeout = 12_000
                    readTimeout = 25_000
                    instanceFollowRedirects = true
                    doInput = true
                    setRequestProperty("User-Agent", "D-Harness/1.2")
                    if (!headersJson.isNullOrBlank()) {
                        val h = JSONObject(headersJson)
                        val keys = h.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            setRequestProperty(k, h.getString(k))
                        }
                    }
                    if (!body.isNullOrEmpty() && requestMethod != "GET" && requestMethod != "HEAD") {
                        doOutput = true
                        if (getRequestProperty("Content-Type") == null) {
                            setRequestProperty("Content-Type", "application/json")
                        }
                        outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                    }
                }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() }?.take(150_000) ?: ""
                var parsed: Any? = null
                try { parsed = JSONObject(text) } catch (_: Exception) {
                    try { parsed = JSONArray(text) } catch (_: Exception) {}
                }
                val out = JSONObject()
                    .put("status", code)
                    .put("ok", code in 200..299)
                    .put("text", text)
                if (parsed is JSONObject) out.put("json", parsed)
                else if (parsed is JSONArray) out.put("json", parsed)
                out.toString()
            } catch (e: Exception) {
                JSONObject().put("ok", false).put("error", e.message ?: "request failed").toString()
            } finally {
                inFlight.remove(callbackId)
            }
            deliver(callbackId, payload)
        }
    }

    /** Back-compat: fetchUrl now accepts headersJson too. */
    @JavascriptInterface
    fun fetchUrl(url: String, method: String, body: String?, callbackId: String) {
        httpRequest(url, method, null, body, callbackId)
    }

    @JavascriptInterface
    fun fetchUrlWithHeaders(url: String, method: String, headersJson: String?, body: String?, callbackId: String) {
        httpRequest(url, method, headersJson, body, callbackId)
    }

    @JavascriptInterface
    fun fsWrite(path: String, content: String): String {
        return try {
            val f = safeFile(path)
            f.parentFile?.mkdirs()
            f.writeText(content)
            JSONObject().put("ok", true).put("bytes", content.length).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun fsRead(path: String): String {
        return try {
            val f = safeFile(path)
            if (!f.exists()) return JSONObject().put("ok", false).put("error", "not found").toString()
            JSONObject().put("ok", true).put("content", f.readText().take(200_000)).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun fsList(prefix: String): String {
        val arr = JSONArray()
        fsRoot.walkTopDown().filter { it.isFile }.forEach { f ->
            val rel = f.relativeTo(fsRoot).path
            if (rel.startsWith(prefix.trimStart('/'))) arr.put(rel)
        }
        return JSONObject().put("paths", arr).toString()
    }

    @JavascriptInterface
    fun fsDelete(path: String): String {
        return try {
            JSONObject().put("ok", safeFile(path).delete()).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun shareText(text: String): String {
        webView.post {
            val i = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
            context.startActivity(Intent.createChooser(i, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        return JSONObject().put("ok", true).toString()
    }

    @JavascriptInterface
    fun saveFile(filename: String, content: String, mime: String): String {
        return try {
            val safe = filename.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(80)
            val f = File(context.cacheDir, safe)
            f.writeText(content)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
            webView.post {
                val i = Intent(Intent.ACTION_SEND).apply {
                    type = mime.ifBlank { "text/plain" }
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(i, "Save").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            JSONObject().put("ok", true).put("filename", safe).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    private fun safeFile(path: String): File {
        val cleaned = path.trim().removePrefix("/").replace("..", "")
        val f = File(fsRoot, cleaned)
        require(f.canonicalPath.startsWith(fsRoot.canonicalPath)) { "bad path" }
        return f
    }
}
