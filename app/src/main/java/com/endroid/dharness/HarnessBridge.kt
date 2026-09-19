package com.endroid.dharness

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Native tools for the injected shim. All heavy work is off the main thread.
 * Results for async ops are delivered via window.__dHarnessFetchCb / __dHarnessCb.
 */
class HarnessBridge(
    private val context: Context,
    private val webView: WebView
) {
    private val io = Executors.newFixedThreadPool(4)
    private val mem = context.getSharedPreferences("dharness_mem", Context.MODE_PRIVATE)
    private val keys = context.getSharedPreferences("dharness_keys", Context.MODE_PRIVATE)
    private val fsRoot = File(context.filesDir, "harness_fs").also { it.mkdirs() }
    private val cbSeq = AtomicInteger(0)
    private val inFlight = ConcurrentHashMap<String, Long>()

    private fun deliver(callbackId: String, json: String) {
        val safe = JSONObject.quote(json)
        val idQ = JSONObject.quote(callbackId)
        webView.post {
            webView.evaluateJavascript(
                "window.__dHarnessCb&&window.__dHarnessCb($idQ,$safe);window.__dHarnessFetchCb&&window.__dHarnessFetchCb($idQ,$safe);",
                null
            )
        }
    }

    @JavascriptInterface
    fun listTools(): String {
        val tools = JSONArray()
            .put("list_tools")
            .put("memory.get/set/delete/list/clear")
            .put("keys.get/set/delete/list")
            .put("fs.read/write/list/delete")
            .put("fetch_url (native, no CORS)")
            .put("clipboard.read/write")
            .put("file.save / share")
            .put("device.info / battery / network")
            .put("notify / toast / vibrate")
            .put("appInfo")
        return JSONObject().put("tools", tools).put("native", true).put("version", "1.1").toString()
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

    @JavascriptInterface
    fun deviceInfo(): String = appInfo()

    @JavascriptInterface
    fun battery(): String {
        return try {
            val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val battery = context.registerReceiver(null, ifilter)
            val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            val pct = if (scale > 0) (level * 100f / scale) else -1f
            JSONObject().put("percent", pct).put("charging", charging).toString()
        } catch (e: Exception) {
            JSONObject().put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun network(): String {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork
            val caps = net?.let { cm.getNetworkCapabilities(it) }
            JSONObject()
                .put("online", caps != null)
                .put("wifi", caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true)
                .put("cellular", caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) == true)
                .toString()
        } catch (e: Exception) {
            JSONObject().put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun toast(message: String) {
        webView.post { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
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
        mem.edit().putString(key, value).apply()
        return JSONObject().put("ok", true).toString()
    }

    @JavascriptInterface fun memoryDelete(key: String): String {
        mem.edit().remove(key).apply()
        return JSONObject().put("ok", true).toString()
    }

    @JavascriptInterface fun memoryList(): String {
        val arr = JSONArray()
        mem.all.keys.forEach { arr.put(it) }
        return JSONObject().put("keys", arr).toString()
    }

    @JavascriptInterface fun memoryClear(): String {
        mem.edit().clear().apply()
        return JSONObject().put("ok", true).toString()
    }

    @JavascriptInterface fun keysGet(name: String): String =
        JSONObject().put("value", keys.getString(name, null)).toString()

    @JavascriptInterface fun keysSet(name: String, value: String): String {
        keys.edit().putString(name, value).apply()
        return JSONObject().put("ok", true).toString()
    }

    @JavascriptInterface fun keysDelete(name: String): String {
        keys.edit().remove(name).apply()
        return JSONObject().put("ok", true).toString()
    }

    @JavascriptInterface fun keysList(): String {
        val arr = JSONArray()
        keys.all.keys.forEach { arr.put(it) }
        return JSONObject().put("keys", arr).toString()
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
            val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", f)
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

    /** Fast async fetch — always returns immediately; result via callback. */
    @JavascriptInterface
    fun fetchUrl(url: String, method: String, body: String?, callbackId: String) {
        if (inFlight.size > 8) {
            deliver(callbackId, JSONObject().put("ok", false).put("error", "too many requests").toString())
            return
        }
        inFlight[callbackId] = System.currentTimeMillis()
        io.execute {
            val payload = try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = method.ifBlank { "GET" }
                    connectTimeout = 12_000
                    readTimeout = 20_000
                    doInput = true
                    setRequestProperty("User-Agent", "D-Harness/1.1")
                    if (!body.isNullOrEmpty() && requestMethod != "GET" && requestMethod != "HEAD") {
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json")
                        outputStream.use { it.write(body.toByteArray()) }
                    }
                }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() }?.take(100_000) ?: ""
                JSONObject().put("status", code).put("ok", code in 200..299).put("text", text).toString()
            } catch (e: Exception) {
                JSONObject().put("ok", false).put("error", e.message ?: "fetch failed").toString()
            } finally {
                inFlight.remove(callbackId)
            }
            deliver(callbackId, payload)
        }
    }

    private fun safeFile(path: String): File {
        val cleaned = path.trim().removePrefix("/").replace("..", "")
        val f = File(fsRoot, cleaned)
        require(f.canonicalPath.startsWith(fsRoot.canonicalPath)) { "bad path" }
        return f
    }
}
