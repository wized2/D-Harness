package com.endroid.dharness

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class HarnessBridge(
    private val context: Context,
    private val webView: WebView
) {
    private val io = Executors.newFixedThreadPool(6)
    private val mem = context.getSharedPreferences("dharness_mem", Context.MODE_PRIVATE)
    private val keys = context.getSharedPreferences("dharness_keys", Context.MODE_PRIVATE)
    private val settings = context.getSharedPreferences("dharness_settings", Context.MODE_PRIVATE)
    private val fsRoot = File(context.filesDir, "harness_fs").also { it.mkdirs() }
    /** Agent working directory (Termux-style). Prefer external app files so file managers can see it. */
    private val workspaceRoot: File = run {
        val ext = context.getExternalFilesDir(null)
        val base = if (ext != null) File(ext, "workspace") else File(context.filesDir, "workspace")
        base.also { it.mkdirs() }
    }
    private val inFlight = ConcurrentHashMap<String, Long>()
    private val childProcs = ConcurrentHashMap<Int, java.lang.Process>()

    private val execAllow = setOf(
        "ls", "cat", "grep", "find", "echo", "pwd", "wc", "head", "tail", "date",
        "uname", "id", "which", "true", "false", "sha256sum", "md5sum", "base64",
        "toybox", "busybox", "dirname", "basename", "cut", "sort", "uniq", "tr",
        "cmp", "stat", "df", "du", "sleep", "printf", "test", "[", "rm", "mkdir",
        "touch", "cp", "mv", "ln", "chmod"
    )

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

    private fun hex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append(String.format("%02x", b))
        return sb.toString()
    }

    private fun sha256(bytes: ByteArray): String =
        hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun safeFile(path: String): File {
        val cleaned = path.trim().removePrefix("/").replace("..", "")
        val f = File(fsRoot, cleaned)
        require(f.canonicalPath.startsWith(fsRoot.canonicalPath)) { "bad path" }
        return f
    }

    /** Resolve path under workspace (absolute within workspace, or relative). */
    private fun safeWorkspace(path: String?): File {
        val raw = (path ?: ".").trim()
        val cleaned = raw.removePrefix("/").replace("..", "")
        val f = if (cleaned.isEmpty() || cleaned == ".") workspaceRoot else File(workspaceRoot, cleaned)
        val canon = f.canonicalFile
        require(canon.path.startsWith(workspaceRoot.canonicalPath)) { "path escapes workspace" }
        return canon
    }

    // ─── Catalog ───────────────────────────────────────────────

    @JavascriptInterface
    fun listTools(): String {
        val tools = JSONArray()
        fun tool(name: String, desc: String, params: JSONObject) {
            tools.put(JSONObject().put("name", name).put("description", desc).put("params", params))
        }
        tool("list_tools", "List tools + schemas", JSONObject())
        tool("describe", "Describe tool or group", JSONObject().put("name", "string"))
        tool("http_request", "HTTP with headers (native)", JSONObject().put("url", "string").put("method", "string?").put("headers", "object?").put("body", "string?"))
        tool("fetch_url", "Alias of http_request", JSONObject().put("url", "string").put("headers", "object?"))
        tool("github.request", "GitHub REST via PAT", JSONObject().put("method", "string?").put("path", "string").put("body", "object?"))
        tool("github.me", "GET /user", JSONObject())
        tool("github.repos", "List repos (compact)", JSONObject().put("per_page", "number?"))
        tool("github.issues", "List issues", JSONObject().put("owner", "string").put("repo", "string"))
        tool("github.issue_comment", "Comment on issue/PR", JSONObject().put("owner", "string").put("repo", "string").put("number", "number").put("body", "string"))
        tool("github.pr", "Get PR", JSONObject().put("owner", "string").put("repo", "string").put("number", "number"))
        tool("memory.get", "Scratch KV get", JSONObject().put("key", "string"))
        tool("memory.set", "Scratch KV set", JSONObject().put("key", "string").put("value", "string"))
        tool("memory.delete", "Scratch KV delete", JSONObject().put("key", "string"))
        tool("memory.list", "List scratch keys", JSONObject())
        tool("memory.clear", "Clear scratch", JSONObject())
        tool("keys.get", "Secret get (never print)", JSONObject().put("name", "string"))
        tool("keys.set", "Secret set", JSONObject().put("name", "string").put("value", "string"))
        tool("keys.delete", "Secret delete", JSONObject().put("name", "string"))
        tool("keys.list", "List secret names", JSONObject())
        tool("fs.read", "Read UTF-8 text file", JSONObject().put("path", "string"))
        tool("fs.write", "Write UTF-8 text file", JSONObject().put("path", "string").put("content", "string"))
        tool("fs.list", "List files", JSONObject().put("prefix", "string?"))
        tool("fs.delete", "Delete file", JSONObject().put("path", "string"))
        tool("file.commit", "Byte-exact write from base64 + SHA-256 verify", JSONObject().put("path", "string").put("contentB64", "string").put("sha256", "string?"))
        tool("file.read_b64", "Read file as base64 + sha256", JSONObject().put("path", "string"))
        tool("workspace.pwd", "Agent workspace absolute path", JSONObject())
        tool("workspace.ls", "List workspace directory", JSONObject().put("path", "string?"))
        tool("workspace.read", "Read UTF-8 text file from workspace", JSONObject().put("path", "string").put("maxBytes", "int?"))
        tool("workspace.write", "Write UTF-8 text file in workspace", JSONObject().put("path", "string").put("content", "string"))
        tool("workspace.write_b64", "Write binary file from base64", JSONObject().put("path", "string").put("contentB64", "string"))
        tool("workspace.read_b64", "Read workspace file as base64 + sha256", JSONObject().put("path", "string"))
        tool("workspace.mkdir", "Create directory under workspace", JSONObject().put("path", "string"))
        tool("workspace.rm", "Delete file or empty dir in workspace", JSONObject().put("path", "string"))
        tool("workspace.stat", "Stat path in workspace", JSONObject().put("path", "string"))
        tool("workspace.tree", "Shallow tree listing", JSONObject().put("path", "string?").put("depth", "int?"))
        tool("github.pull", "Download GitHub file into workspace", JSONObject().put("owner", "string").put("repo", "string").put("path", "string").put("ref", "string?").put("dest", "string?"))
        tool("github.push_file", "Upload workspace file to GitHub contents API", JSONObject().put("owner", "string").put("repo", "string").put("path", "string").put("branch", "string").put("message", "string").put("localPath", "string"))
        tool("file.verify_roundtrip", "Write then read-back SHA-256 of UTF-8 test bytes", JSONObject())
        tool("exec", "Allowlisted ProcessBuilder in app sandbox", JSONObject().put("argv", "string[]").put("timeout_ms", "number?").put("cwd", "string?"))
        tool("sqlite.query", "Read-only SQLite query", JSONObject().put("path", "string").put("sql", "string").put("args", "string[]?"))
        tool("crypto.hash", "SHA-256/SHA-1/MD5", JSONObject().put("algo", "string").put("data", "string").put("encoding", "utf8|b64?"))
        tool("crypto.hmac", "HMAC-SHA256", JSONObject().put("key", "string").put("data", "string"))
        tool("archive.zip_list", "List zip entries", JSONObject().put("path", "string"))
        tool("archive.zip_extract", "Extract one entry as b64", JSONObject().put("path", "string").put("entry", "string"))
        tool("archive.zip_create", "Create zip from path→b64 map", JSONObject().put("path", "string").put("files", "object"))
        tool("json.query", "Dot/index path on JSON string", JSONObject().put("json", "string").put("path", "string"))
        tool("net.ping", "InetAddress reachability", JSONObject().put("host", "string").put("timeout_ms", "number?"))
        tool("net.port", "TCP connect check", JSONObject().put("host", "string").put("port", "number").put("timeout_ms", "number?"))
        tool("process.list", "Tracked child processes", JSONObject())
        tool("process.kill", "Kill tracked process", JSONObject().put("pid", "number"))
        tool("env.get", "SDK, perms, paths, allowlist", JSONObject())
        tool("clipboard.read", "Read clipboard", JSONObject())
        tool("clipboard.write", "Write clipboard", JSONObject().put("text", "string"))
        tool("clipboard.copy", "Alias of clipboard.write", JSONObject().put("text", "string"))
        tool("file.save", "Share sheet", JSONObject().put("filename", "string").put("content", "string"))
        tool("share", "Share text", JSONObject().put("text", "string"))
        tool("device.info", "Device info", JSONObject())
        tool("device.battery", "Battery", JSONObject())
        tool("device.network", "Network", JSONObject())
        tool("toast", "Toast", JSONObject().put("message", "string"))
        tool("vibrate", "Vibrate", JSONObject().put("ms", "number?"))
        tool("notify", "Notification", JSONObject().put("title", "string").put("body", "string?"))
        tool("appInfo", "Same as device.info", JSONObject())
        tool("geo.get", "Location if permitted", JSONObject())

        tool("calc.eval", "Safe arithmetic expression ( + - * / % ^ ( ) )", JSONObject().put("expr", "string"))
        tool("calc.convert", "Unit convert: length/mass/temp/data", JSONObject().put("value", "number").put("from", "string").put("to", "string"))
        tool("calc.haversine", "Distance km between lat/lon pairs", JSONObject().put("lat1", "number").put("lon1", "number").put("lat2", "number").put("lon2", "number"))
        tool("text.base64", "encode|decode base64", JSONObject().put("op", "encode|decode").put("data", "string"))
        tool("text.url", "encode|decode URL component", JSONObject().put("op", "encode|decode").put("data", "string"))
        tool("text.regex", "Regex find/match/replace", JSONObject().put("op", "find|match|replace").put("pattern", "string").put("text", "string").put("replacement", "string?"))
        tool("text.hash_preview", "Length/lines/words of text", JSONObject().put("text", "string"))
        tool("time.now", "Epoch ms + ISO UTC", JSONObject())
        tool("time.format", "Format epoch ms", JSONObject().put("ms", "number").put("pattern", "string?"))
        tool("uuid.v4", "Random UUID", JSONObject())
        tool("fs.stat", "File size/mtime/exists", JSONObject().put("path", "string"))
        tool("fs.exists", "Boolean exists", JSONObject().put("path", "string"))
        tool("fs.append", "Append UTF-8 text", JSONObject().put("path", "string").put("content", "string"))
        tool("intent.open_url", "Open URL in browser", JSONObject().put("url", "string"))
        tool("device.display", "Screen size/density", JSONObject())
        tool("random.bytes", "Secure random hex", JSONObject().put("n", "number"))
        tool("calc.clamp", "Clamp number to [min,max]", JSONObject().put("value", "number").put("min", "number").put("max", "number"))
        tool("calc.round", "Round to decimals", JSONObject().put("value", "number").put("digits", "number?"))
        tool("text.case", "lower|upper|title", JSONObject().put("op", "string").put("text", "string"))
        tool("text.trim", "Trim whitespace", JSONObject().put("text", "string"))
        tool("text.split", "Split by delimiter", JSONObject().put("text", "string").put("sep", "string").put("limit", "number?"))
        tool("text.join", "Join array with sep", JSONObject().put("parts", "string[]").put("sep", "string"))
        tool("json.pretty", "Pretty-print JSON", JSONObject().put("json", "string").put("indent", "number?"))
        tool("json.parse", "Parse JSON string", JSONObject().put("json", "string"))
        tool("color.hex_rgb", "hex↔rgb", JSONObject().put("op", "to_rgb|to_hex").put("value", "string"))
        tool("fs.mkdir", "Create directory", JSONObject().put("path", "string"))
        tool("fs.touch", "Create empty file", JSONObject().put("path", "string"))
        tool("fs.copy", "Copy file in sandbox", JSONObject().put("from", "string").put("to", "string"))
        tool("fs.move", "Move/rename in sandbox", JSONObject().put("from", "string").put("to", "string"))
        tool("diff.lines", "Simple line diff a vs b", JSONObject().put("a", "string").put("b", "string"))


        return JSONObject()
            .put("tools", tools)
            .put("native", true)
            .put("version", "1.4.0")
            .put("notes", JSONObject()
                .put("memory", "agent scratchpad")
                .put("keys", "secrets/PAT — never echo values")
                .put("file.commit", "byte-exact writes; use for source files")
                .put("exec", "allowlisted binaries only; cwd jailed to harness_fs")
                .put("github", "requires PAT key github")
            ).toString()
    }

    @JavascriptInterface
    fun describeTool(name: String): String {
        val raw = name.trim()
        val arr = JSONObject(listTools()).getJSONArray("tools")
        val matches = JSONArray()
        for (i in 0 until arr.length()) {
            val t = arr.getJSONObject(i)
            val n = t.getString("name")
            if (n == raw || n.startsWith("$raw.") ||
                (raw == "memory" && n.startsWith("memory.")) ||
                (raw == "keys" && n.startsWith("keys.")) ||
                (raw == "fs" && n.startsWith("fs.")) ||
                (raw == "file" && n.startsWith("file.")) ||
                (raw == "github" && n.startsWith("github.")) ||
                (raw == "device" && n.startsWith("device.")) ||
                (raw == "clipboard" && n.startsWith("clipboard.")) ||
                (raw == "crypto" && n.startsWith("crypto.")) ||
                (raw == "archive" && n.startsWith("archive.")) ||
                (raw == "net" && n.startsWith("net.")) ||
                (raw == "process" && n.startsWith("process.")) ||
                (raw == "sqlite" && n.startsWith("sqlite."))
            ) matches.put(t)
        }
        if (matches.length() == 1) return matches.getJSONObject(0).toString()
        if (matches.length() > 1) {
            return JSONObject().put("name", raw).put("variants", matches).toString()
        }
        return JSONObject().put("error", "unknown tool: $raw").toString()
    }

    // ─── file.commit / read_b64 ────────────────────────────────

    @JavascriptInterface
    fun fileCommit(path: String, contentB64: String, expectedSha: String?): String {
        return try {
            val bytes = Base64.decode(contentB64, Base64.DEFAULT)
            val digest = sha256(bytes)
            if (!expectedSha.isNullOrBlank() && expectedSha.lowercase() != digest) {
                return JSONObject().put("ok", false).put("error", "sha256 mismatch")
                    .put("expected", expectedSha).put("actual", digest).toString()
            }
            val f = safeFile(path)
            f.parentFile?.mkdirs()
            FileOutputStream(f).use { it.write(bytes) }
            val verify = FileInputStream(f).use { it.readBytes() }
            val vSha = sha256(verify)
            if (vSha != digest) {
                return JSONObject().put("ok", false).put("error", "write verify failed").toString()
            }
            JSONObject().put("ok", true).put("bytes", bytes.size).put("sha256", digest)
                .put("path", path).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun fileReadB64(path: String): String {
        return try {
            val f = safeFile(path)
            if (!f.exists()) return JSONObject().put("ok", false).put("error", "not found").toString()
            val bytes = FileInputStream(f).use { it.readBytes() }
            JSONObject().put("ok", true)
                .put("contentB64", Base64.encodeToString(bytes, Base64.NO_WRAP))
                .put("bytes", bytes.size)
                .put("sha256", sha256(bytes)).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    // ─── exec ──────────────────────────────────────────────────

    @JavascriptInterface
    fun exec(argvJson: String, timeoutMs: Int, cwdRel: String?): String {
        return try {
            val arr = JSONArray(argvJson)
            if (arr.length() == 0) return JSONObject().put("ok", false).put("error", "empty argv").toString()
            val argv = MutableList(arr.length()) { arr.getString(it) }
            val bin = argv[0].substringAfterLast('/')
            if (bin !in execAllow) {
                return JSONObject().put("ok", false).put("error", "binary not allowlisted: $bin")
                    .put("allow", JSONArray(execAllow.toList())).toString()
            }
            val cwd = if (cwdRel.isNullOrBlank()) fsRoot else safeFile(cwdRel).also {
                if (!it.isDirectory) it.mkdirs()
            }
            val t0 = System.currentTimeMillis()
            val pb = ProcessBuilder(argv).directory(cwd).redirectErrorStream(false)
            val proc = pb.start()
            val pid = try {
                // java.lang.Process#pid() is API 26+ / JDK 9+
                val m = proc.javaClass.getMethod("pid")
                (m.invoke(proc) as Long).toInt()
            } catch (_: Exception) {
                (100000 + (Math.random() * 900000).toInt())
            }
            childProcs[pid] = proc
            val finished = proc.waitFor(timeoutMs.coerceIn(500, 60_000).toLong(), TimeUnit.MILLISECONDS)
            if (!finished) {
                try { proc.javaClass.getMethod("destroyForcibly").invoke(proc) } catch (_: Exception) { proc.destroy() }
                childProcs.remove(pid)
                return JSONObject().put("ok", false).put("error", "timeout").put("pid", pid)
                    .put("durationMs", System.currentTimeMillis() - t0).toString()
            }
            val stdout = proc.inputStream.bufferedReader().use(BufferedReader::readText).take(80_000)
            val stderr = proc.errorStream.bufferedReader().use(BufferedReader::readText).take(40_000)
            val code = proc.exitValue()
            childProcs.remove(pid)
            JSONObject().put("ok", true).put("code", code).put("stdout", stdout).put("stderr", stderr)
                .put("durationMs", System.currentTimeMillis() - t0).put("pid", pid).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    // ─── sqlite ────────────────────────────────────────────────

    @JavascriptInterface
    fun sqliteQuery(path: String, sql: String, argsJson: String?): String {
        return try {
            val f = safeFile(path)
            if (!f.exists()) return JSONObject().put("ok", false).put("error", "db not found").toString()
            val db = android.database.sqlite.SQLiteDatabase.openDatabase(
                f.absolutePath, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY
            )
            try {
                val args = if (argsJson.isNullOrBlank()) null
                else Array(JSONArray(argsJson).length()) { JSONArray(argsJson).getString(it) }
                val cur = db.rawQuery(sql, args)
                val cols = JSONArray()
                for (i in 0 until cur.columnCount) cols.put(cur.getColumnName(i))
                val rows = JSONArray()
                while (cur.moveToNext()) {
                    val row = JSONArray()
                    for (i in 0 until cur.columnCount) {
                        when (cur.getType(i)) {
                            android.database.Cursor.FIELD_TYPE_NULL -> row.put(JSONObject.NULL)
                            android.database.Cursor.FIELD_TYPE_INTEGER -> row.put(cur.getLong(i))
                            android.database.Cursor.FIELD_TYPE_FLOAT -> row.put(cur.getDouble(i))
                            else -> row.put(cur.getString(i))
                        }
                    }
                    rows.put(row)
                    if (rows.length() >= 500) break
                }
                cur.close()
                JSONObject().put("ok", true).put("columns", cols).put("rows", rows).toString()
            } finally {
                db.close()
            }
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    // ─── crypto ────────────────────────────────────────────────

    @JavascriptInterface
    fun cryptoHash(algo: String, data: String, encoding: String?): String {
        return try {
            val bytes = if (encoding == "b64") Base64.decode(data, Base64.DEFAULT)
            else data.toByteArray(Charsets.UTF_8)
            val name = when (algo.lowercase()) {
                "sha256", "sha-256" -> "SHA-256"
                "sha1", "sha-1" -> "SHA-1"
                "md5" -> "MD5"
                else -> return JSONObject().put("ok", false).put("error", "algo").toString()
            }
            val dig = MessageDigest.getInstance(name).digest(bytes)
            JSONObject().put("ok", true).put("hex", hex(dig)).put("algo", name).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun cryptoHmac(key: String, data: String): String {
        return try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            JSONObject().put("ok", true).put("hex", hex(mac.doFinal(data.toByteArray(Charsets.UTF_8)))).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    // ─── archive ───────────────────────────────────────────────

    @JavascriptInterface
    fun zipList(path: String): String {
        return try {
            val f = safeFile(path)
            val names = JSONArray()
            ZipInputStream(FileInputStream(f)).use { zis ->
                var e = zis.nextEntry
                while (e != null) {
                    names.put(JSONObject().put("name", e.name).put("size", e.size).put("dir", e.isDirectory))
                    zis.closeEntry()
                    e = zis.nextEntry
                }
            }
            JSONObject().put("ok", true).put("entries", names).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun zipExtract(path: String, entry: String): String {
        return try {
            val f = safeFile(path)
            ZipInputStream(FileInputStream(f)).use { zis ->
                var e = zis.nextEntry
                while (e != null) {
                    if (e.name == entry && !e.isDirectory) {
                        val bytes = zis.readBytes().take(2_000_000).toByteArray()
                        return JSONObject().put("ok", true)
                            .put("contentB64", Base64.encodeToString(bytes, Base64.NO_WRAP))
                            .put("bytes", bytes.size).put("sha256", sha256(bytes)).toString()
                    }
                    zis.closeEntry()
                    e = zis.nextEntry
                }
            }
            JSONObject().put("ok", false).put("error", "entry not found").toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun zipCreate(path: String, filesJson: String): String {
        return try {
            val f = safeFile(path)
            f.parentFile?.mkdirs()
            val obj = JSONObject(filesJson)
            ZipOutputStream(FileOutputStream(f)).use { zos ->
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val name = keys.next()
                    val b64 = obj.getString(name)
                    val data = Base64.decode(b64, Base64.DEFAULT)
                    zos.putNextEntry(ZipEntry(name))
                    zos.write(data)
                    zos.closeEntry()
                }
            }
            JSONObject().put("ok", true).put("path", path).put("bytes", f.length()).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    // ─── json.query ────────────────────────────────────────────

    @JavascriptInterface
    fun jsonQuery(jsonStr: String, path: String): String {
        return try {
            var cur: Any? = if (jsonStr.trimStart().startsWith("[")) JSONArray(jsonStr) else JSONObject(jsonStr)
            val parts = path.split('.').filter { it.isNotEmpty() }
            for (part in parts) {
                val m = Regex("""^(\w+)(?:\[(\d+)\])?$""").find(part)
                val key = m?.groupValues?.get(1) ?: part
                val idx = m?.groupValues?.getOrNull(2)?.toIntOrNull()
                cur = when (val c = cur) {
                    is JSONObject -> c.opt(key)
                    is JSONArray -> c.opt(key.toIntOrNull() ?: return JSONObject().put("ok", false).put("error", "idx").toString())
                    else -> return JSONObject().put("ok", false).put("error", "path").toString()
                }
                if (idx != null) {
                    cur = (cur as? JSONArray)?.opt(idx)
                        ?: return JSONObject().put("ok", false).put("error", "not array").toString()
                }
            }
            when (cur) {
                null, JSONObject.NULL -> JSONObject().put("ok", true).put("value", JSONObject.NULL).toString()
                is JSONObject, is JSONArray -> JSONObject().put("ok", true).put("value", cur).toString()
                else -> JSONObject().put("ok", true).put("value", cur).toString()
            }
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    // ─── net ───────────────────────────────────────────────────

    @JavascriptInterface
    fun netPing(host: String, timeoutMs: Int): String {
        return try {
            val t0 = System.currentTimeMillis()
            val ok = InetAddress.getByName(host).isReachable(timeoutMs.coerceIn(200, 10_000))
            JSONObject().put("ok", true).put("reachable", ok)
                .put("latencyMs", System.currentTimeMillis() - t0).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun netPort(host: String, port: Int, timeoutMs: Int): String {
        return try {
            val t0 = System.currentTimeMillis()
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), timeoutMs.coerceIn(200, 10_000))
            }
            JSONObject().put("ok", true).put("open", true)
                .put("latencyMs", System.currentTimeMillis() - t0).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", true).put("open", false).put("error", e.message).toString()
        }
    }

    // ─── process ───────────────────────────────────────────────

    @JavascriptInterface
    fun processList(): String {
        val arr = JSONArray()
        childProcs.forEach { (pid, p) ->
            val alive = try { p.javaClass.getMethod("isAlive").invoke(p) as Boolean } catch (_: Exception) { true }
            arr.put(JSONObject().put("pid", pid).put("alive", alive))
        }
        return JSONObject().put("ok", true).put("processes", arr).toString()
    }

    @JavascriptInterface
    fun processKill(pid: Int): String {
        val p = childProcs.remove(pid) ?: return JSONObject().put("ok", false).put("error", "unknown pid").toString()
        try { p.javaClass.getMethod("destroyForcibly").invoke(p) } catch (_: Exception) { p.destroy() }
        return JSONObject().put("ok", true).toString()
    }

    // ─── env ───────────────────────────────────────────────────

    @JavascriptInterface
    fun envGet(): String {
        val perms = JSONArray()
        listOf(
            android.Manifest.permission.INTERNET,
            android.Manifest.permission.ACCESS_NETWORK_STATE,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
            android.Manifest.permission.POST_NOTIFICATIONS,
            android.Manifest.permission.VIBRATE
        ).forEach { p ->
            val g = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
            perms.put(JSONObject().put("perm", p.substringAfterLast('.')).put("granted", g))
        }
        val bins = JSONArray()
        execAllow.forEach { b ->
            val found = listOf("/system/bin/$b", "/system/xbin/$b", "/bin/$b").any { File(it).exists() }
            if (found) bins.put(b)
        }
        return JSONObject()
            .put("ok", true)
            .put("sdk", Build.VERSION.SDK_INT)
            .put("abi", Build.SUPPORTED_ABIS.toList().let { JSONArray(it) })
            .put("model", Build.MODEL)
            .put("filesDir", context.filesDir.absolutePath)
            .put("fsRoot", fsRoot.absolutePath)
            .put("freeBytes", context.filesDir.usableSpace)
            .put("permissions", perms)
            .put("allowlistedBinsPresent", bins)
            .toString()
    }

    // ─── existing tools (memory/keys/fs/http/github/device/…) ─

    @JavascriptInterface fun appInfo(): String {
        val p = context.packageManager.getPackageInfo(context.packageName, 0)
        return JSONObject().put("name", "D-Harness").put("version", p.versionName)
            .put("package", context.packageName).put("sdk", Build.VERSION.SDK_INT)
            .put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL).toString()
    }

    @JavascriptInterface fun deviceInfo(): String = appInfo()

    @JavascriptInterface
    fun battery(): String {
        return try {
            val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            JSONObject().put("percent", if (scale > 0) level * 100.0 / scale else -1.0).put("charging", charging).toString()
        } catch (e: Exception) {
            JSONObject().put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun network(): String {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            JSONObject().put("online", caps != null)
                .put("wifi", caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true)
                .put("cellular", caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true).toString()
        } catch (e: Exception) {
            JSONObject().put("error", e.message).toString()
        }
    }

    @JavascriptInterface fun toast(message: String) {
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
        if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(d, VibrationEffect.DEFAULT_AMPLITUDE))
        else @Suppress("DEPRECATION") v.vibrate(d)
    }

    @JavascriptInterface
    fun notify(title: String, body: String): String {
        return try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(NotificationChannel("dharness", "D-Harness", NotificationManager.IMPORTANCE_DEFAULT))
            }
            nm.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(),
                NotificationCompat.Builder(context, "dharness")
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(title).setContentText(body).setAutoCancel(true).build())
            JSONObject().put("ok", true).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun clipboardWrite(text: String): String {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("dharness", text))
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
        val a = JSONArray(); mem.all.keys.forEach { a.put(it) }; return JSONObject().put("keys", a).toString()
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
        val a = JSONArray(); keys.all.keys.forEach { a.put(it) }; return JSONObject().put("keys", a).toString()
    }

    private fun githubToken(): String? =
        keys.getString("github", null) ?: keys.getString("github_pat", null)
            ?: keys.getString("GITHUB_TOKEN", null) ?: settings.getString("github_pat", null)

    @JavascriptInterface
    fun githubRequest(method: String, path: String, body: String?, callbackId: String) {
        val token = githubToken()
        if (token.isNullOrBlank()) {
            deliver(callbackId, JSONObject().put("ok", false).put("error", "missing PAT").toString())
            return
        }
        val url = if (path.startsWith("http")) path else "https://api.github.com${if (path.startsWith("/")) path else "/$path"}"
        val headers = JSONObject().put("Authorization", "Bearer $token")
            .put("Accept", "application/vnd.github+json").put("X-GitHub-Api-Version", "2022-11-28").toString()
        httpRequest(url, method.ifBlank { "GET" }, headers, body, callbackId)
    }

    @JavascriptInterface
    fun httpRequest(url: String, method: String, headersJson: String?, body: String?, callbackId: String) {
        if (inFlight.size > 10) {
            deliver(callbackId, JSONObject().put("ok", false).put("error", "too many in-flight").toString())
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
                    setRequestProperty("User-Agent", "D-Harness/1.3")
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
                        if (getRequestProperty("Content-Type") == null) setRequestProperty("Content-Type", "application/json")
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
                val out = JSONObject().put("status", code).put("ok", code in 200..299).put("text", text)
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
            context.startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) },
                    "Share"
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
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
                context.startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).apply {
                            type = mime.ifBlank { "text/plain" }
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        },
                        "Save"
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            JSONObject().put("ok", true).put("filename", safe).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    // ─── calc / text / time / uuid / fs extras / intent / display ─

    @JavascriptInterface
    fun calcEval(expr: String): String {
        return try {
            val cleaned = expr.replace(Regex("[^0-9+\\-*/%^().eE\\s]"), "")
            if (cleaned.isBlank()) return JSONObject().put("ok", false).put("error", "empty").toString()
            val result = evalExpr(cleaned)
            JSONObject().put("ok", true).put("result", result).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    private fun evalExpr(expr: String): Double {
        // shunting-yard style minimal evaluator
        data class Tok(val op: Boolean, val v: Double = 0.0, val c: Char = ' ')
        val toks = mutableListOf<Tok>()
        var i = 0
        val s = expr.replace(" ", "")
        while (i < s.length) {
            val c = s[i]
            if (c.isDigit() || c == '.') {
                var j = i + 1
                while (j < s.length && (s[j].isDigit() || s[j] == '.' || s[j] == 'e' || s[j] == 'E' || (s[j] == '-' && s[j - 1].lowercaseChar() == 'e'))) j++
                toks.add(Tok(false, s.substring(i, j).toDouble()))
                i = j
            } else if (c in "+-*/%^()") {
                toks.add(Tok(true, c = c))
                i++
            } else throw IllegalArgumentException("bad char $c")
        }
        val out = mutableListOf<Tok>()
        val ops = ArrayDeque<Char>()
        fun prec(c: Char) = when (c) { '+', '-' -> 1; '*', '/', '%' -> 2; '^' -> 3; else -> 0 }
        for (t in toks) {
            if (!t.op) out.add(t)
            else if (t.c == '(') ops.addFirst(t.c)
            else if (t.c == ')') {
                while (ops.isNotEmpty() && ops.first() != '(') out.add(Tok(true, c = ops.removeFirst()))
                if (ops.isNotEmpty() && ops.first() == '(') ops.removeFirst()
            } else {
                while (ops.isNotEmpty() && ops.first() != '(' && prec(ops.first()) >= prec(t.c)) {
                    out.add(Tok(true, c = ops.removeFirst()))
                }
                ops.addFirst(t.c)
            }
        }
        while (ops.isNotEmpty()) out.add(Tok(true, c = ops.removeFirst()))
        val st = ArrayDeque<Double>()
        for (t in out) {
            if (!t.op) st.addFirst(t.v)
            else {
                val b = st.removeFirst()
                val a = if (st.isEmpty()) 0.0 else st.removeFirst()
                val r = when (t.c) {
                    '+' -> a + b; '-' -> a - b; '*' -> a * b
                    '/' -> a / b; '%' -> a % b
                    '^' -> Math.pow(a, b)
                    else -> throw IllegalArgumentException("op")
                }
                st.addFirst(r)
            }
        }
        return st.first()
    }

    @JavascriptInterface
    fun calcConvert(value: Double, from: String, to: String): String {
        return try {
            fun toMeter(v: Double, u: String) = when (u.lowercase()) {
                "m" -> v; "km" -> v * 1000; "cm" -> v / 100; "mm" -> v / 1000
                "mi" -> v * 1609.344; "ft" -> v * 0.3048; "in" -> v * 0.0254
                else -> null
            }
            fun fromMeter(v: Double, u: String) = when (u.lowercase()) {
                "m" -> v; "km" -> v / 1000; "cm" -> v * 100; "mm" -> v * 1000
                "mi" -> v / 1609.344; "ft" -> v / 0.3048; "in" -> v / 0.0254
                else -> null
            }
            fun toKg(v: Double, u: String) = when (u.lowercase()) {
                "kg" -> v; "g" -> v / 1000; "lb" -> v * 0.45359237; "oz" -> v * 0.0283495231
                else -> null
            }
            fun fromKg(v: Double, u: String) = when (u.lowercase()) {
                "kg" -> v; "g" -> v * 1000; "lb" -> v / 0.45359237; "oz" -> v / 0.0283495231
                else -> null
            }
            fun toC(v: Double, u: String) = when (u.lowercase()) {
                "c", "celsius" -> v; "f", "fahrenheit" -> (v - 32) * 5 / 9; "k", "kelvin" -> v - 273.15
                else -> null
            }
            fun fromC(v: Double, u: String) = when (u.lowercase()) {
                "c", "celsius" -> v; "f", "fahrenheit" -> v * 9 / 5 + 32; "k", "kelvin" -> v + 273.15
                else -> null
            }
            fun toByte(v: Double, u: String) = when (u.lowercase()) {
                "b" -> v; "kb" -> v * 1000; "mb" -> v * 1e6; "gb" -> v * 1e9
                "kib" -> v * 1024; "mib" -> v * 1048576; "gib" -> v * 1073741824
                else -> null
            }
            fun fromByte(v: Double, u: String) = when (u.lowercase()) {
                "b" -> v; "kb" -> v / 1000; "mb" -> v / 1e6; "gb" -> v / 1e9
                "kib" -> v / 1024; "mib" -> v / 1048576; "gib" -> v / 1073741824
                else -> null
            }
            val f = from.lowercase(); val tt = to.lowercase()
            val result = when {
                toMeter(value, f) != null && fromMeter(0.0, tt) != null -> fromMeter(toMeter(value, f)!!, tt)
                toKg(value, f) != null && fromKg(0.0, tt) != null -> fromKg(toKg(value, f)!!, tt)
                toC(value, f) != null && fromC(0.0, tt) != null -> fromC(toC(value, f)!!, tt)
                toByte(value, f) != null && fromByte(0.0, tt) != null -> fromByte(toByte(value, f)!!, tt)
                else -> return JSONObject().put("ok", false).put("error", "unknown units").toString()
            }
            JSONObject().put("ok", true).put("result", result).put("from", from).put("to", to).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun calcHaversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): String {
        return try {
            val r = 6371.0
            val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
            val dp = Math.toRadians(lat2 - lat1); val dl = Math.toRadians(lon2 - lon1)
            val a = Math.sin(dp / 2).let { it * it } + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2).let { it * it }
            val km = 2 * r * Math.asin(Math.sqrt(a))
            JSONObject().put("ok", true).put("km", km).put("m", km * 1000).put("mi", km / 1.609344).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun textBase64(op: String, data: String): String {
        return try {
            if (op == "encode") {
                JSONObject().put("ok", true)
                    .put("result", Base64.encodeToString(data.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)).toString()
            } else {
                val bytes = Base64.decode(data, Base64.DEFAULT)
                JSONObject().put("ok", true).put("result", String(bytes, Charsets.UTF_8))
                    .put("bytes", bytes.size).toString()
            }
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun textUrl(op: String, data: String): String {
        return try {
            val r = if (op == "encode") java.net.URLEncoder.encode(data, "UTF-8")
            else java.net.URLDecoder.decode(data, "UTF-8")
            JSONObject().put("ok", true).put("result", r).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun textRegex(op: String, pattern: String, text: String, replacement: String?): String {
        return try {
            val re = Regex(pattern)
            when (op) {
                "match" -> JSONObject().put("ok", true).put("matches", re.containsMatchIn(text)).toString()
                "find" -> {
                    val arr = JSONArray()
                    re.findAll(text).take(50).forEach { arr.put(it.value) }
                    JSONObject().put("ok", true).put("matches", arr).toString()
                }
                "replace" -> JSONObject().put("ok", true)
                    .put("result", re.replace(text, replacement ?: "")).toString()
                else -> JSONObject().put("ok", false).put("error", "op").toString()
            }
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun textStats(text: String): String {
        val lines = if (text.isEmpty()) 0 else text.split('\n').size
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.size
        return JSONObject().put("ok", true).put("chars", text.length).put("lines", lines)
            .put("words", words).put("utf8Bytes", text.toByteArray(Charsets.UTF_8).size).toString()
    }

    @JavascriptInterface
    fun timeNow(): String {
        val ms = System.currentTimeMillis()
        val iso = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.format(java.util.Date(ms))
        return JSONObject().put("ok", true).put("ms", ms).put("iso", iso).toString()
    }

    @JavascriptInterface
    fun timeFormat(ms: Long, pattern: String?): String {
        return try {
            val p = pattern?.ifBlank { null } ?: "yyyy-MM-dd HH:mm:ss"
            val fmt = java.text.SimpleDateFormat(p, java.util.Locale.US)
            JSONObject().put("ok", true).put("result", fmt.format(java.util.Date(ms))).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun uuidV4(): String =
        JSONObject().put("ok", true).put("uuid", java.util.UUID.randomUUID().toString()).toString()

    @JavascriptInterface
    fun fsStat(path: String): String {
        return try {
            val f = safeFile(path)
            JSONObject().put("ok", true).put("exists", f.exists()).put("isFile", f.isFile)
                .put("isDir", f.isDirectory).put("bytes", if (f.exists()) f.length() else 0)
                .put("mtime", if (f.exists()) f.lastModified() else 0).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun fsExists(path: String): String {
        return try {
            JSONObject().put("ok", true).put("exists", safeFile(path).exists()).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun fsAppend(path: String, content: String): String {
        return try {
            val f = safeFile(path)
            f.parentFile?.mkdirs()
            f.appendText(content)
            JSONObject().put("ok", true).put("bytes", f.length()).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun openUrl(url: String): String {
        return try {
            webView.post {
                context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            JSONObject().put("ok", true).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun deviceDisplay(): String {
        val dm = context.resources.displayMetrics
        return JSONObject().put("ok", true)
            .put("widthPx", dm.widthPixels).put("heightPx", dm.heightPixels)
            .put("density", dm.density.toDouble()).put("dpi", dm.densityDpi)
            .put("scaledDensity", dm.scaledDensity.toDouble()).toString()
    }

    @JavascriptInterface
    fun randomBytes(n: Int): String {
        return try {
            val count = n.coerceIn(1, 64)
            val bytes = ByteArray(count)
            java.security.SecureRandom().nextBytes(bytes)
            JSONObject().put("ok", true).put("hex", hex(bytes)).put("n", count).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }


    @JavascriptInterface
    fun calcClamp(value: Double, min: Double, max: Double): String {
        val r = value.coerceIn(minOf(min, max), maxOf(min, max))
        return JSONObject().put("ok", true).put("result", r).toString()
    }

    @JavascriptInterface
    fun calcRound(value: Double, digits: Int): String {
        val d = digits.coerceIn(0, 12)
        val f = Math.pow(10.0, d.toDouble())
        return JSONObject().put("ok", true).put("result", Math.round(value * f) / f).toString()
    }

    @JavascriptInterface
    fun textCase(op: String, text: String): String {
        val r = when (op.lowercase()) {
            "lower" -> text.lowercase()
            "upper" -> text.uppercase()
            "title" -> text.split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
            else -> return JSONObject().put("ok", false).put("error", "op").toString()
        }
        return JSONObject().put("ok", true).put("result", r).toString()
    }

    @JavascriptInterface
    fun textTrim(text: String): String =
        JSONObject().put("ok", true).put("result", text.trim()).toString()

    @JavascriptInterface
    fun textSplit(text: String, sep: String, limit: Int): String {
        val parts = if (limit > 0) text.split(sep, limit = limit) else text.split(sep)
        return JSONObject().put("ok", true).put("parts", JSONArray(parts)).toString()
    }

    @JavascriptInterface
    fun textJoin(partsJson: String, sep: String): String {
        return try {
            val arr = JSONArray(partsJson)
            val list = List(arr.length()) { arr.getString(it) }
            JSONObject().put("ok", true).put("result", list.joinToString(sep)).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun jsonPretty(jsonStr: String, indent: Int): String {
        return try {
            val ind = indent.coerceIn(0, 8)
            val trimmed = jsonStr.trim()
            val formatted = if (trimmed.startsWith("[")) JSONArray(trimmed).toString(ind)
            else JSONObject(trimmed).toString(ind)
            JSONObject().put("ok", true).put("result", formatted).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun jsonParse(jsonStr: String): String {
        return try {
            val trimmed = jsonStr.trim()
            val v: Any = if (trimmed.startsWith("[")) JSONArray(trimmed) else JSONObject(trimmed)
            JSONObject().put("ok", true).put("value", v).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun colorHexRgb(op: String, value: String): String {
        return try {
            if (op == "to_rgb") {
                var h = value.removePrefix("#")
                if (h.length == 3) h = h.map { "$it$it" }.joinToString("")
                val n = h.toLong(16)
                JSONObject().put("ok", true)
                    .put("r", (n shr 16) and 255).put("g", (n shr 8) and 255).put("b", n and 255).toString()
            } else {
                // value like "255,128,0" or JSON
                val parts = value.replace(Regex("[^0-9,]"), "").split(",").map { it.toInt() }
                val r = parts.getOrElse(0) { 0 }.coerceIn(0, 255)
                val g = parts.getOrElse(1) { 0 }.coerceIn(0, 255)
                val b = parts.getOrElse(2) { 0 }.coerceIn(0, 255)
                JSONObject().put("ok", true).put("hex", String.format("#%02X%02X%02X", r, g, b)).toString()
            }
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun fsMkdir(path: String): String {
        return try {
            val f = safeFile(path)
            JSONObject().put("ok", f.mkdirs() || f.isDirectory).put("path", path).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun fsTouch(path: String): String {
        return try {
            val f = safeFile(path)
            f.parentFile?.mkdirs()
            if (!f.exists()) f.writeText("")
            else f.setLastModified(System.currentTimeMillis())
            JSONObject().put("ok", true).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun fsCopy(from: String, to: String): String {
        return try {
            val a = safeFile(from); val b = safeFile(to)
            if (!a.exists()) return JSONObject().put("ok", false).put("error", "missing").toString()
            b.parentFile?.mkdirs()
            a.copyTo(b, overwrite = true)
            JSONObject().put("ok", true).put("bytes", b.length()).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun fsMove(from: String, to: String): String {
        return try {
            val a = safeFile(from); val b = safeFile(to)
            if (!a.exists()) return JSONObject().put("ok", false).put("error", "missing").toString()
            b.parentFile?.mkdirs()
            val ok = a.renameTo(b)
            if (!ok) { a.copyTo(b, true); a.delete() }
            JSONObject().put("ok", true).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun diffLines(a: String, b: String): String {
        val la = a.split('\n')
        val lb = b.split('\n')
        val max = maxOf(la.size, lb.size).coerceAtMost(500)
        val changes = JSONArray()
        for (i in 0 until max) {
            val sa = la.getOrNull(i)
            val sb = lb.getOrNull(i)
            if (sa != sb) {
                changes.put(JSONObject().put("line", i + 1).put("a", sa ?: JSONObject.NULL).put("b", sb ?: JSONObject.NULL))
            }
        }
        return JSONObject().put("ok", true).put("changes", changes).put("count", changes.length()).toString()
    }

    @JavascriptInterface
    fun fileVerifyRoundtrip(): String {
        return try {
            val sample = "tabs:\tx\n° ′ ’ trailing\n"
            val bytes = sample.toByteArray(Charsets.UTF_8)
            val expected = sha256(bytes)
            val path = "_verify/roundtrip.txt"
            val f = safeFile(path)
            f.parentFile?.mkdirs()
            FileOutputStream(f).use { it.write(bytes) }
            val back = FileInputStream(f).use { it.readBytes() }
            val actual = sha256(back)
            val ok = expected == actual && back.contentEquals(bytes)
            JSONObject().put("ok", ok).put("expected", expected).put("actual", actual)
                .put("bytes", bytes.size).put("path", path).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }



    /** Synchronous GitHub API helper for workspace pull/push (binder thread OK for short calls). */
    private fun githubRequestSync(method: String, path: String, body: String?, token: String): JSONObject {
        val url = if (path.startsWith("http")) path else "https://api.github.com${if (path.startsWith("/")) path else "/$path"}"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method.ifBlank { "GET" }.uppercase()
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            doInput = true
            setRequestProperty("User-Agent", "D-Harness/1.4")
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            if (!body.isNullOrEmpty() && requestMethod != "GET" && requestMethod != "HEAD") {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }?.take(500_000) ?: ""
        val json = try { if (text.isNotBlank()) JSONObject(text) else null } catch (_: Exception) {
            try { JSONArray(text); null } catch (_: Exception) { null }
        }
        // If array response, wrap
        val out = JSONObject().put("status", code).put("ok", code in 200..299).put("text", text.take(100_000))
        if (json != null) out.put("json", json)
        else if (text.trimStart().startsWith("[")) {
            try { out.put("json", JSONArray(text)) } catch (_: Exception) {}
        }
        return out
    }

    // ─── Workspace (Termux-style agent home) ───────────────────

    @JavascriptInterface
    fun workspacePwd(): String {
        return JSONObject()
            .put("ok", true)
            .put("path", workspaceRoot.absolutePath)
            .put("exists", workspaceRoot.exists())
            .put("writable", workspaceRoot.canWrite())
            .toString()
    }

    @JavascriptInterface
    fun workspaceLs(path: String?): String {
        return try {
            val dir = safeWorkspace(path)
            if (!dir.exists()) return JSONObject().put("ok", false).put("error", "not found").toString()
            if (!dir.isDirectory) return JSONObject().put("ok", false).put("error", "not a directory").toString()
            val arr = JSONArray()
            dir.listFiles()?.sortedBy { it.name.lowercase() }?.forEach { f ->
                arr.put(JSONObject()
                    .put("name", f.name)
                    .put("type", if (f.isDirectory) "dir" else "file")
                    .put("size", if (f.isFile) f.length() else JSONObject.NULL)
                    .put("mtime", f.lastModified()))
            }
            JSONObject().put("ok", true).put("path", dir.absolutePath).put("entries", arr).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun workspaceRead(path: String, maxBytes: Int): String {
        return try {
            val f = safeWorkspace(path)
            if (!f.isFile) return JSONObject().put("ok", false).put("error", "not a file").toString()
            val limit = if (maxBytes > 0) maxBytes else 512_000
            val bytes = f.readBytes()
            val slice = if (bytes.size > limit) bytes.copyOf(limit) else bytes
            val text = slice.toString(Charsets.UTF_8)
            JSONObject()
                .put("ok", true)
                .put("path", f.absolutePath)
                .put("size", bytes.size)
                .put("truncated", bytes.size > limit)
                .put("content", text)
                .toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun workspaceWrite(path: String, content: String): String {
        return try {
            val f = safeWorkspace(path)
            f.parentFile?.mkdirs()
            f.writeText(content, Charsets.UTF_8)
            JSONObject().put("ok", true).put("path", f.absolutePath).put("bytes", f.length()).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun workspaceWriteB64(path: String, contentB64: String): String {
        return try {
            val f = safeWorkspace(path)
            f.parentFile?.mkdirs()
            val bytes = Base64.decode(contentB64, Base64.DEFAULT)
            FileOutputStream(f).use { it.write(bytes) }
            JSONObject().put("ok", true).put("path", f.absolutePath).put("bytes", bytes.size)
                .put("sha256", sha256(bytes)).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun workspaceReadB64(path: String): String {
        return try {
            val f = safeWorkspace(path)
            if (!f.isFile) return JSONObject().put("ok", false).put("error", "not a file").toString()
            val bytes = f.readBytes()
            JSONObject().put("ok", true).put("path", f.absolutePath).put("bytes", bytes.size)
                .put("sha256", sha256(bytes))
                .put("contentB64", Base64.encodeToString(bytes, Base64.NO_WRAP)).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun workspaceMkdir(path: String): String {
        return try {
            val f = safeWorkspace(path)
            val ok = f.mkdirs() || f.isDirectory
            JSONObject().put("ok", ok).put("path", f.absolutePath).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun workspaceRm(path: String): String {
        return try {
            val f = safeWorkspace(path)
            if (!f.exists()) return JSONObject().put("ok", false).put("error", "not found").toString()
            if (f.isDirectory) {
                val children = f.list()
                if (children != null && children.isNotEmpty()) {
                    return JSONObject().put("ok", false).put("error", "directory not empty").toString()
                }
            }
            val ok = f.delete()
            JSONObject().put("ok", ok).put("path", f.absolutePath).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun workspaceStat(path: String): String {
        return try {
            val f = safeWorkspace(path)
            if (!f.exists()) return JSONObject().put("ok", false).put("error", "not found").toString()
            JSONObject().put("ok", true)
                .put("path", f.absolutePath)
                .put("type", if (f.isDirectory) "dir" else "file")
                .put("size", if (f.isFile) f.length() else JSONObject.NULL)
                .put("mtime", f.lastModified())
                .put("readable", f.canRead())
                .put("writable", f.canWrite())
                .toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun workspaceTree(path: String?, depth: Int): String {
        return try {
            val maxDepth = depth.coerceIn(1, 4)
            fun walk(dir: File, d: Int): JSONArray {
                val arr = JSONArray()
                if (d > maxDepth || !dir.isDirectory) return arr
                dir.listFiles()?.sortedBy { it.name.lowercase() }?.forEach { f ->
                    val o = JSONObject().put("name", f.name).put("type", if (f.isDirectory) "dir" else "file")
                    if (f.isFile) o.put("size", f.length())
                    if (f.isDirectory && d < maxDepth) o.put("children", walk(f, d + 1))
                    arr.put(o)
                }
                return arr
            }
            val root = safeWorkspace(path)
            JSONObject().put("ok", true).put("path", root.absolutePath).put("tree", walk(root, 1)).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    /**
     * Download a file from GitHub Contents API into the workspace.
     * dest defaults to the repo-relative path basename under workspace/github/owner/repo/
     */
    @JavascriptInterface
    fun githubPull(owner: String, repo: String, path: String, ref: String?, dest: String?): String {
        return try {
            val token = githubToken()
                ?: return JSONObject().put("ok", false).put("error", "no github PAT in keys").toString()
            val q = if (!ref.isNullOrBlank()) "?ref=${java.net.URLEncoder.encode(ref, "UTF-8")}" else ""
            val apiPath = "/repos/$owner/$repo/contents/${path.trimStart('/').trim()}$q"
            val raw = githubRequestSync("GET", apiPath, null, token)
            val status = raw.optInt("status", 0)
            val json = raw.optJSONObject("json")
                ?: return JSONObject().put("ok", false).put("error", "bad response").put("status", status).toString()
            if (status !in 200..299) {
                return JSONObject().put("ok", false).put("error", raw.optString("text")).put("status", status).toString()
            }
            if (json.optString("type") == "dir") {
                return JSONObject().put("ok", false).put("error", "path is a directory; pull a file").toString()
            }
            val contentB64 = json.optString("content", "").replace("\n", "")
            if (contentB64.isEmpty()) {
                return JSONObject().put("ok", false).put("error", "empty content (use download_url for large files)").toString()
            }
            val bytes = Base64.decode(contentB64, Base64.DEFAULT)
            val destRel = if (!dest.isNullOrBlank()) dest.trim().removePrefix("/")
            else "github/$owner/$repo/${path.trimStart('/')}"
            val out = safeWorkspace(destRel)
            out.parentFile?.mkdirs()
            FileOutputStream(out).use { it.write(bytes) }
            JSONObject()
                .put("ok", true)
                .put("path", out.absolutePath)
                .put("rel", destRel)
                .put("bytes", bytes.size)
                .put("sha", json.optString("sha"))
                .put("sha256", sha256(bytes))
                .toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    /** Upload a workspace file to GitHub (create or update via Contents API). */
    @JavascriptInterface
    fun githubPushFile(
        owner: String, repo: String, path: String, branch: String,
        message: String, localPath: String
    ): String {
        return try {
            val token = githubToken()
                ?: return JSONObject().put("ok", false).put("error", "no github PAT in keys").toString()
            val local = safeWorkspace(localPath)
            if (!local.isFile) return JSONObject().put("ok", false).put("error", "local file not found").toString()
            val bytes = local.readBytes()
            val contentB64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            val remotePath = path.trimStart('/')
            // get existing sha if file exists
            var sha: String? = null
            try {
                val existing = githubRequestSync(
                    "GET",
                    "/repos/$owner/$repo/contents/$remotePath?ref=${java.net.URLEncoder.encode(branch, "UTF-8")}",
                    null,
                    token
                )
                if (existing.optInt("status") == 200) {
                    sha = existing.optJSONObject("json")?.optString("sha")
                }
            } catch (_: Exception) {}
            val body = JSONObject()
                .put("message", message)
                .put("content", contentB64)
                .put("branch", branch)
            if (!sha.isNullOrBlank()) body.put("sha", sha)
            val put = githubRequestSync("PUT", "/repos/$owner/$repo/contents/$remotePath", body.toString(), token)
            val status = put.optInt("status", 0)
            JSONObject()
                .put("ok", status in 200..299)
                .put("status", status)
                .put("json", put.opt("json"))
                .put("local", local.absolutePath)
                .put("bytes", bytes.size)
                .toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }


}
