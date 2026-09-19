package com.endroid.dharness

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Native extras exposed to the injected shim as window.DHarness.*
 */
class HarnessBridge(
    private val context: Context,
    private val webView: WebView
) {
    private val io = Executors.newCachedThreadPool()
    private val prefs = context.getSharedPreferences("dharness_mem", Context.MODE_PRIVATE)
    private val fsRoot = File(context.filesDir, "harness_fs").also { it.mkdirs() }

    @JavascriptInterface
    fun appInfo(): String {
        val p = context.packageManager.getPackageInfo(context.packageName, 0)
        return JSONObject()
            .put("name", "D-Harness")
            .put("version", p.versionName)
            .put("package", context.packageName)
            .put("sdk", Build.VERSION.SDK_INT)
            .toString()
    }

    @JavascriptInterface
    fun toast(message: String) {
        webView.post {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    @JavascriptInterface
    fun vibrate(ms: Int) {
        val v = if (Build.VERSION.SDK_INT >= 31) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        if (Build.VERSION.SDK_INT >= 26) {
            v.vibrate(VibrationEffect.createOneShot(ms.toLong().coerceIn(10, 1000), VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(ms.toLong().coerceIn(10, 1000))
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

    @JavascriptInterface
    fun memoryGet(key: String): String {
        return JSONObject().put("value", prefs.getString(key, null)).toString()
    }

    @JavascriptInterface
    fun memorySet(key: String, value: String): String {
        prefs.edit().putString(key, value).apply()
        return JSONObject().put("ok", true).toString()
    }

    @JavascriptInterface
    fun memoryDelete(key: String): String {
        prefs.edit().remove(key).apply()
        return JSONObject().put("ok", true).toString()
    }

    @JavascriptInterface
    fun memoryList(): String {
        val arr = JSONArray()
        prefs.all.keys.forEach { arr.put(it) }
        return JSONObject().put("keys", arr).toString()
    }

    @JavascriptInterface
    fun memoryClear(): String {
        prefs.edit().clear().apply()
        return JSONObject().put("ok", true).toString()
    }

    @JavascriptInterface
    fun fsWrite(path: String, content: String): String {
        return try {
            val f = safeFile(path)
            f.parentFile?.mkdirs()
            f.writeText(content)
            JSONObject().put("ok", true).put("path", path).put("bytes", content.length).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun fsRead(path: String): String {
        return try {
            val f = safeFile(path)
            if (!f.exists()) return JSONObject().put("ok", false).put("error", "not found").toString()
            JSONObject().put("ok", true).put("content", f.readText()).toString()
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
            val ok = safeFile(path).delete()
            JSONObject().put("ok", ok).toString()
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
            val safe = filename.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val f = File(context.cacheDir, safe)
            f.writeText(content)
            val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", f)
            webView.post {
                val i = Intent(Intent.ACTION_SEND).apply {
                    type = mime.ifBlank { "text/plain" }
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(i, "Save / share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            JSONObject().put("ok", true).put("filename", safe).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    /**
     * Network fetch from native (no CORS). Returns JSON: status, ok, text (capped).
     * Heavy work off main thread; result delivered via callback id.
     */
    @JavascriptInterface
    fun fetchUrl(url: String, method: String, body: String?, callbackId: String) {
        io.execute {
            val payload = try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = method.ifBlank { "GET" }
                    connectTimeout = 15000
                    readTimeout = 20000
                    doInput = true
                    if (!body.isNullOrEmpty() && requestMethod != "GET") {
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json")
                        outputStream.use { it.write(body.toByteArray()) }
                    }
                }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader()?.readText()?.take(100_000) ?: ""
                JSONObject()
                    .put("status", code)
                    .put("ok", code in 200..299)
                    .put("text", text)
                    .toString()
            } catch (e: Exception) {
                JSONObject().put("ok", false).put("error", e.message).toString()
            }
            val js = "window.__dHarnessFetchCb && window.__dHarnessFetchCb(" +
                JSONObject.quote(callbackId) + "," + JSONObject.quote(payload) + ");"
            webView.post { webView.evaluateJavascript(js, null) }
        }
    }

    private fun safeFile(path: String): File {
        val cleaned = path.trim().removePrefix("/").replace("..", "")
        val f = File(fsRoot, cleaned)
        require(f.canonicalPath.startsWith(fsRoot.canonicalPath)) { "bad path" }
        return f
    }
}
