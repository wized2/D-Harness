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
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.PowerManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import java.util.concurrent.CountDownLatch
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
    private val keys = SecureStore.keys(context)
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
    , "sh", "mksh", "bash", "toybox", "busybox")

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
        fun tool(name: String, desc: String, params: JSONObject, call: String? = null, example: String? = null) {
            val o = JSONObject().put("name", name).put("description", desc).put("params", params)
            val callForm = call ?: when {
                name.contains('.') -> {
                    val parts = name.split('.', limit = 2)
                    val args = params.keys().asSequence().toList()
                    if (args.isEmpty()) "${parts[0]}.${parts[1]}()"
                    else "${parts[0]}.${parts[1]}(${args.joinToString(", ")})"
                }
                else -> if (params.length() == 0) "$name()" else "$name(${params.keys().asSequence().joinToString(", ")})"
            }
            o.put("call", callForm)
            o.put("via", if (name.contains('.')) "global" else "dispatch")
            if (!example.isNullOrBlank()) o.put("example", example)
            tools.put(o)
        }
        tool("list_tools", "Full tool catalog with params, call form, and examples. Call this before inventing APIs.", JSONObject(), example = "return await list_tools()")
        tool("run_js", "Execute custom JS in the tool sandbox (last resort). Prefer native dotted tools. args.code required.", JSONObject().put("code", "string"), example = "{\"tool\":\"run_js\",\"args\":{\"code\":\"return await workspace.ls()\"}}")
        tool("selftest", "Probe which tools are actually bound", JSONObject())
        tool("research.web", "Multi-source web research (DDG + Wikipedia + pages)", JSONObject().put("query", "string").put("maxSources", "number?"))
        tool("research.preview", "URL title/description preview", JSONObject().put("url", "string"))
        tool("research.html_text", "Fetch URL and extract visible text", JSONObject().put("url", "string").put("maxChars", "number?"))
        tool("workspace.grep", "Search all workspace text files for a string or regex. Returns path+line hits.", JSONObject().put("query", "string").put("regex", "boolean?").put("maxHits", "number?"), example = "return await workspace.grep('TODO', false, 50)")
        tool("exec.lang", "Run a short script in an on-device runtime. Check exec.langs() first. cwd=workspace.", JSONObject().put("lang", "string").put("code", "string").put("timeoutMs", "number?"), example = "return await exec.lang('node', 'console.log(1+1)')")
        tool("exec.which", "Locate binary on PATH", JSONObject().put("bin", "string"))
        tool("exec.langs", "List available script runtimes on device", JSONObject())
        tool("text.regex_find", "Regex findall", JSONObject().put("text", "string").put("pattern", "string").put("flags", "string?"))
        tool("text.regex_replace", "Regex replace", JSONObject().put("text", "string").put("pattern", "string").put("replacement", "string"))
        tool("util.base64", "encode|decode", JSONObject().put("op", "string").put("data", "string"))
        tool("util.uuid", "UUID v4", JSONObject())
        tool("util.time", "Epoch + ISO + timezone", JSONObject())
        tool("research.plan", "Structured research plan (no network)", JSONObject().put("topic", "string"))
        tool("sensors.list", "List hardware sensors", JSONObject())
        tool("sensors.read", "One-shot sensor reading by type", JSONObject().put("type", "string"))
        tool("torch.set", "Flashlight on/off", JSONObject().put("on", "boolean"))
        tool("audio.volume", "Get/set stream volume", JSONObject().put("stream", "string?").put("level", "number?"))
        tool("audio.ringer", "Ringer mode get/set", JSONObject().put("mode", "string?"))
        tool("wakelock.acquire", "Partial wake lock (ms)", JSONObject().put("ms", "number?"))
        tool("wakelock.release", "Release wake lock", JSONObject())
        tool("diff.lines", "Unified-style line diff between two strings. Good for code review in-chat.", JSONObject().put("a", "string").put("b", "string"), example = "return await diff.lines(oldSrc, newSrc)")
        tool("toybox.list", "List toybox applets", JSONObject())
        tool("toybox.run", "Run toybox applet", JSONObject().put("applet", "string").put("args", "array?"))
        tool("describe", "Describe tool or group", JSONObject().put("name", "string"))
        tool("http_request", "HTTP native. No GitHub PAT — use github.request for api.github.com auth.", JSONObject().put("url", "string").put("method", "string?").put("headers", "object?").put("body", "string?"))
        tool("fetch_url", "Alias of http_request", JSONObject().put("url", "string").put("headers", "object?"))
        tool("github.request", "GitHub REST with stored PAT (keys.github). Prefer over http_request for GitHub", JSONObject().put("method", "string?").put("path", "string").put("body", "object?"))
        tool("github.me", "GET /user", JSONObject())
        tool("github.repos", "List repos (compact)", JSONObject().put("per_page", "number?"))
        tool("github.issues", "List issues", JSONObject().put("owner", "string").put("repo", "string"))
        tool("github.issue_comment", "Comment on issue/PR", JSONObject().put("owner", "string").put("repo", "string").put("number", "number").put("body", "string"))
        tool("github.pr", "Get PR", JSONObject().put("owner", "string").put("repo", "string").put("number", "number"))
        tool("github.pr_files", "List files changed in a PR with status and patch snippets.", JSONObject().put("owner", "string").put("repo", "string").put("number", "number"), example = "return await github.pr_files('o','r', 42)")
        tool("github.pr_reviews", "PR reviews", JSONObject().put("owner", "string").put("repo", "string").put("number", "number"))
        tool("github.pr_commits", "PR commits", JSONObject().put("owner", "string").put("repo", "string").put("number", "number"))
        tool("github.issue", "Get issue", JSONObject().put("owner", "string").put("repo", "string").put("number", "number"))
        tool("github.contents", "Repo file contents", JSONObject().put("owner", "string").put("repo", "string").put("path", "string").put("ref", "string?"))
        tool("github.search", "Search issues/PRs/code", JSONObject().put("query", "string").put("type", "string?"))
        tool("github.pr_create", "Create PR", JSONObject().put("owner", "string").put("repo", "string").put("title", "string").put("head", "string").put("base", "string?").put("body", "string?"))
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
        tool("workspace.read", "Read UTF-8 text from workspace. Use offset+maxBytes for large files.", JSONObject().put("path", "string").put("maxBytes", "int?").put("offset", "int?"))
        tool("workspace.write", "Write UTF-8 text file in workspace", JSONObject().put("path", "string").put("content", "string"))
        tool(
            "paste_box",
            "UI dialog: multi-line paste area + Done/Cancel. On Done saves to workspace path and returns absolute path. Use for large code/text the user must paste.",
            JSONObject().put("path", "string").put("title", "string?").put("hint", "string?")
        )
        tool("workspace.write_b64", "Write binary file from base64", JSONObject().put("path", "string").put("contentB64", "string"))
        tool("workspace.read_b64", "Read workspace file as base64 + sha256", JSONObject().put("path", "string"))
        tool("workspace.mkdir", "Create directory under workspace", JSONObject().put("path", "string"))
        tool("workspace.rm", "Delete file or empty dir in workspace", JSONObject().put("path", "string"))
        tool("workspace.stat", "Stat path in workspace", JSONObject().put("path", "string"))
        tool("workspace.tree", "Shallow tree listing", JSONObject().put("path", "string?").put("depth", "int?"))
        tool("github.pull", "Download GitHub file into workspace", JSONObject().put("owner", "string").put("repo", "string").put("path", "string").put("ref", "string?").put("dest", "string?"))
        tool("github.push_file", "Create/update a file on GitHub from a workspace path (Contents API). Needs PAT.", JSONObject().put("owner", "string").put("repo", "string").put("path", "string").put("branch", "string").put("message", "string").put("localPath", "string"), example = "return await github.push_file('o','r','src/a.kt','main','msg','src/a.kt')")
        tool("file.verify_roundtrip", "Write then read-back SHA-256 of UTF-8 test bytes", JSONObject())
        tool("exec", "Allowlisted ProcessBuilder in app sandbox", JSONObject().put("argv", "string[]").put("timeout_ms", "number?").put("cwd", "string?"))
        tool("sqlite.query", "Read-only SQLite query", JSONObject().put("path", "string").put("sql", "string").put("args", "string[]?"))
        tool("crypto.hash", "SHA-256/SHA-1/MD5", JSONObject().put("algo", "string").put("data", "string").put("text", "string? alias of data").put("encoding", "utf8|b64?"))
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
        tool("text.stats", "Length/lines/words of text (alias: text.hash_preview)", JSONObject().put("text", "string"))
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
        tool("json.pretty", "Validate and pretty-print a JSON string. Errors if invalid.", JSONObject().put("json", "string").put("indent", "number?"), example = "return await json.pretty('{\"a\":1}', 2)")
        tool("json.parse", "Parse JSON string → object. Throws structured error if invalid.", JSONObject().put("json", "string"), example = "return await json.parse(text)")
        tool("code.outline", "Outline symbols in source: functions, classes, headers. Pass workspace path OR raw content.", JSONObject().put("path", "string?").put("content", "string?").put("max", "number?"), example = "return await code.outline('src/Main.kt', null, 80)")
        tool("code.find_todos", "Find TODO/FIXME/HACK comments under a workspace path.", JSONObject().put("path", "string?").put("maxHits", "number?"), example = "return await code.find_todos('.', 40)")
        tool("github.issue_create", "Create a GitHub issue.", JSONObject().put("owner", "string").put("repo", "string").put("title", "string").put("body", "string?").put("labels", "string?"), example = "return await github.issue_create('o','r','Bug','steps', 'bug')")
        tool("github.commits", "List recent commits on a repo/ref.", JSONObject().put("owner", "string").put("repo", "string").put("sha", "string?").put("per_page", "number?"), example = "return await github.commits('o','r','main', 10)")
        tool("color.hex_rgb", "hex↔rgb", JSONObject().put("op", "to_rgb|to_hex").put("value", "string"))
        tool("fs.mkdir", "Create directory", JSONObject().put("path", "string"))
        tool("fs.touch", "Create empty file", JSONObject().put("path", "string"))
        tool("fs.copy", "Copy file in sandbox", JSONObject().put("from", "string").put("to", "string"))
        tool("fs.move", "Move/rename in sandbox", JSONObject().put("from", "string").put("to", "string"))
        tool("diff.lines", "Simple line diff a vs b", JSONObject().put("a", "string").put("b", "string"))



        tool("device.uptime", "Device uptime millis/hours", JSONObject())
        tool("device.storage", "Internal free/total storage bytes", JSONObject())
        tool("device.memory", "Runtime + ActivityManager memory", JSONObject())
        tool("github.branch_create", "Create branch from SHA/ref", JSONObject().put("owner", "string").put("repo", "string").put("branch", "string").put("from", "string?"))
        tool("github.compare", "Compare base...head", JSONObject().put("owner", "string").put("repo", "string").put("base", "string").put("head", "string"))
        tool("file.append", "Append UTF-8 to harness_fs file", JSONObject().put("path", "string").put("content", "string"))
        tool("workspace.append", "Append UTF-8 in workspace", JSONObject().put("path", "string").put("content", "string"))
        tool("text.replace", "Replace all in string", JSONObject().put("text", "string").put("find", "string").put("replace", "string"))
        tool("text.lines", "Split text into lines", JSONObject().put("text", "string"))
        tool("device.locale", "Device locale / language tags", JSONObject())
        tool("device.timezone", "Default timezone id + offset", JSONObject())
        tool("device.sensors", "List available hardware sensors", JSONObject().put("limit", "number?"))
        tool("net.dns", "Resolve host via InetAddress", JSONObject().put("host", "string"))
        tool("time.sleep", "Sleep on native thread (ms, max 10000)", JSONObject().put("ms", "number"))
        tool("text.snippet", "First N lines of text", JSONObject().put("text", "string").put("lines", "number?"))
        tool("github.repo", "Compact repo metadata", JSONObject().put("owner", "string").put("repo", "string"))

        // ── Extra coding + GitHub (1.9.7) ─────────────────────
        tool("code.search", "Regex search workspace source files by extension filter.", JSONObject().put("query", "string").put("path", "string?").put("ext", "string?").put("maxHits", "number?"), example = "return await code.search('class Main', '.', 'kt,java', 30)")
        tool("code.slice", "Read line range from a workspace file (1-based, inclusive).", JSONObject().put("path", "string").put("start", "number").put("end", "number?"), example = "return await code.slice('src/A.kt', 10, 40)")
        tool("code.count_lines", "Count lines / non-empty / chars for a file or raw content.", JSONObject().put("path", "string?").put("content", "string?"))
        tool("code.imports", "Extract import/require/include lines from source.", JSONObject().put("path", "string?").put("content", "string?"))
        tool("code.detect_lang", "Guess language from path extension or content heuristics.", JSONObject().put("path", "string?").put("content", "string?"))
        tool("workspace.replace", "Find/replace in a workspace file (literal or regex).", JSONObject().put("path", "string").put("find", "string").put("replace", "string").put("regex", "boolean?"), example = "return await workspace.replace('a.txt', 'foo', 'bar', false)")
        tool("workspace.apply_patch", "Apply sequential exact search/replace edits to one file (Claude Code style). Fails if old not unique unless replace_all.", JSONObject().put("path", "string").put("edits", "array of {old,new,replace_all?}"), example = "return await DHarness.workspaceApplyPatch('a.kt', JSON.stringify([{old:'foo',new:'bar'}]))")
        tool("workspace.head", "First N lines of a workspace file.", JSONObject().put("path", "string").put("lines", "number?"))
        tool("workspace.tail", "Last N lines of a workspace file.", JSONObject().put("path", "string").put("lines", "number?"))
        tool("workspace.glob", "List files under path matching a simple glob (*.kt, **/*.js).", JSONObject().put("pattern", "string").put("path", "string?").put("max", "number?"))
        tool("json.merge", "Shallow-merge two JSON objects (string or object).", JSONObject().put("a", "string").put("b", "string"))
        tool("json.keys", "List top-level keys of a JSON object.", JSONObject().put("json", "string"))
        tool("text.word_count", "Words, lines, chars for text.", JSONObject().put("text", "string"))
        tool("diff.file", "Line-diff two workspace files.", JSONObject().put("pathA", "string").put("pathB", "string"))
        tool("github.pr_list", "List pull requests (state: open|closed|all).", JSONObject().put("owner", "string").put("repo", "string").put("state", "string?").put("per_page", "number?"), example = "return await github.pr_list('o','r','open',10)")
        tool("github.pr_merge", "Merge a PR (merge|squash|rebase).", JSONObject().put("owner", "string").put("repo", "string").put("number", "number").put("method", "string?"), example = "return await github.pr_merge('o','r',12,'squash')")
        tool("github.issue_update", "Update issue title/body/state (open|closed).", JSONObject().put("owner", "string").put("repo", "string").put("number", "number").put("title", "string?").put("body", "string?").put("state", "string?"))
        tool("github.labels", "List repo labels.", JSONObject().put("owner", "string").put("repo", "string"))
        tool("github.branches", "List branches.", JSONObject().put("owner", "string").put("repo", "string").put("per_page", "number?"))
        tool("github.release_latest", "Latest release metadata.", JSONObject().put("owner", "string").put("repo", "string"))
        tool("github.releases", "List releases.", JSONObject().put("owner", "string").put("repo", "string").put("per_page", "number?"))
        tool("github.workflows", "List GitHub Actions workflows.", JSONObject().put("owner", "string").put("repo", "string"))
        tool("github.workflow_runs", "List recent workflow runs.", JSONObject().put("owner", "string").put("repo", "string").put("per_page", "number?"))
        tool("github.tree", "Git tree listing for a ref (recursive optional).", JSONObject().put("owner", "string").put("repo", "string").put("ref", "string?").put("recursive", "boolean?"))
        tool("github.user", "Get a GitHub user profile.", JSONObject().put("username", "string"))
        tool("github.gist_create", "Create a secret/public gist from filename→content map JSON.", JSONObject().put("files", "object").put("description", "string?").put("public", "boolean?"), example = "return await github.gist_create({note:'hi'}, 'demo', false)")
        tool("github.pr_comment", "Comment on a PR (issue comments API).", JSONObject().put("owner", "string").put("repo", "string").put("number", "number").put("body", "string"))
        tool("github.forks", "List forks of a repo.", JSONObject().put("owner", "string").put("repo", "string").put("per_page", "number?"))
        tool("github.tags", "List tags.", JSONObject().put("owner", "string").put("repo", "string").put("per_page", "number?"))

        return JSONObject()
            .put("tools", tools)
            .put("native", true)
            .put("version", try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
            } catch (_: Exception) { "?" })
            .put("envelope", "{ok, data|error, meta:{ms}} — prefer this shape")
            .put("conventions", JSONObject()
                .put("run_js", "{\"tool\":\"run_js\",\"description\":\"…\",\"args\":{\"code\":\"return await TOOL()\"}}")
                .put("flat", "{\"tool\":\"name\",\"args\":{…}}")
                .put("group", "{\"tool\":\"group\",\"args\":{\"op\":\"…\"}}")
            )
            .put("call", "{\"tool\":\"run_js\",\"args\":{\"code\":\"return await TOOL()\"}}")
            .put("notes", JSONObject()
                .put("format", "One JSON object per reply: {tool,description,args}. Prefer run_js with globals. Wait for TOOL_RESULT:")
                .put("globals", "In run_js: workspace, github, device, exec, list_tools, describe, memory, keys")
                .put("memory", "Scratch KV: memory.get/set/list/delete/clear")
                .put("keys", "Encrypted secrets: keys.set('github', pat) — never print values")
                .put("workspace", "Sandbox: pwd/ls/tree/read/write/append/grep/replace/glob/head/tail")
                .put("paste_box", "UI paste; no timeout; args.path; returns {ok,path,bytes} or cancelled")
                .put("github", "PAT in keys.github; me/repos/pr_*/issue_*/contents/workflows/request/…")
                .put("http", "http_request / research.web — no browser CORS")
                .put("skills", "Explore workspace → edit file; GitHub auth → me; research.plan→web; paste_box for long input")
                .put("file.commit", "Byte-exact base64 writes with optional sha256 verify")
                .put("exec", "Allowlisted binaries + optional sh -c (Settings exec_shell). Returns {exitCode,stdout,stderr,timedOut}. cwd=workspace.")
            )
            .put("examples", JSONArray()
                .put("return await list_tools()")
                .put("return await describe('github')")
                .put("return await memory.set('k','v')")
                .put("return await workspace.ls()")
                .put("return await paste_box({path:'src/main.kt',title:'Paste Kotlin'})")
                .put("return await github.me()")
                .put("return await http_request({url:'https://example.com',method:'GET'})")
                .put("return await device.info()")
            ).toString()
    }

    @JavascriptInterface
    fun describeTool(name: String): String {
        val raw = name.trim()
        val bound = boundNativeMethods()
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
                (raw == "sqlite" && n.startsWith("sqlite.")) ||
                (raw == "sensors" && n.startsWith("sensors.")) ||
                (raw == "workspace" && n.startsWith("workspace."))
            ) {
                // Map catalog name → likely DHarness method
                val methodGuess = catalogToMethod(n)
                t.put("bound", methodGuess != null && bound.contains(methodGuess))
                t.put("dharnessMethod", methodGuess ?: JSONObject.NULL)
                t.put("convention", "DHarness." + (methodGuess ?: n))
                matches.put(t)
            }
        }
        // Also: if raw is a DHarness method name
        if (matches.length() == 0 && bound.contains(raw)) {
            return JSONObject()
                .put("name", raw)
                .put("bound", true)
                .put("dharnessMethod", raw)
                .put("convention", "DHarness.$raw(...)")
                .put("via", "JavascriptInterface")
                .toString()
        }
        if (matches.length() == 1) return matches.getJSONObject(0).toString()
        if (matches.length() > 1) {
            return JSONObject().put("name", raw).put("variants", matches).put("count", matches.length()).toString()
        }
        return JSONObject().put("ok", false).put("error", "unknown tool: $raw")
            .put("hint", "Call DHarness.selftest() or list_tools(); primary API is DHarness.*").toString()
    }

    private fun boundNativeMethods(): Set<String> {
        return this::class.java.methods
            .filter { m -> m.getAnnotation(JavascriptInterface::class.java) != null }
            .map { it.name }
            .toSet()
    }

    private fun catalogToMethod(catalogName: String): String? {
        // Heuristic mapping catalog labels → JavascriptInterface method names
        val map = mapOf(
            "list_tools" to "listTools",
            "describe" to "describeTool",
            "http_request" to "httpRequest",
            "fetch_url" to "httpRequest",
            "clipboard.read" to "clipboardRead",
            "clipboard.write" to "clipboardWrite",
            "clipboard.copy" to "clipboardWrite",
            "workspace.pwd" to "workspacePwd",
            "workspace.ls" to "workspaceLs",
            "workspace.read" to "workspaceRead",
            "workspace.write" to "workspaceWrite",
            "exec" to "exec",
            "sensors.list" to "sensorsList",
            "sensors.read" to "sensorsRead",
            "torch.set" to "torchSet",
            "selftest" to "selftest",
        )
        map[catalogName]?.let { return it }
        // camelCase last segment: github.me → not direct; workspace.mkdir → workspaceMkdir
        if (catalogName.contains('.')) {
            val parts = catalogName.split('.', limit = 2)
            val camel = parts[0] + parts[1].replaceFirstChar { it.uppercase() }
            if (boundNativeMethods().contains(camel)) return camel
            // githubRequest style
            val joined = parts[0] + parts[1].split('_', '.').joinToString("") { s -> s.replaceFirstChar { c -> c.uppercase() } }
        }
        return null
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
            var argv = MutableList(arr.length()) { arr.getString(it) }
            var bin = argv[0].substringAfterLast('/')

            // Resolve shell: prefer /system/bin/sh when agent asks for sh -c
            if (bin == "sh" || bin == "mksh" || bin == "bash") {
                val shellPath = listOf("/system/bin/sh", "/system/bin/mksh", "/vendor/bin/sh")
                    .firstOrNull { File(it).exists() && File(it).canExecute() }
                    ?: return JSONObject().put("ok", false)
                        .put("error", "no shell binary on device").put("allow", JSONArray(execAllow.toList())).toString()
                argv = (listOf(shellPath) + argv.drop(1)).toMutableList()
                bin = "sh"
            } else if (bin !in execAllow) {
                return JSONObject().put("ok", false).put("error", "binary not allowlisted: $bin")
                    .put("allow", JSONArray(execAllow.toList().sorted())).toString()
            } else {
                // Prefer absolute path under /system/bin when present
                val candidate = File("/system/bin", bin)
                if (candidate.exists() && candidate.canExecute()) {
                    argv[0] = candidate.absolutePath
                }
            }

            val shellEnabled = settings.getBoolean("exec_shell", false)
            if (bin == "sh" && !shellEnabled) {
                return JSONObject().put("ok", false)
                    .put("error", "shell exec disabled in Settings (exec_shell)").toString()
            }

            val cwd = when {
                !cwdRel.isNullOrBlank() -> {
                    val raw = cwdRel.trim()
                    val rootCanon = workspaceRoot.canonicalPath
                    val f = try {
                        val asFile = File(raw)
                        when {
                            asFile.isAbsolute -> asFile
                            raw.startsWith(rootCanon) -> File(raw)
                            else -> File(workspaceRoot, raw.trimStart('/'))
                        }
                    } catch (_: Exception) {
                        File(workspaceRoot, raw.trimStart('/'))
                    }
                    val canon = try { f.canonicalPath } catch (_: Exception) { f.absolutePath }
                    if (!canon.startsWith(rootCanon)) {
                        return JSONObject().put("ok", false).put("error", "cwd escapes workspace").toString()
                    }
                    f.also { if (!it.isDirectory) it.mkdirs() }
                }
                else -> workspaceRoot
            }

            val t0 = System.currentTimeMillis()
            val pb = ProcessBuilder(argv).directory(cwd).redirectErrorStream(false)
            pb.environment()["HOME"] = workspaceRoot.absolutePath
            pb.environment()["TMPDIR"] = context.cacheDir.absolutePath
            val proc = pb.start()
            val pid = try {
                val m = proc.javaClass.getMethod("pid")
                (m.invoke(proc) as Long).toInt()
            } catch (_: Exception) {
                (100000 + (Math.random() * 900000).toInt())
            }
            childProcs[pid] = proc

            // Drain streams on background threads to avoid pipe deadlock
            val stdoutBox = arrayOfNulls<String>(1)
            val stderrBox = arrayOfNulls<String>(1)
            val outT = Thread {
                stdoutBox[0] = proc.inputStream.bufferedReader().use { it.readText() }.take(80_000)
            }.also { it.start() }
            val errT = Thread {
                stderrBox[0] = proc.errorStream.bufferedReader().use { it.readText() }.take(40_000)
            }.also { it.start() }

            val finished = proc.waitFor(timeoutMs.coerceIn(500, 120_000).toLong(), TimeUnit.MILLISECONDS)
            if (!finished) {
                try { proc.javaClass.getMethod("destroyForcibly").invoke(proc) } catch (_: Exception) { proc.destroy() }
                childProcs.remove(pid)
                outT.join(500); errT.join(500)
                return JSONObject().put("ok", false).put("error", "timeout")
                    .put("exitCode", -1).put("timedOut", true).put("pid", pid)
                    .put("stdout", stdoutBox[0] ?: "").put("stderr", stderrBox[0] ?: "")
                    .put("durationMs", System.currentTimeMillis() - t0).toString()
            }
            outT.join(2000); errT.join(2000)
            val code = proc.exitValue()
            childProcs.remove(pid)
            JSONObject()
                .put("ok", code == 0)
                .put("exitCode", code)
                .put("code", code)
                .put("stdout", stdoutBox[0] ?: "")
                .put("stderr", stderrBox[0] ?: "")
                .put("timedOut", false)
                .put("durationMs", System.currentTimeMillis() - t0)
                .put("pid", pid)
                .put("cwd", cwd.absolutePath)
                .toString()
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
        return try {
            val value = when {
                text == "[object Object]" -> return JSONObject().put("ok", false)
                    .put("error", "ARGS_NOT_UNWRAPPED")
                    .put("hint", "pass args.text as a string").toString()
                else -> text
            }
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("dharness", value))
            // read-back verify
            val back = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() ?: ""
            val verified = back == value
            JSONObject().put("ok", verified)
                .put("verified", verified)
                .put("bytes", value.length)
                .apply { if (!verified) put("error", "clipboard read-back mismatch") }
                .toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
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
        keys.getString("github", null)
            ?: keys.getString("github_pat", null)
            ?: keys.getString("GITHUB_TOKEN", null)

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
                val rawAll = stream?.bufferedReader()?.use { it.readText() } ?: ""
                val truncated = rawAll.length > 400_000
                val text = if (truncated) rawAll.take(400_000) else rawAll
                var parsed: Any? = null
                try { parsed = JSONObject(text) } catch (_: Exception) {
                    try { parsed = JSONArray(text) } catch (_: Exception) {}
                }
                val out = JSONObject().put("status", code).put("ok", code in 200..299).put("text", text).put("truncated", truncated)
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
            if (content == "[object Object]") {
                return JSONObject().put("ok", false).put("error", "ARGS_NOT_UNWRAPPED")
                    .put("hint", "pass path and content as strings").toString()
            }
            val f = safeFile(path)
            f.parentFile?.mkdirs()
            // history snapshot
            historySnapshot(f)
            val bytes = content.toByteArray(Charsets.UTF_8)
            val expected = sha256(bytes)
            val tmp = File(f.parentFile, ".${f.name}.tmp-${System.nanoTime()}")
            FileOutputStream(tmp).use { out -> out.write(bytes); out.fd.sync() }
            if (!tmp.renameTo(f)) {
                FileOutputStream(f).use { it.write(bytes); it.fd.sync() }
                tmp.delete()
            }
            val actual = sha256(f.readBytes())
            if (actual != expected) {
                return JSONObject().put("ok", false).put("error", "VERIFICATION_FAILED")
                    .put("sha256_expected", expected).put("sha256_actual", actual).toString()
            }
            JSONObject().put("ok", true).put("path", path).put("bytes", bytes.size)
                .put("sha256", actual).put("verified", true).toString()
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
            val f = safeFile(path)
            val existed = f.exists()
            if (!existed) {
                return JSONObject().put("ok", true).put("existed", false).put("nowExists", false).toString()
            }
            historySnapshot(f)
            val ok = if (f.isDirectory) f.deleteRecursively() else f.delete()
            val nowExists = f.exists()
            JSONObject().put("ok", ok && !nowExists)
                .put("existed", true)
                .put("nowExists", nowExists)
                .apply { if (nowExists) put("error", "delete did not persist") }
                .toString()
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
    fun codeOutline(path: String?, content: String?, max: Int): String {
        return try {
            val src = when {
                !content.isNullOrBlank() -> content
                !path.isNullOrBlank() -> safeFile(path).readText()
                else -> return JSONObject().put("ok", false).put("error", "path or content required").toString()
            }
            val limit = if (max <= 0) 100 else max.coerceAtMost(400)
            val patterns = listOf(
                Regex("""(?m)^\s*(?:export\s+)?(?:async\s+)?function\s+([A-Za-z0-9_]+)"""),
                Regex("""(?m)^\s*(?:export\s+)?(?:const|let|var)\s+([A-Za-z0-9_]+)\s*=\s*(?:async\s*)?\("""),
                Regex("""(?m)^\s*(?:export\s+)?class\s+([A-Za-z0-9_]+)"""),
                Regex("""(?m)^\s*(?:fun|suspend\s+fun)\s+([A-Za-z0-9_]+)"""),
                Regex("""(?m)^\s*(?:public|private|protected|internal)?\s*(?:static\s+)?(?:final\s+)?(?:\w+\s+)+([A-Za-z0-9_]+)\s*\("""),
                Regex("""(?m)^\s*def\s+([A-Za-z0-9_]+)"""),
                Regex("""(?m)^#{1,3}\s+(.+)$"""),
            )
            val out = JSONArray()
            val lines = src.split('\n')
            for ((li, line) in lines.withIndex()) {
                for (re in patterns) {
                    val m = re.find(line) ?: continue
                    val name = m.groupValues.getOrNull(1)?.trim() ?: continue
                    out.put(JSONObject().put("line", li + 1).put("name", name).put("text", line.trim().take(120)))
                    if (out.length() >= limit) break
                }
                if (out.length() >= limit) break
            }
            JSONObject().put("ok", true).put("count", out.length()).put("symbols", out).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun codeFindTodos(path: String?, maxHits: Int): String {
        return try {
            val root = if (path.isNullOrBlank()) workspaceRoot else safeFile(path)
            val limit = if (maxHits <= 0) 50 else maxHits.coerceAtMost(200)
            val hits = JSONArray()
            val re = Regex("""(?i)\b(TODO|FIXME|HACK|XXX)\b.*""")
            fun walk(f: java.io.File) {
                if (hits.length() >= limit) return
                if (f.isDirectory) {
                    f.listFiles()?.forEach { walk(it) }
                    return
                }
                if (f.length() > 1_000_000) return
                val name = f.name.lowercase()
                if (!name.endsWith(".kt") && !name.endsWith(".java") && !name.endsWith(".js") &&
                    !name.endsWith(".ts") && !name.endsWith(".py") && !name.endsWith(".md") &&
                    !name.endsWith(".c") && !name.endsWith(".cpp") && !name.endsWith(".h") &&
                    !name.endsWith(".go") && !name.endsWith(".rs") && !name.endsWith(".swift")) return
                f.readLines().forEachIndexed { i, line ->
                    if (hits.length() >= limit) return
                    if (re.containsMatchIn(line)) {
                        val rel = try { f.relativeTo(workspaceRoot).path } catch (_: Exception) { f.name }
                        hits.put(JSONObject().put("path", rel).put("line", i + 1).put("text", line.trim().take(160)))
                    }
                }
            }
            walk(root)
            JSONObject().put("ok", true).put("count", hits.length()).put("hits", hits).toString()
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
    fun workspaceRead(path: String, maxBytes: Int): String = workspaceReadRange(path, maxBytes, 0)

    @JavascriptInterface
    fun workspaceReadRange(path: String, maxBytes: Int, offset: Int): String {
        return try {
            val f = safeWorkspace(path)
            if (!f.isFile) return JSONObject().put("ok", false).put("error", "not a file").toString()
            val bytes = f.readBytes()
            val off = offset.coerceIn(0, bytes.size)
            val limit = if (maxBytes > 0) maxBytes else 512_000
            val end = (off + limit).coerceAtMost(bytes.size)
            val slice = if (off == 0 && end == bytes.size) bytes else bytes.copyOfRange(off, end)
            val text = slice.toString(Charsets.UTF_8)
            JSONObject()
                .put("ok", true)
                .put("path", f.absolutePath)
                .put("size", bytes.size)
                .put("offset", off)
                .put("maxBytes", limit)
                .put("returnedBytes", slice.size)
                .put("truncated", end < bytes.size)
                .put("content", text)
                .toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }


    /**
     * UI paste dialog. Model supplies [path] (workspace-relative). User pastes, taps Done →
     * file is written; result includes absolute path + dir. Cancel → cancelled:true.
     */

    /**
     * Paste dialog on UI thread. Model supplies workspace-relative [path].
     * Done → write file, return absolute path. Cancel → cancelled:true.
     */
    @JavascriptInterface
    fun pasteBox(path: String?, title: String?, hint: String?, callbackId: String) {
        val activity = context as? android.app.Activity
        if (activity == null || activity.isFinishing) {
            deliver(callbackId, JSONObject().put("ok", false).put("error", "no activity").toString())
            return
        }
        val safePath = (path ?: "paste.txt").trim().ifEmpty { "paste.txt" }
        activity.runOnUiThread {
            try {
                val density = activity.resources.displayMetrics.density
                val pad = (20 * density).toInt()
                val scroll = android.widget.ScrollView(activity)
                val container = android.widget.LinearLayout(activity).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    setPadding(pad, pad / 2, pad, pad / 2)
                }
                val pathLabel = android.widget.TextView(activity).apply {
                    text = "Save as: $safePath"
                    setTextColor(0xFFA8B0C0.toInt())
                    textSize = 13f
                    setPadding(0, 0, 0, (10 * density).toInt())
                }
                val input = android.widget.EditText(activity).apply {
                    minLines = 12
                    maxLines = 24
                    gravity = android.view.Gravity.TOP or android.view.Gravity.START
                    inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                        android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    this.hint = hint?.takeIf { it.isNotBlank() } ?: "Paste text or code here"
                    setTextColor(0xFFE8EAED.toInt())
                    setHintTextColor(0xFF6B7385.toInt())
                    setBackgroundColor(0xFF1E2430.toInt())
                    setPadding(pad / 2, pad / 2, pad / 2, pad / 2)
                    isVerticalScrollBarEnabled = true
                    isFocusable = true
                    isFocusableInTouchMode = true
                }
                container.addView(pathLabel)
                container.addView(
                    input,
                    android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        (240 * density).toInt()
                    )
                )
                scroll.addView(container)
                var delivered = false
                fun once(json: String) {
                    if (delivered) return
                    delivered = true
                    deliver(callbackId, json)
                }
                val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
                    .setTitle(title?.takeIf { it.isNotBlank() } ?: "Paste content")
                    .setView(scroll)
                    .setPositiveButton("Done", null)
                    .setNegativeButton("Cancel") { _, _ ->
                        once(JSONObject().put("ok", false).put("cancelled", true).put("error", "cancelled").toString())
                    }
                    .setOnCancelListener {
                        once(JSONObject().put("ok", false).put("cancelled", true).put("error", "cancelled").toString())
                    }
                    .create()
                dialog.setOnShowListener {
                    input.requestFocus()
                    dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                        val content = input.text?.toString() ?: ""
                        io.execute {
                            try {
                                val f = safeWorkspace(safePath)
                                f.parentFile?.mkdirs()
                                f.writeText(content, Charsets.UTF_8)
                                once(
                                    JSONObject()
                                        .put("ok", true)
                                        .put("path", f.absolutePath)
                                        .put("relative", safePath)
                                        .put("dir", f.parentFile?.absolutePath ?: workspaceRoot.absolutePath)
                                        .put("workspace", workspaceRoot.absolutePath)
                                        .put("bytes", f.length())
                                        .toString()
                                )
                                activity.runOnUiThread {
                                    try { dialog.dismiss() } catch (_: Exception) {}
                                }
                            } catch (e: Exception) {
                                once(JSONObject().put("ok", false).put("error", e.message ?: "write failed").toString())
                                activity.runOnUiThread {
                                    try { dialog.dismiss() } catch (_: Exception) {}
                                }
                            }
                        }
                    }
                }
                dialog.show()
            } catch (e: Exception) {
                android.util.Log.e("DHarness", "pasteBox", e)
                deliver(callbackId, JSONObject().put("ok", false).put("error", e.message ?: "dialog failed").toString())
            }
        }
    }


    /** Absolute workspace path (for Settings / explorer). */
    @JavascriptInterface
    fun workspaceRootPath(): String =
        JSONObject().put("ok", true).put("path", workspaceRoot.absolutePath).toString()

    /** Atomic UTF-8 write: temp + fsync + rename, then read-back verify. */
    @JavascriptInterface
    fun workspaceWrite(path: String, content: String): String {
        return try {
            // Guard: if content looks like stringified object, reject
            if (content == "[object Object]") {
                return JSONObject().put("ok", false).put("error", "ARGS_NOT_UNWRAPPED")
                    .put("hint", "pass path and content as separate string fields").toString()
            }
            val f = safeWorkspace(path)
            f.parentFile?.mkdirs()
            historySnapshot(f)
            val bytes = content.toByteArray(Charsets.UTF_8)
            val expected = sha256(bytes)
            val tmp = File(f.parentFile, ".${f.name}.tmp-${System.nanoTime()}")
            try {
                FileOutputStream(tmp).use { out ->
                    out.write(bytes)
                    out.fd.sync()
                }
                if (!tmp.renameTo(f)) {
                    FileOutputStream(f).use { it.write(bytes); it.fd.sync() }
                    tmp.delete()
                }
            } catch (e: Exception) {
                tmp.delete()
                throw e
            }
            // Verify on disk
            if (!f.isFile) {
                return JSONObject().put("ok", false).put("error", "VERIFICATION_FAILED")
                    .put("hint", "file missing after write").toString()
            }
            val actualBytes = f.readBytes()
            val actual = sha256(actualBytes)
            if (actual != expected) {
                return JSONObject().put("ok", false).put("error", "VERIFICATION_FAILED")
                    .put("sha256_expected", expected).put("sha256_actual", actual).toString()
            }
            JSONObject()
                .put("ok", true)
                .put("path", f.absolutePath)
                .put("bytes", actualBytes.size)
                .put("sha256", actual)
                .put("verified", true)
                .toString()
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
            val tmp = File(f.parentFile, ".${f.name}.tmp-${System.nanoTime()}")
            try {
                FileOutputStream(tmp).use { out ->
                    out.write(bytes)
                    out.fd.sync()
                }
                if (!tmp.renameTo(f)) {
                    FileOutputStream(f).use { it.write(bytes) }
                    tmp.delete()
                }
            } catch (e: Exception) {
                tmp.delete()
                throw e
            }
            JSONObject().put("ok", true).put("path", f.absolutePath).put("bytes", bytes.size)
                .put("sha256", sha256(bytes)).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    /**
     * Claude-Code-style apply_patch: sequential search/replace blocks on one file.
     * args JSON: { "path":"...", "edits":[{"old":"...","new":"..."}, ...] }
     * Each old must appear exactly once (or use replace_all:true on an edit).
     */
    @JavascriptInterface
    fun workspaceApplyPatch(path: String, editsJson: String): String {
        return try {
            val f = safeWorkspace(path)
            if (!f.isFile) return JSONObject().put("ok", false).put("error", "not a file").toString()
            var text = f.readText(Charsets.UTF_8)
            val edits = JSONArray(editsJson)
            val applied = JSONArray()
            for (i in 0 until edits.length()) {
                val e = edits.getJSONObject(i)
                val old = e.optString("old", e.optString("find", ""))
                val neu = e.optString("new", e.optString("replace", ""))
                val replaceAll = e.optBoolean("replace_all", false)
                if (old.isEmpty()) {
                    return JSONObject().put("ok", false).put("error", "edit $i: empty old").put("applied", applied).toString()
                }
                val count = old.toRegex(RegexOption.LITERAL).findAll(text).count()
                if (count == 0) {
                    return JSONObject().put("ok", false)
                        .put("error", "edit $i: old text not found")
                        .put("old_preview", old.take(120))
                        .put("applied", applied)
                        .toString()
                }
                if (count > 1 && !replaceAll) {
                    return JSONObject().put("ok", false)
                        .put("error", "edit $i: old text found $count times — set replace_all:true or make old unique")
                        .put("matches", count)
                        .put("applied", applied)
                        .toString()
                }
                text = if (replaceAll) text.replace(old, neu) else text.replaceFirst(old, neu)
                applied.put(JSONObject().put("index", i).put("matches", if (replaceAll) count else 1))
            }
            // atomic write
            val bytes = text.toByteArray(Charsets.UTF_8)
            val tmp = File(f.parentFile, ".${f.name}.tmp-${System.nanoTime()}")
            FileOutputStream(tmp).use { out -> out.write(bytes); out.fd.sync() }
            if (!tmp.renameTo(f)) {
                FileOutputStream(f).use { it.write(bytes) }
                tmp.delete()
            }
            JSONObject()
                .put("ok", true)
                .put("path", f.absolutePath)
                .put("bytes", bytes.size)
                .put("sha256", sha256(bytes))
                .put("edits_applied", applied.length())
                .put("applied", applied)
                .toString()
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



    @JavascriptInterface
    fun deviceUptime(): String {
        return try {
            val ms = android.os.SystemClock.elapsedRealtime()
            JSONObject()
                .put("ok", true)
                .put("elapsedRealtimeMs", ms)
                .put("hours", ms / 3_600_000.0)
                .put("uptimeMillis", android.os.SystemClock.uptimeMillis())
                .toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun deviceStorage(): String {
        return try {
            val path = context.filesDir
            val free = path.usableSpace
            val total = path.totalSpace
            JSONObject()
                .put("ok", true)
                .put("path", path.absolutePath)
                .put("freeBytes", free)
                .put("totalBytes", total)
                .put("usedBytes", total - free)
                .put("freeGb", free / 1e9)
                .put("totalGb", total / 1e9)
                .toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun deviceMemory(): String {
        return try {
            val rt = Runtime.getRuntime()
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            JSONObject()
                .put("ok", true)
                .put("runtimeMax", rt.maxMemory())
                .put("runtimeTotal", rt.totalMemory())
                .put("runtimeFree", rt.freeMemory())
                .put("runtimeUsed", rt.totalMemory() - rt.freeMemory())
                .put("availMem", mi.availMem)
                .put("totalMem", mi.totalMem)
                .put("lowMemory", mi.lowMemory)
                .toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun githubBranchCreate(owner: String, repo: String, branch: String, from: String?): String {
        return try {
            val token = githubToken()
                ?: return JSONObject().put("ok", false).put("error", "no github PAT").toString()
            val fromRef = if (!from.isNullOrBlank()) from.trim() else "heads/main"
            val refPath = if (fromRef.startsWith("heads/") || fromRef.startsWith("tags/")) fromRef else "heads/$fromRef"
            // resolve SHA of source ref
            val refResp = githubRequestSync("GET", "/repos/$owner/$repo/git/ref/$refPath", null, token)
            if (refResp.optInt("status") !in 200..299) {
                // try master
                val alt = githubRequestSync("GET", "/repos/$owner/$repo/git/ref/heads/master", null, token)
                if (alt.optInt("status") !in 200..299) {
                    return JSONObject().put("ok", false).put("error", "source ref not found").put("status", refResp.optInt("status")).toString()
                }
                val sha = alt.optJSONObject("json")?.optJSONObject("object")?.optString("sha")
                    ?: return JSONObject().put("ok", false).put("error", "no sha").toString()
                val body = JSONObject().put("ref", "refs/heads/$branch").put("sha", sha).toString()
                val created = githubRequestSync("POST", "/repos/$owner/$repo/git/refs", body, token)
                return JSONObject().put("ok", created.optInt("status") in 200..299)
                    .put("status", created.optInt("status")).put("json", created.opt("json")).toString()
            }
            val sha = refResp.optJSONObject("json")?.optJSONObject("object")?.optString("sha")
                ?: return JSONObject().put("ok", false).put("error", "no sha").toString()
            val body = JSONObject().put("ref", "refs/heads/$branch").put("sha", sha).toString()
            val created = githubRequestSync("POST", "/repos/$owner/$repo/git/refs", body, token)
            JSONObject().put("ok", created.optInt("status") in 200..299)
                .put("status", created.optInt("status")).put("json", created.opt("json")).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun githubCompare(owner: String, repo: String, base: String, head: String): String {
        return try {
            val token = githubToken()
                ?: return JSONObject().put("ok", false).put("error", "no github PAT").toString()
            val path = "/repos/$owner/$repo/compare/${base.trim()}...${head.trim()}"
            val r = githubRequestSync("GET", path, null, token)
            val status = r.optInt("status")
            val json = r.optJSONObject("json")
            val out = JSONObject().put("ok", status in 200..299).put("status", status)
            if (json != null) {
                out.put("status_text", json.optString("status"))
                    .put("ahead_by", json.optInt("ahead_by"))
                    .put("behind_by", json.optInt("behind_by"))
                    .put("total_commits", json.optInt("total_commits"))
                    .put("html_url", json.optString("html_url"))
                val files = json.optJSONArray("files")
                if (files != null) {
                    val compact = JSONArray()
                    for (i in 0 until minOf(files.length(), 50)) {
                        val f = files.optJSONObject(i) ?: continue
                        compact.put(JSONObject()
                            .put("filename", f.optString("filename"))
                            .put("status", f.optString("status"))
                            .put("additions", f.optInt("additions"))
                            .put("deletions", f.optInt("deletions")))
                    }
                    out.put("files", compact)
                }
            }
            out.toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun fileAppend(path: String, content: String): String {
        return try {
            val f = safeFile(path)
            f.parentFile?.mkdirs()
            java.io.FileWriter(f, true).use { it.write(content) }
            JSONObject().put("ok", true).put("path", f.absolutePath).put("bytes", f.length()).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun workspaceAppend(path: String, content: String): String {
        return try {
            val f = safeWorkspace(path)
            f.parentFile?.mkdirs()
            java.io.FileWriter(f, true).use { it.write(content) }
            JSONObject().put("ok", true).put("path", f.absolutePath).put("bytes", f.length()).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun textReplace(text: String, find: String, replace: String): String {
        return try {
            val out = text.replace(find, replace)
            JSONObject().put("ok", true).put("text", out).put("count", text.split(find).size - 1).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun textLines(text: String): String {
        return try {
            val lines = text.split("\n")
            JSONObject().put("ok", true).put("count", lines.size).put("lines", JSONArray(lines)).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }



    @JavascriptInterface
    fun deviceLocale(): String {
        return try {
            val loc = java.util.Locale.getDefault()
            JSONObject()
                .put("ok", true)
                .put("language", loc.language)
                .put("country", loc.country)
                .put("displayName", loc.displayName)
                .put("toLanguageTag", loc.toLanguageTag())
                .toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun deviceTimezone(): String {
        return try {
            val tz = java.util.TimeZone.getDefault()
            JSONObject()
                .put("ok", true)
                .put("id", tz.id)
                .put("displayName", tz.displayName)
                .put("rawOffsetMs", tz.rawOffset)
                .put("dstSavings", tz.dstSavings)
                .put("inDaylightTime", tz.inDaylightTime(java.util.Date()))
                .toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun deviceSensors(limit: Int): String {
        return try {
            val sm = context.getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager
            val all = sm.getSensorList(android.hardware.Sensor.TYPE_ALL)
            val lim = if (limit <= 0) 40 else limit.coerceIn(1, 80)
            val arr = JSONArray()
            for (s in all.take(lim)) {
                arr.put(
                    JSONObject()
                        .put("name", s.name)
                        .put("type", s.type)
                        .put("vendor", s.vendor)
                        .put("power", s.power.toDouble())
                        .put("resolution", s.resolution.toDouble())
                )
            }
            JSONObject().put("ok", true).put("count", all.size).put("shown", arr.length()).put("sensors", arr).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun netDns(host: String): String {
        return try {
            val addrs = java.net.InetAddress.getAllByName(host.trim())
            val arr = JSONArray()
            for (a in addrs) arr.put(a.hostAddress)
            JSONObject().put("ok", true).put("host", host).put("addresses", arr).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun timeSleep(ms: Int): String {
        return try {
            val wait = ms.coerceIn(0, 10_000)
            Thread.sleep(wait.toLong())
            JSONObject().put("ok", true).put("sleptMs", wait).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun textSnippet(text: String, lines: Int): String {
        return try {
            val n = if (lines <= 0) 20 else lines.coerceIn(1, 500)
            val parts = text.split("\n")
            val take = parts.take(n)
            JSONObject()
                .put("ok", true)
                .put("totalLines", parts.size)
                .put("shown", take.size)
                .put("text", take.joinToString("\n"))
                .toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun githubRepo(owner: String, repo: String): String {
        return try {
            val token = githubToken()
                ?: return JSONObject().put("ok", false).put("error", "no github PAT").toString()
            val r = githubRequestSync("GET", "/repos/$owner/$repo", null, token)
            val status = r.optInt("status")
            val json = r.optJSONObject("json")
            val out = JSONObject().put("ok", status in 200..299).put("status", status)
            if (json != null) {
                out.put("full_name", json.optString("full_name"))
                    .put("default_branch", json.optString("default_branch"))
                    .put("stars", json.optInt("stargazers_count"))
                    .put("forks", json.optInt("forks_count"))
                    .put("open_issues", json.optInt("open_issues_count"))
                    .put("language", json.optString("language"))
                    .put("private", json.optBoolean("private"))
                    .put("html_url", json.optString("html_url"))
            }
            out.toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun githubIssueCreate(owner: String, repo: String, title: String, body: String?, labels: String?): String {
        return try {
            val token = githubToken() ?: return JSONObject().put("ok", false).put("error", "no github token — keys.set('github', pat)").toString()
            val payload = JSONObject().put("title", title).put("body", body ?: "")
            if (!labels.isNullOrBlank()) {
                val arr = JSONArray()
                labels.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { arr.put(it) }
                payload.put("labels", arr)
            }
            val res = githubRequestSync("POST", "/repos/$owner/$repo/issues", payload.toString(), token)
            val data = res.optJSONObject("json")
            if (!res.optBoolean("ok") || data == null) {
                return JSONObject().put("ok", false).put("status", res.optInt("status"))
                    .put("error", data?.optString("message") ?: res.optString("text").take(300)).toString()
            }
            JSONObject().put("ok", true)
                .put("number", data.optInt("number"))
                .put("html_url", data.optString("html_url"))
                .put("title", data.optString("title"))
                .toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun githubCommits(owner: String, repo: String, sha: String?, perPage: Int): String {
        return try {
            val token = githubToken() ?: return JSONObject().put("ok", false).put("error", "no github token").toString()
            val n = if (perPage <= 0) 15 else perPage.coerceAtMost(50)
            var path = "/repos/$owner/$repo/commits?per_page=$n"
            if (!sha.isNullOrBlank()) path += "&sha=${java.net.URLEncoder.encode(sha, "UTF-8")}"
            val res = githubRequestSync("GET", path, null, token)
            if (!res.optBoolean("ok")) {
                return JSONObject().put("ok", false).put("status", res.optInt("status"))
                    .put("error", res.optString("text").take(300)).toString()
            }
            val arr = res.optJSONArray("json") ?: JSONArray()
            val out = JSONArray()
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                val commit = c.optJSONObject("commit")
                out.put(JSONObject()
                    .put("sha", c.optString("sha").take(8))
                    .put("message", commit?.optString("message")?.lineSequence()?.firstOrNull() ?: "")
                    .put("author", commit?.optJSONObject("author")?.optString("name") ?: "")
                    .put("date", commit?.optJSONObject("author")?.optString("date") ?: "")
                    .put("html_url", c.optString("html_url")))
            }
            JSONObject().put("ok", true).put("commits", out).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }



    @JavascriptInterface
    fun agentBegin(tool: String?, description: String?) {
        try {
            AgentService.status(context, tool.orEmpty(), description.orEmpty())
        } catch (_: Exception) { }
    }

    @JavascriptInterface
    fun agentEnd() {
        try {
            AgentService.idle(context)
        } catch (_: Exception) { }
    }

    @JavascriptInterface
    fun agentStop() {
        try {
            AgentService.stop(context)
        } catch (_: Exception) { }
    }



    private var wakeLock: PowerManager.WakeLock? = null

    private fun envelope(ok: Boolean, data: Any? = null, error: String? = null, ms: Long = 0, tool: String? = null, requestId: String? = null): String {
        val o = JSONObject().put("ok", ok)
        if (data != null) {
            when (data) {
                is JSONObject -> o.put("data", data)
                is JSONArray -> o.put("data", data)
                is String -> o.put("data", data)
                is Number -> o.put("data", data)
                is Boolean -> o.put("data", data)
                else -> o.put("data", data.toString())
            }
        }
        if (error != null) {
            o.put("error", if (error.startsWith("{")) try { JSONObject(error) } catch (_: Exception) { JSONObject().put("message", error) } else JSONObject().put("message", error))
        }
        val meta = JSONObject().put("ms", ms)
        if (!tool.isNullOrBlank()) meta.put("tool", tool)
        if (!requestId.isNullOrBlank()) meta.put("requestId", requestId)
        o.put("meta", meta)
        if (!requestId.isNullOrBlank()) o.put("requestId", requestId)
        if (!tool.isNullOrBlank()) o.put("tool", tool)
        return o.toString()
    }

    /**
     * Universal JSON dispatcher — all tools receive one JSONObject args.
     * Fixes Root Cause A (positional/[object Object] arg bugs).
     */
    @JavascriptInterface
    fun invokeJson(toolName: String, argsJson: String?, requestId: String?): String {
        val t0 = System.currentTimeMillis()
        val tool = toolName.trim()
        val rid = requestId?.takeIf { it.isNotBlank() } ?: ("r-" + System.currentTimeMillis())
        val args = try {
            if (argsJson.isNullOrBlank() || argsJson == "null") JSONObject()
            else JSONObject(argsJson)
        } catch (e: Exception) {
            return envelope(false, error = """{"code":"ARGS_INVALID","message":"args must be JSON object: ${e.message}","retryable":false}""", ms = 0, tool = tool, requestId = rid)
        }
        return try {
            val raw = dispatchByName(tool, args)
            // Attach requestId/tool to whatever the handler returned
            val out = try { JSONObject(raw) } catch (_: Exception) {
                JSONObject().put("ok", true).put("data", raw)
            }
            if (!out.has("requestId")) out.put("requestId", rid)
            if (!out.has("tool")) out.put("tool", tool)
            val meta = out.optJSONObject("meta") ?: JSONObject()
            meta.put("ms", System.currentTimeMillis() - t0)
            meta.put("tool", tool)
            meta.put("requestId", rid)
            out.put("meta", meta)
            out.toString()
        } catch (e: Exception) {
            envelope(false,
                error = """{"code":"TOOL_BROKEN","message":${JSONObject.quote(e.message ?: "error")},"retryable":true,"hint":"retry or check args"}""",
                ms = System.currentTimeMillis() - t0, tool = tool, requestId = rid)
        }
    }

    private fun dispatchByName(tool: String, args: JSONObject): String {
        fun s(key: String, default: String = "") = args.optString(key, default)
        fun sAny(vararg keys: String): String {
            for (k in keys) {
                if (args.has(k) && !args.isNull(k)) {
                    val v = args.opt(k)
                    return when (v) {
                        is String -> v
                        is Number, is Boolean -> v.toString()
                        is JSONObject, is JSONArray -> v.toString()
                        else -> v?.toString() ?: ""
                    }
                }
            }
            return ""
        }
        fun i(key: String, default: Int = 0) = args.optInt(key, default)
        fun l(key: String, default: Long = 0L) = args.optLong(key, default)
        fun d(key: String, default: Double = 0.0) = args.optDouble(key, default)
        fun b(key: String, default: Boolean = false) = args.optBoolean(key, default)

        return when (tool) {
            "list_tools" -> listTools()
            "describe" -> describeTool(sAny("name", "tool"))
            "selftest" -> selftest()
            "time.now" -> timeNow()
            "time.format" -> timeFormat(l("ms"), s("pattern").ifBlank { null })
            "time.sleep" -> timeSleep(i("ms", 100).coerceIn(0, 10000))
            "uuid.v4", "uuid" -> uuidV4()
            "device.info" -> deviceInfo()
            "device.battery", "battery" -> battery()
            "device.network", "network" -> network()
            "clipboard.read" -> clipboardRead()
            "clipboard.write", "clipboard.copy" -> {
                val text = sAny("text", "value", "content")
                if (text.isEmpty() && args.length() > 0 && !args.has("text")) {
                    return JSONObject().put("ok", false).put("error", "ARGS_NOT_UNWRAPPED")
                        .put("hint", "use args.text string").toString()
                }
                clipboardWrite(text)
            }
            "workspace.pwd" -> workspacePwd()
            "workspace.ls" -> workspaceLs(sAny("path").ifBlank { null })
            "workspace.read" -> workspaceReadRange(sAny("path"), i("maxBytes", 0), i("offset", 0))
            "workspace.write" -> workspaceWrite(sAny("path"), sAny("content", "text", "data"))
            "workspace.write_b64" -> workspaceWriteB64(sAny("path"), sAny("contentB64", "content", "data"))
            "workspace.read_b64" -> workspaceReadB64(sAny("path"))
            "workspace.mkdir" -> workspaceMkdir(sAny("path"))
            "workspace.rm" -> workspaceRm(sAny("path"))
            "workspace.stat" -> workspaceStat(sAny("path"))
            "workspace.tree" -> workspaceTree(sAny("path").ifBlank { null }, i("depth", 2))
            "workspace.grep" -> workspaceGrep(sAny("query", "pattern"), b("regex"), i("maxHits", 50))
            "workspace.replace" -> workspaceReplace(sAny("path"), sAny("find", "old"), sAny("replace", "new"), b("regex"))
            "workspace.apply_patch" -> {
                val edits = args.opt("edits")
                val ej = when (edits) {
                    is JSONArray -> edits.toString()
                    is String -> edits
                    else -> "[]"
                }
                workspaceApplyPatch(sAny("path"), ej)
            }
            "workspace.head" -> workspaceHead(sAny("path"), i("lines", 40))
            "workspace.tail" -> workspaceTail(sAny("path"), i("lines", 40))
            "workspace.glob" -> workspaceGlob(sAny("pattern", "glob"), sAny("path").ifBlank { null }, i("max", 200))
            "workspace.append" -> workspaceAppend(sAny("path"), sAny("content", "text"))
            "net.ping" -> netPing(sAny("host", "hostname", "target"), i("timeoutMs", 3000))
            "net.dns" -> netDns(sAny("host", "hostname", "name"))
            "net.port" -> netPort(sAny("host", "hostname"), i("port", 80), i("timeoutMs", 3000))
            "crypto.hash" -> cryptoHash(sAny("algo", "algorithm", "hash"), sAny("data", "text", "input"), sAny("encoding").ifBlank { null })
            "crypto.hmac" -> cryptoHmac(sAny("key"), sAny("data", "text"))
            "calc.eval" -> calcEval(sAny("expr", "expression", "code"))
            "calc.round" -> calcRound(d("value"), i("digits", 2))
            "calc.clamp" -> calcClamp(d("value"), d("min"), d("max"))
            "calc.convert" -> calcConvert(d("value"), sAny("from"), sAny("to"))
            "calc.haversine" -> calcHaversine(d("lat1"), d("lon1"), d("lat2"), d("lon2"))
            "color.hex_rgb" -> colorHexRgb(sAny("op", "action").ifBlank { "to_rgb" }, sAny("value", "hex", "color"))
            "json.pretty" -> jsonPretty(sAny("json", "text", "data"), i("indent", 2))
            "json.parse" -> jsonParse(sAny("json", "text", "data"))
            "json.query" -> jsonQuery(sAny("json", "text"), sAny("path", "query"))
            "text.stats" -> textStats(sAny("text", "content"))
            "text.trim" -> textTrim(sAny("text", "content"))
            "text.split" -> textSplit(sAny("text"), sAny("sep", "separator", "delimiter"), i("limit", 0))
            "text.join" -> textJoin(args.opt("parts")?.toString() ?: "[]", sAny("sep", "separator"))
            "text.base64" -> textBase64(sAny("op").ifBlank { "encode" }, sAny("data", "text"))
            "text.regex", "text.regex_find" -> textRegex(sAny("op").ifBlank { "find" }, sAny("pattern", "regex"), sAny("text"), sAny("replace", "replacement").ifBlank { null })
            "text.case" -> textCase(sAny("op").ifBlank { "upper" }, sAny("text"))
            "text.lines" -> textLines(sAny("text"))
            "text.replace" -> textReplace(sAny("text"), sAny("find"), sAny("replace"))
            "text.snippet" -> textSnippet(sAny("text"), i("lines", 5))
            "text.url" -> textUrl(sAny("op").ifBlank { "encode" }, sAny("data", "text", "url"))
            "text.word_count" -> textWordCount(sAny("text"))
            "random.bytes" -> randomBytes(i("n", 16).coerceIn(1, 4096))
            "http_request", "fetch_url" -> {
                // async tools still need callback path — return guidance
                JSONObject().put("ok", false).put("error", "use http_request via native async bridge")
                    .put("hint", "call http_request with url/method from shim async path").toString()
            }
            "github.me" -> {
                // sync wrapper not available — mark
                JSONObject().put("ok", false).put("error", "use github.me via async bridge").toString()
            }
            "toast" -> run { toast(sAny("message", "text")); JSONObject().put("ok", true).toString() }
            "vibrate" -> run { vibrate(i("ms", 50)); JSONObject().put("ok", true).toString() }
            "notify" -> notify(sAny("title"), sAny("body", "text", "message"))
            "exec.langs" -> execLangs()
            "exec.which" -> execWhich(sAny("bin", "name", "cmd"))
            "exec" -> exec(args.opt("argv")?.toString() ?: "[]", i("timeout_ms", i("timeoutMs", 15000)), sAny("cwd").ifBlank { null })
            "memory.get" -> memoryGet(sAny("key", "name"))
            "memory.set" -> memorySet(sAny("key", "name"), sAny("value"))
            "memory.delete" -> memoryDelete(sAny("key", "name"))
            "memory.list" -> memoryList()
            "memory.clear" -> memoryClear()
            "keys.get" -> {
                // mask by default
                val name = sAny("name", "key")
                val full = keysGet(name)
                try {
                    val o = JSONObject(full)
                    val v = o.optString("value", "")
                    if (v.length > 8) {
                        o.put("value", v.take(3) + "…" + v.takeLast(3))
                        o.put("masked", true)
                        o.put("exists", v.isNotEmpty())
                    }
                    o.toString()
                } catch (_: Exception) { full }
            }
            "keys.set" -> keysSet(sAny("name", "key"), sAny("value"))
            "keys.delete" -> keysDelete(sAny("name", "key"))
            "keys.list" -> keysList()
            "diff.lines" -> diffLines(sAny("a", "textA", "left"), sAny("b", "textB", "right"))
            "diff.file" -> diffFile(sAny("pathA", "a"), sAny("pathB", "b"))
            "code.count_lines" -> codeCountLines(sAny("path").ifBlank { null }, sAny("content").ifBlank { null })
            "code.imports" -> codeImports(sAny("path").ifBlank { null }, sAny("content").ifBlank { null })
            "code.detect_lang" -> codeDetectLang(sAny("path").ifBlank { null }, sAny("content").ifBlank { null })
            "code.slice" -> codeSlice(sAny("path"), i("start", 1), i("end", 50))
            "code.find_todos" -> codeFindTodos(sAny("path").ifBlank { "." }, i("maxHits", 40))
            "code.search" -> codeSearch(sAny("query", "pattern"), sAny("path").ifBlank { "." }, sAny("ext").ifBlank { null }, i("maxHits", 40))
            "env.get" -> envGet()
            "app.info" -> appInfo()

            "fs.write" -> fsWrite(sAny("path"), sAny("content", "text"))
            "fs.read" -> fsRead(sAny("path"))
            "fs.delete" -> fsDelete(sAny("path"))
            "fs.list" -> fsList(sAny("prefix", "path"))
            "task.add" -> taskAdd(sAny("content", "text", "title"))
            "task.update" -> taskUpdate(sAny("id"), sAny("status"))
            "task.list" -> taskList()
            "task.clear" -> taskClear()
            "history.list" -> historyList(sAny("path"))
            "history.revert" -> historyRevert(sAny("path"), sAny("version"))
            "session.save" -> sessionSave(sAny("name", "path"), sAny("state").ifBlank { null })
            "session.load" -> sessionLoad(sAny("name", "path"))
            "session.list" -> sessionList()
            "policy.allow" -> policyAllow(sAny("pattern", "tool"))
            "policy.deny" -> policyDeny(sAny("pattern", "tool"))
            "policy.check" -> policyCheck(sAny("tool", "name"))
            "dispatch.log" -> dispatchLog()
            "dispatch.errors" -> dispatchErrors()
            "intent.open_url" -> intentOpenUrl(sAny("url", "uri", "link"))
            "index.build" -> indexBuild(sAny("path").ifBlank { null })
            "index.find" -> indexFind(sAny("query", "q", "name"))
            "toybox.list" -> toyboxList()

            else -> JSONObject().put("ok", false)
                .put("error", JSONObject()
                    .put("code", "TOOL_NOT_FOUND")
                    .put("message", "unknown tool: $tool")
                    .put("retryable", false)
                    .put("hint", "call list_tools"))
                .put("tool", tool).toString()
        }
    }

    @JavascriptInterface
    fun sensorsList(): String {
        return try {
            val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            val arr = JSONArray()
            for (s in sm.getSensorList(Sensor.TYPE_ALL)) {
                arr.put(
                    JSONObject()
                        .put("name", s.name)
                        .put("type", s.stringType ?: s.type.toString())
                        .put("vendor", s.vendor)
                        .put("maxRange", s.maximumRange.toDouble())
                )
            }
            envelope(true, JSONObject().put("sensors", arr).put("count", arr.length()))
        } catch (e: Exception) {
            envelope(false, error = e.message)
        }
    }

    @JavascriptInterface
    fun sensorsRead(type: String): String {
        return try {
            val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            val sensor = resolveSensor(sm, type)
                ?: return envelope(false, error = "sensor not found: $type")
            val box = arrayOfNulls<FloatArray>(1)
            val lock = Object()
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    synchronized(lock) {
                        box[0] = event.values.clone()
                        lock.notifyAll()
                    }
                }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            }
            sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_FASTEST)
            val t0 = System.currentTimeMillis()
            synchronized(lock) {
                if (box[0] == null) lock.wait(1500)
            }
            sm.unregisterListener(listener)
            val vals = box[0] ?: return envelope(false, error = "no reading", ms = System.currentTimeMillis() - t0)
            val arr = JSONArray()
            vals.forEach { arr.put(it.toDouble()) }
            envelope(
                true,
                JSONObject()
                    .put("type", sensor.stringType ?: type)
                    .put("name", sensor.name)
                    .put("values", arr),
                ms = System.currentTimeMillis() - t0
            )
        } catch (e: Exception) {
            envelope(false, error = e.message)
        }
    }

    private fun resolveSensor(sm: SensorManager, type: String): Sensor? {
        val t = type.lowercase()
        val byName = sm.getSensorList(Sensor.TYPE_ALL).firstOrNull {
            (it.stringType ?: "").contains(t, true) || it.name.contains(t, true)
        }
        if (byName != null) return byName
        val typeInt = when {
            t.contains("accel") -> Sensor.TYPE_ACCELEROMETER
            t.contains("gyro") -> Sensor.TYPE_GYROSCOPE
            t.contains("light") -> Sensor.TYPE_LIGHT
            t.contains("proxim") -> Sensor.TYPE_PROXIMITY
            t.contains("step") -> Sensor.TYPE_STEP_COUNTER
            t.contains("magnet") -> Sensor.TYPE_MAGNETIC_FIELD
            t.contains("gravity") -> Sensor.TYPE_GRAVITY
            t.contains("rotat") -> Sensor.TYPE_ROTATION_VECTOR
            else -> return null
        }
        return sm.getDefaultSensor(typeInt)
    }

    @JavascriptInterface
    fun torchSet(on: Boolean): String {
        return try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull { cid ->
                try {
                    val chars = cm.getCameraCharacteristics(cid)
                    val flash = chars.get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE)
                    flash == true
                } catch (_: Exception) { false }
            } ?: return envelope(false, error = "no flash camera")
            cm.setTorchMode(id, on)
            envelope(true, JSONObject().put("on", on).put("cameraId", id))
        } catch (e: Exception) {
            envelope(false, error = e.message)
        }
    }

    @JavascriptInterface
    fun audioVolume(stream: String?, level: Int): String {
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val st = when (stream?.lowercase()) {
                "ring", "ringer" -> AudioManager.STREAM_RING
                "alarm" -> AudioManager.STREAM_ALARM
                "voice", "call" -> AudioManager.STREAM_VOICE_CALL
                else -> AudioManager.STREAM_MUSIC
            }
            val max = am.getStreamMaxVolume(st)
            if (level >= 0) {
                am.setStreamVolume(st, level.coerceIn(0, max), 0)
            }
            envelope(
                true,
                JSONObject()
                    .put("stream", stream ?: "music")
                    .put("level", am.getStreamVolume(st))
                    .put("max", max)
            )
        } catch (e: Exception) {
            envelope(false, error = e.message)
        }
    }

    @JavascriptInterface
    fun audioRinger(mode: String?): String {
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (!mode.isNullOrBlank()) {
                val m = when (mode.lowercase()) {
                    "silent" -> AudioManager.RINGER_MODE_SILENT
                    "vibrate" -> AudioManager.RINGER_MODE_VIBRATE
                    else -> AudioManager.RINGER_MODE_NORMAL
                }
                am.ringerMode = m
            }
            val name = when (am.ringerMode) {
                AudioManager.RINGER_MODE_SILENT -> "silent"
                AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
                else -> "normal"
            }
            envelope(true, JSONObject().put("mode", name))
        } catch (e: Exception) {
            envelope(false, error = e.message)
        }
    }

    @JavascriptInterface
    fun wakelockAcquire(ms: Int): String {
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (wakeLock?.isHeld != true) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dharness:tool").apply {
                    setReferenceCounted(false)
                }
            }
            val hold = ms.coerceIn(1000, 600_000).toLong()
            wakeLock?.acquire(hold)
            envelope(true, JSONObject().put("held", true).put("ms", hold))
        } catch (e: Exception) {
            envelope(false, error = e.message)
        }
    }

    @JavascriptInterface
    fun wakelockRelease(): String {
        return try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
            envelope(true, JSONObject().put("held", false))
        } catch (e: Exception) {
            envelope(false, error = e.message)
        }
    }

    @JavascriptInterface
    fun diffLines(a: String, b: String): String {
        return try {
            val la = a.split('\n')
            val lb = b.split('\n')
            val max = maxOf(la.size, lb.size)
            val changes = JSONArray()
            var i = 0
            while (i < max) {
                val sa = la.getOrNull(i)
                val sb = lb.getOrNull(i)
                if (sa != sb) {
                    changes.put(
                        JSONObject()
                            .put("line", i + 1)
                            .put("a", sa ?: JSONObject.NULL)
                            .put("b", sb ?: JSONObject.NULL)
                    )
                }
                i++
                if (changes.length() >= 500) break
            }
            envelope(
                true,
                JSONObject()
                    .put("linesA", la.size)
                    .put("linesB", lb.size)
                    .put("changed", changes.length())
                    .put("changes", changes)
            )
        } catch (e: Exception) {
            envelope(false, error = e.message)
        }
    }

    @JavascriptInterface
    fun toyboxList(): String {
        return try {
            val pb = ProcessBuilder("toybox", "--help")
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val out = proc.inputStream.bufferedReader().readText()
            proc.waitFor(3, TimeUnit.SECONDS)
            // Prefer "Currently defined functions:" section or applet-only lines
            val applets = linkedSetOf<String>()
            var inList = false
            for (line in out.lineSequence()) {
                val l = line.trim()
                if (l.contains("defined functions", ignoreCase = true) || l.contains("commands:", ignoreCase = true)) {
                    inList = true
                    continue
                }
                if (inList) {
                    // lines of space-separated applet names
                    for (tok in l.split(Regex("\\s+"))) {
                        val a = tok.trim().trimEnd(',')
                        if (a.matches(Regex("[a-z][a-z0-9_.+-]*")) && a.length <= 24) applets.add(a)
                    }
                }
            }
            if (applets.isEmpty()) {
                // fallback: toybox with no args sometimes prints applets
                val pb2 = ProcessBuilder("toybox")
                pb2.redirectErrorStream(true)
                val p2 = pb2.start()
                val o2 = p2.inputStream.bufferedReader().readText()
                p2.waitFor(2, TimeUnit.SECONDS)
                for (tok in o2.split(Regex("\\s+"))) {
                    val a = tok.trim()
                    if (a.matches(Regex("[a-z][a-z0-9_.+-]*")) && a.length in 2..20
                        && a !in setOf("the", "and", "for", "with", "from", "this", "that", "available", "commands", "arguments", "argument")) {
                        applets.add(a)
                    }
                }
            }
            // Filter English help words
            val stop = setOf("accept","additional","also","any","argument","arguments","available","commands",
                "day","decimal","each","usage","options","help","version","currently","defined","functions")
            val cleaned = applets.filter { it !in stop && it.length >= 2 }.sorted()
            JSONObject().put("ok", true).put("count", cleaned.size).put("applets", JSONArray(cleaned)).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun toyboxRun(applet: String, argsJson: String?): String {
        return try {
            val args = JSONArray().put("/system/bin/toybox").put(applet)
            if (!argsJson.isNullOrBlank()) {
                val extra = JSONArray(argsJson)
                for (i in 0 until extra.length()) args.put(extra.getString(i))
            }
            // drop the path prefix for allowlist — use toybox as bin
            val argv = JSONArray().put("toybox").put(applet)
            if (!argsJson.isNullOrBlank()) {
                val extra = JSONArray(argsJson)
                for (i in 0 until extra.length()) argv.put(extra.getString(i))
            }
            exec(argv.toString(), 30_000, null)
        } catch (e: Exception) {
            envelope(false, error = e.message)
        }
    }

    @JavascriptInterface
    fun execWithStdin(argvJson: String, stdin: String?, timeoutMs: Int, cwdRel: String?): String {
        return try {
            val arr = JSONArray(argvJson)
            if (arr.length() == 0) return envelope(false, error = "empty argv")
            var argv = MutableList(arr.length()) { arr.getString(it) }
            val bin = argv[0].substringAfterLast('/')
            if (bin == "sh" || bin == "mksh" || bin == "bash") {
                val shellPath = listOf("/system/bin/sh", "/system/bin/mksh")
                    .firstOrNull { File(it).canExecute() }
                    ?: return envelope(false, error = "no shell")
                argv = (listOf(shellPath) + argv.drop(1)).toMutableList()
            } else if (bin !in execAllow) {
                return envelope(false, error = "not allowlisted: $bin")
            }
            val cwd = workspaceRoot
            val t0 = System.currentTimeMillis()
            val pb = ProcessBuilder(argv).directory(cwd).redirectErrorStream(false)
            val proc = pb.start()
            if (!stdin.isNullOrEmpty()) {
                proc.outputStream.use { it.write(stdin.toByteArray(Charsets.UTF_8)); it.flush() }
            } else {
                proc.outputStream.close()
            }
            val stdoutBox = arrayOfNulls<String>(1)
            val stderrBox = arrayOfNulls<String>(1)
            val outT = Thread { stdoutBox[0] = proc.inputStream.bufferedReader().readText().take(80_000) }.also { it.start() }
            val errT = Thread { stderrBox[0] = proc.errorStream.bufferedReader().readText().take(40_000) }.also { it.start() }
            val finished = proc.waitFor(timeoutMs.coerceIn(500, 120_000).toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!finished) {
                proc.destroy()
                outT.join(300); errT.join(300)
                return envelope(false, error = "timeout", ms = System.currentTimeMillis() - t0)
            }
            outT.join(2000); errT.join(2000)
            val code = proc.exitValue()
            envelope(
                code == 0,
                JSONObject()
                    .put("exitCode", code)
                    .put("stdout", stdoutBox[0] ?: "")
                    .put("stderr", stderrBox[0] ?: ""),
                ms = System.currentTimeMillis() - t0
            )
        } catch (e: Exception) {
            envelope(false, error = e.message)
        }
    }


    @JavascriptInterface
    fun selftest(): String {
        val t0 = System.currentTimeMillis()
        return try {
            val methods = boundNativeMethods().sorted()
            val probes = JSONObject()
            fun probe(name: String, block: () -> Unit) {
                try {
                    block()
                    probes.put(name, JSONObject().put("ok", true))
                } catch (e: Exception) {
                    probes.put(name, JSONObject().put("ok", false).put("error", e.message))
                }
            }
            probe("listTools") { listTools() }
            probe("describeTool") { describeTool("workspace") }
            probe("clipboardWrite") { clipboardWrite("dharness-selftest") }
            probe("clipboardRead") { clipboardRead() }
            probe("workspacePwd") { workspacePwd() }
            probe("deviceInfo") { deviceInfo() }
            probe("sensorsList") { sensorsList() }
            probe("execAllow") { exec(JSONArray().put("echo").put("ok").toString(), 5000, null) }
            val ver = try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            } catch (_: Exception) { "?" }
            envelope(
                true,
                JSONObject()
                    .put("version", ver)
                    .put("methodCount", methods.size)
                    .put("methods", JSONArray(methods))
                    .put("probes", probes)
                    .put("primaryApi", "DHarness")
                    .put("hint", "Prefer DHarness.methodName(...). Object.keys not available on interface; use selftest().methods"),
                ms = System.currentTimeMillis() - t0
            )
        } catch (e: Exception) {
            envelope(false, error = e.message, ms = System.currentTimeMillis() - t0)
        }
    }

    @JavascriptInterface
    fun help(name: String?): String {
        val n = name?.trim().orEmpty()
        if (n.isEmpty()) {
            return envelope(
                true,
                JSONObject()
                    .put("primaryApi", "DHarness")
                    .put("usage", "DHarness.help('workspaceRead') or DHarness.help('exec')")
                    .put("discover", JSONArray()
                        .put("DHarness.selftest()")
                        .put("DHarness.listTools()")
                        .put("DHarness.capabilities()")
                    )
                    .put("conventions", JSONArray()
                        .put("DHarness.method(args) — primary")
                        .put("run_js globals: workspace.ls()")
                        .put("flat: {tool,args}")
                        .put("group: {tool:workspace,args:{op:ls}}")
                    )
            )
        }
        val desc = describeTool(n)
        val o = try { JSONObject(desc) } catch (_: Exception) { JSONObject().put("raw", desc) }
        o.put("ok", !o.has("error") || o.optBoolean("bound", false))
        // examples
        val examples = JSONArray()
        when {
            n.contains("workspace", true) || n.contains("ls", true) ->
                examples.put("DHarness.workspaceLs(null)").put("return await workspace.ls()")
            n.contains("exec", true) ->
                examples.put("DHarness.exec(JSON.stringify(['ls','-la']), 15000, null)")
                    .put("return await exec({argv:['toybox','ls']})")
            n.contains("http", true) || n.contains("fetch", true) ->
                examples.put("return await http_request({url:'https://httpbin.org/get'})")
            n.contains("sensor", true) ->
                examples.put("DHarness.sensorsRead('light')").put("return await sensors.read('accelerometer')")
            n.contains("geo", true) || n.contains("location", true) ->
                examples.put("DHarness.geoGet(8000)")
            n.contains("clipboard", true) ->
                examples.put("DHarness.clipboardWrite('hi')").put("DHarness.clipboardRead()")
            else -> examples.put("DHarness.help('')")
        }
        o.put("examples", examples)
        return o.toString()
    }

    @JavascriptInterface
    fun capabilities(): String {
        return try {
            val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            val sensors = sm.getSensorList(Sensor.TYPE_ALL).map { it.stringType ?: it.name }
            var torch = false
            try {
                val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                torch = cm.cameraIdList.any { id ->
                    try {
                        cm.getCameraCharacteristics(id)
                            .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                    } catch (_: Exception) { false }
                }
            } catch (_: Exception) { }
            val stat = android.os.StatFs(workspaceRoot.absolutePath)
            val free = stat.availableBlocksLong * stat.blockSizeLong
            val total = stat.blockCountLong * stat.blockSizeLong
            val net = try { JSONObject(network()) } catch (_: Exception) { JSONObject() }
            envelope(
                true,
                JSONObject()
                    .put("sensors", JSONArray(sensors.take(40)))
                    .put("sensorCount", sensors.size)
                    .put("torch", torch)
                    .put("execAllow", JSONArray(execAllow.toList().sorted()))
                    .put("storage", JSONObject()
                        .put("workspace", workspaceRoot.absolutePath)
                        .put("freeBytes", free)
                        .put("totalBytes", total)
                    )
                    .put("network", net)
                    .put("methodCount", boundNativeMethods().size)
                    .put("geoPermission",
                        ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) ==
                            android.content.pm.PackageManager.PERMISSION_GRANTED ||
                        ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
                            android.content.pm.PackageManager.PERMISSION_GRANTED
                    )
            )
        } catch (e: Exception) {
            envelope(false, error = e.message)
        }
    }

    /** Native location via LocationManager (not WebView geolocation). */
    @JavascriptInterface
    fun geoGet(timeoutMs: Int): String {
        val t0 = System.currentTimeMillis()
        return try {
            val fine = ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION)
            val coarse = ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION)
            if (fine != android.content.pm.PackageManager.PERMISSION_GRANTED &&
                coarse != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                return envelope(false, error = "location permission not granted", ms = System.currentTimeMillis() - t0)
            }
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val providers = listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
            )
            var best: Location? = null
            for (p in providers) {
                try {
                    if (!lm.isProviderEnabled(p)) continue
                    val loc = lm.getLastKnownLocation(p) ?: continue
                    if (best == null || loc.accuracy < best!!.accuracy) best = loc
                } catch (_: SecurityException) { }
            }
            if (best != null && (System.currentTimeMillis() - best!!.time) < 120_000) {
                return envelope(
                    true,
                    JSONObject()
                        .put("lat", best!!.latitude)
                        .put("lng", best!!.longitude)
                        .put("accuracy", best!!.accuracy.toDouble())
                        .put("provider", best!!.provider ?: "")
                        .put("time", best!!.time)
                        .put("source", "lastKnown"),
                    ms = System.currentTimeMillis() - t0
                )
            }
            // Live fix with timeout
            val latch = CountDownLatch(1)
            val box = arrayOfNulls<Location>(1)
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    box[0] = location
                    latch.countDown()
                }
                @Deprecated("Deprecated in API")
                override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }
            try {
                val use = when {
                    lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                    lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                    else -> null
                }
                if (use == null) {
                    if (best != null) {
                        return envelope(
                            true,
                            JSONObject()
                                .put("lat", best!!.latitude)
                                .put("lng", best!!.longitude)
                                .put("accuracy", best!!.accuracy.toDouble())
                                .put("provider", best!!.provider ?: "")
                                .put("time", best!!.time)
                                .put("source", "lastKnown_stale"),
                            ms = System.currentTimeMillis() - t0
                        )
                    }
                    return envelope(false, error = "no location provider enabled", ms = System.currentTimeMillis() - t0)
                }
                lm.requestLocationUpdates(use, 0L, 0f, listener, Looper.getMainLooper())
                val wait = timeoutMs.coerceIn(2000, 30_000).toLong()
                latch.await(wait, java.util.concurrent.TimeUnit.MILLISECONDS)
            } finally {
                try { lm.removeUpdates(listener) } catch (_: Exception) { }
            }
            val loc = box[0] ?: best
            if (loc == null) {
                return envelope(false, error = "location unavailable", ms = System.currentTimeMillis() - t0)
            }
            envelope(
                true,
                JSONObject()
                    .put("lat", loc.latitude)
                    .put("lng", loc.longitude)
                    .put("accuracy", loc.accuracy.toDouble())
                    .put("provider", loc.provider ?: "")
                    .put("time", loc.time)
                    .put("source", if (box[0] != null) "live" else "lastKnown"),
                ms = System.currentTimeMillis() - t0
            )
        } catch (e: Exception) {
            envelope(false, error = e.message, ms = System.currentTimeMillis() - t0)
        }
    }

    /** Blink torch with pattern of on/off durations (ms). Each state clamped ≥200ms. */
    @JavascriptInterface
    fun torchBlink(patternJson: String?, cycles: Int): String {
        return try {
            val arr = if (patternJson.isNullOrBlank()) JSONArray().put(200).put(200)
            else JSONArray(patternJson)
            if (arr.length() == 0) return envelope(false, error = "empty pattern")
            val times = MutableList(arr.length()) { i ->
                arr.getLong(i).coerceAtLeast(200L).coerceAtMost(5000L)
            }
            val n = cycles.coerceIn(1, 20)
            Thread {
                try {
                    repeat(n) {
                        var on = true
                        for (ms in times) {
                            torchSet(on)
                            Thread.sleep(ms)
                            on = !on
                        }
                    }
                    torchSet(false)
                } catch (_: Exception) {
                    try { torchSet(false) } catch (_: Exception) { }
                }
            }.start()
            envelope(true, JSONObject().put("started", true).put("cycles", n).put("pattern", JSONArray(times)))
        } catch (e: Exception) {
            envelope(false, error = e.message)
        }
    }

    /** Collect sensor samples for durationMs (max 5000). */
    @JavascriptInterface
    fun sensorsWatch(type: String, durationMs: Int): String {
        val t0 = System.currentTimeMillis()
        return try {
            val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            val sensor = resolveSensor(sm, type)
                ?: return envelope(false, error = "sensor not found: $type")
            val samples = JSONArray()
            val lock = Object()
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    synchronized(lock) {
                        if (samples.length() >= 100) return
                        val vals = JSONArray()
                        event.values.forEach { vals.put(it.toDouble()) }
                        samples.put(JSONObject().put("t", System.currentTimeMillis() - t0).put("values", vals))
                    }
                }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            }
            sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
            Thread.sleep(durationMs.coerceIn(100, 5000).toLong())
            sm.unregisterListener(listener)
            envelope(
                true,
                JSONObject()
                    .put("type", sensor.stringType ?: type)
                    .put("samples", samples)
                    .put("count", samples.length()),
                ms = System.currentTimeMillis() - t0
            )
        } catch (e: Exception) {
            envelope(false, error = e.message, ms = System.currentTimeMillis() - t0)
        }
    }

    @JavascriptInterface
    fun execPipeline(cmdsJson: String, timeoutMs: Int): String {
        // cmds: [["cmd","arg"],["cmd2","arg"]] — stdout of each fed as stdin-less; intermediate files in workspace
        return try {
            val cmds = JSONArray(cmdsJson)
            if (cmds.length() == 0) return envelope(false, error = "empty pipeline")
            var inputFile: File? = null
            var lastOut = ""
            val steps = JSONArray()
            val t0 = System.currentTimeMillis()
            for (i in 0 until cmds.length()) {
                val step = cmds.getJSONArray(i)
                val argv = JSONArray()
                for (j in 0 until step.length()) argv.put(step.getString(j))
                val outFile = File(workspaceRoot, ".pipe_$i.out")
                // If previous output file, use shell redirect via sh -c
                val result: String
                if (inputFile != null) {
                    val cmd = buildString {
                        append(argv.getString(0))
                        for (j in 1 until argv.length()) {
                            append(' ')
                            append("'")
                            append(argv.getString(j).replace("'", "'\\''"))
                            append("'")
                        }
                        append(" < '")
                        append(inputFile!!.absolutePath.replace("'", "'\\''"))
                        append("'")
                    }
                    result = exec(JSONArray().put("sh").put("-c").put(cmd).toString(), timeoutMs, null)
                } else {
                    result = exec(argv.toString(), timeoutMs, null)
                }
                val jo = JSONObject(result)
                val stdout = jo.optString("stdout", jo.optJSONObject("data")?.optString("stdout") ?: "")
                outFile.writeText(stdout)
                inputFile = outFile
                lastOut = stdout
                steps.put(JSONObject().put("step", i).put("ok", jo.optBoolean("ok", jo.optInt("exitCode", 1) == 0)).put("exitCode", jo.optInt("exitCode", jo.optInt("code", -1))))
            }
            // cleanup pipe files
            workspaceRoot.listFiles()?.filter { it.name.startsWith(".pipe_") }?.forEach { it.delete() }
            envelope(
                true,
                JSONObject().put("stdout", lastOut.take(80_000)).put("steps", steps),
                ms = System.currentTimeMillis() - t0
            )
        } catch (e: Exception) {
            envelope(false, error = e.message)
        }
    }


    // ─── Research / web ────────────────────────────────────────

    private fun httpGetSync(url: String, timeoutMs: Int = 12_000): Pair<Int, String> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            setRequestProperty("User-Agent", "D-Harness/1.7 (Android; research)")
            setRequestProperty("Accept", "text/html,application/json,*/*")
            instanceFollowRedirects = true
        }
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            code to body.take(400_000)
        } finally {
            conn.disconnect()
        }
    }

    private fun stripHtml(html: String): String {
        var s = html
        s = Regex("(?is)<script[^>]*>.*?</script>").replace(s, " ")
        s = Regex("(?is)<style[^>]*>.*?</style>").replace(s, " ")
        s = Regex("(?is)<[^>]+>").replace(s, " ")
        s = Regex("&nbsp;|&#160;").replace(s, " ")
        s = Regex("&amp;").replace(s, "&")
        s = Regex("&lt;").replace(s, "<")
        s = Regex("&gt;").replace(s, ">")
        s = Regex("&quot;").replace(s, "\"")
        s = Regex("&#39;|&apos;").replace(s, "'")
        s = Regex("[ \\t\\x0B\\f\\r]+").replace(s, " ")
        s = Regex("\\n{3,}").replace(s, "\n\n")
        return s.trim()
    }

    @JavascriptInterface
    fun researchWeb(query: String, maxSources: Int): String {
        val t0 = System.currentTimeMillis()
        return try {
            val q = query.trim()
            if (q.isEmpty()) return envelope(false, error = "empty query")
            val limit = maxSources.coerceIn(1, 8)
            val sources = JSONArray()

            // Wikipedia summary
            try {
                val title = q.replace(" ", "_")
                val (code, body) = httpGetSync(
                    "https://en.wikipedia.org/api/rest_v1/page/summary/" +
                        java.net.URLEncoder.encode(title, "UTF-8").replace("+", "%20"),
                    10_000
                )
                if (code in 200..299 && body.contains("extract")) {
                    val jo = JSONObject(body)
                    sources.put(
                        JSONObject()
                            .put("type", "wikipedia")
                            .put("title", jo.optString("title"))
                            .put("url", jo.optJSONObject("content_urls")
                                ?.optJSONObject("desktop")?.optString("page")
                                ?: ("https://en.wikipedia.org/wiki/" + title))
                            .put("snippet", jo.optString("extract").take(1200))
                    )
                }
            } catch (_: Exception) { }

            // DuckDuckGo HTML results
            try {
                val enc = java.net.URLEncoder.encode(q, "UTF-8")
                val (code, body) = httpGetSync("https://html.duckduckgo.com/html/?q=$enc", 12_000)
                if (code in 200..299) {
                    val re = Regex(
                        """(?is)<a[^>]*class="[^"]*result__a[^"]*"[^>]*href="([^"]+)"[^>]*>(.*?)</a>"""
                    )
                    val snipRe = Regex("""(?is)<a[^>]*class="[^"]*result__snippet[^"]*"[^>]*>(.*?)</a>""")
                    val snips = snipRe.findAll(body).map { stripHtml(it.groupValues[1]).take(280) }.toList()
                    var i = 0
                    for (m in re.findAll(body)) {
                        if (sources.length() >= limit) break
                        val href = m.groupValues[1]
                        val title = stripHtml(m.groupValues[2]).take(200)
                        if (title.isBlank()) continue
                        val snip = snips.getOrNull(i) ?: ""
                        i++
                        sources.put(
                            JSONObject()
                                .put("type", "web")
                                .put("title", title)
                                .put("url", href)
                                .put("snippet", snip)
                        )
                    }
                }
            } catch (_: Exception) { }

            // Optional: fetch first 1-2 page texts
            val pages = JSONArray()
            var fetched = 0
            for (i in 0 until sources.length()) {
                if (fetched >= 2) break
                val src = sources.getJSONObject(i)
                val u = src.optString("url")
                if (!u.startsWith("http")) continue
                try {
                    val (code, body) = httpGetSync(u, 10_000)
                    if (code in 200..299) {
                        pages.put(
                            JSONObject()
                                .put("url", u)
                                .put("text", stripHtml(body).take(4000))
                        )
                        fetched++
                    }
                } catch (_: Exception) { }
            }

            envelope(
                sources.length() > 0,
                JSONObject()
                    .put("query", q)
                    .put("sources", sources)
                    .put("pages", pages)
                    .put("count", sources.length()),
                error = if (sources.length() == 0) "no results" else null,
                ms = System.currentTimeMillis() - t0
            )
        } catch (e: Exception) {
            envelope(false, error = e.message, ms = System.currentTimeMillis() - t0)
        }
    }

    @JavascriptInterface
    fun researchPreview(url: String): String {
        val t0 = System.currentTimeMillis()
        return try {
            val u = url.trim()
            if (!u.startsWith("http")) return envelope(false, error = "url must start with http")
            val (code, body) = httpGetSync(u, 12_000)
            val title = Regex("(?is)<title[^>]*>(.*?)</title>").find(body)?.groupValues?.get(1)?.let { stripHtml(it) }
            val desc = Regex("(?is)<meta[^>]+name=[\"']description[\"'][^>]+content=[\"'](.*?)[\"']").find(body)?.groupValues?.get(1)
                ?: Regex("(?is)<meta[^>]+content=[\"'](.*?)[\"'][^>]+name=[\"']description[\"']").find(body)?.groupValues?.get(1)
            envelope(
                code in 200..299,
                JSONObject()
                    .put("url", u)
                    .put("status", code)
                    .put("title", title ?: JSONObject.NULL)
                    .put("description", desc?.let { stripHtml(it).take(500) } ?: JSONObject.NULL)
                    .put("length", body.length),
                ms = System.currentTimeMillis() - t0
            )
        } catch (e: Exception) {
            envelope(false, error = e.message, ms = System.currentTimeMillis() - t0)
        }
    }

    @JavascriptInterface
    fun researchHtmlText(url: String, maxChars: Int): String {
        val t0 = System.currentTimeMillis()
        return try {
            val u = url.trim()
            if (!u.startsWith("http")) return envelope(false, error = "url must start with http")
            val (code, body) = httpGetSync(u, 15_000)
            val text = stripHtml(body).take(maxChars.coerceIn(500, 50_000))
            envelope(
                code in 200..299,
                JSONObject().put("url", u).put("status", code).put("text", text).put("chars", text.length),
                ms = System.currentTimeMillis() - t0
            )
        } catch (e: Exception) {
            envelope(false, error = e.message, ms = System.currentTimeMillis() - t0)
        }
    }

    @JavascriptInterface
    fun researchPlan(topic: String): String {
        val t = topic.trim().ifEmpty { "general topic" }
        val steps = JSONArray()
            .put(JSONObject().put("step", 1).put("action", "clarify").put("detail", "Define scope and key questions for: $t"))
            .put(JSONObject().put("step", 2).put("action", "research.web").put("detail", "DHarness.researchWeb(query) for overview + sources"))
            .put(JSONObject().put("step", 3).put("action", "research.preview / html_text").put("detail", "Deep-read top 2–3 URLs"))
            .put(JSONObject().put("step", 4).put("action", "synthesize").put("detail", "Compare claims; note disagreements"))
            .put(JSONObject().put("step", 5).put("action", "workspace.write").put("detail", "Save notes + citations under workspace/research/"))
        return envelope(true, JSONObject().put("topic", t).put("steps", steps))
    }

    @JavascriptInterface
    fun workspaceGrep(query: String, useRegex: Boolean, maxHits: Int): String {
        val t0 = System.currentTimeMillis()
        return try {
            val q = query.trim()
            if (q.isEmpty()) return envelope(false, error = "empty query")
            val limit = maxHits.coerceIn(1, 200)
            val hits = JSONArray()
            val pattern = if (useRegex) Regex(q, RegexOption.IGNORE_CASE) else null
            workspaceRoot.walkTopDown().filter { it.isFile && it.length() < 2_000_000 }.forEach { f ->
                if (hits.length() >= limit) return@forEach
                val rel = f.relativeTo(workspaceRoot).path
                if (rel.startsWith(".")) return@forEach
                try {
                    val lines = f.readLines(Charsets.UTF_8)
                    lines.forEachIndexed { idx, line ->
                        if (hits.length() >= limit) return@forEachIndexed
                        val match = if (pattern != null) pattern.containsMatchIn(line)
                        else line.contains(q, ignoreCase = true)
                        if (match) {
                            hits.put(
                                JSONObject()
                                    .put("path", rel)
                                    .put("line", idx + 1)
                                    .put("text", line.take(300))
                            )
                        }
                    }
                } catch (_: Exception) { }
            }
            envelope(
                true,
                JSONObject().put("query", q).put("regex", useRegex).put("hits", hits).put("count", hits.length()),
                ms = System.currentTimeMillis() - t0
            )
        } catch (e: Exception) {
            envelope(false, error = e.message, ms = System.currentTimeMillis() - t0)
        }
    }

    private fun resolveLangBinary(lang: String): String? {
        val candidates = when (lang.lowercase()) {
            "python", "python3", "py" -> listOf("python3", "python")
            "node", "nodejs", "js" -> listOf("node", "nodejs")
            "php" -> listOf("php")
            "ruby", "rb" -> listOf("ruby")
            "lua" -> listOf("lua")
            "perl", "pl" -> listOf("perl")
            "sh", "bash", "shell" -> listOf("sh", "bash")
            else -> listOf(lang)
        }
        for (bin in candidates) {
            val paths = listOf("/system/bin/$bin", "/system/xbin/$bin", "/data/local/tmp/$bin")
            if (paths.any { File(it).canExecute() }) return paths.first { File(it).canExecute() }
            // which via toybox
            try {
                val p = ProcessBuilder("sh", "-c", "command -v $bin 2>/dev/null").start()
                val out = p.inputStream.bufferedReader().readText().trim()
                p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
                if (out.isNotEmpty() && File(out).canExecute()) return out
            } catch (_: Exception) { }
        }
        return null
    }

    @JavascriptInterface
    fun execLangs(): String {
        return try {
            val langs = listOf("python3", "node", "php", "ruby", "lua", "perl", "sh")
            val arr = JSONArray()
            for (l in langs) {
                val bin = resolveLangBinary(l)
                arr.put(JSONObject().put("lang", l).put("available", bin != null).put("path", bin ?: JSONObject.NULL))
            }
            envelope(true, JSONObject().put("runtimes", arr))
        } catch (e: Exception) {
            envelope(false, error = e.message)
        }
    }

    @JavascriptInterface
    fun execWhich(bin: String): String {
        return try {
            val b = bin.trim().substringAfterLast('/')
            if (b.isEmpty() || b.contains(' ')) return envelope(false, error = "invalid bin")
            val path = resolveLangBinary(b) ?: run {
                val p = ProcessBuilder("sh", "-c", "command -v $b 2>/dev/null").start()
                val out = p.inputStream.bufferedReader().readText().trim()
                p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
                out.ifEmpty { null }
            }
            envelope(path != null, JSONObject().put("bin", b).put("path", path ?: JSONObject.NULL))
        } catch (e: Exception) {
            envelope(false, error = e.message)
        }
    }

    @JavascriptInterface
    fun execLang(lang: String, code: String, timeoutMs: Int): String {
        val t0 = System.currentTimeMillis()
        return try {
            if (code.isBlank()) return envelope(false, error = "empty code — pass args.code")
            val binary = resolveLangBinary(lang)
                ?: return envelope(false, error = "runtime not found: $lang — call execLangs()")
            val ext = when (lang.lowercase()) {
                "python", "python3", "py" -> ".py"
                "node", "nodejs", "js" -> ".js"
                "php" -> ".php"
                "ruby", "rb" -> ".rb"
                "lua" -> ".lua"
                "perl", "pl" -> ".pl"
                else -> ".sh"
            }
            val script = File(workspaceRoot, ".run_${System.currentTimeMillis()}$ext")
            script.writeText(code)
            try {
                val argv = when {
                    ext == ".py" -> JSONArray().put(binary).put(script.absolutePath)
                    ext == ".js" -> JSONArray().put(binary).put(script.absolutePath)
                    ext == ".php" -> JSONArray().put(binary).put(script.absolutePath)
                    ext == ".rb" -> JSONArray().put(binary).put(script.absolutePath)
                    ext == ".lua" -> JSONArray().put(binary).put(script.absolutePath)
                    ext == ".pl" -> JSONArray().put(binary).put(script.absolutePath)
                    else -> JSONArray().put(binary).put(script.absolutePath)
                }
                val result = exec(argv.toString(), timeoutMs.coerceIn(500, 120_000), null)
                // wrap
                val jo = try { JSONObject(result) } catch (_: Exception) { JSONObject().put("raw", result) }
                jo.put("lang", lang).put("binary", binary).put("script", script.name)
                jo.toString()
            } finally {
                script.delete()
            }
        } catch (e: Exception) {
            envelope(false, error = e.message, ms = System.currentTimeMillis() - t0)
        }
    }

    @JavascriptInterface
    fun textRegexFind(text: String, pattern: String, flags: String?): String {
        return try {
            val opts = mutableSetOf<RegexOption>()
            val f = flags ?: ""
            if (f.contains("i")) opts.add(RegexOption.IGNORE_CASE)
            if (f.contains("m")) opts.add(RegexOption.MULTILINE)
            if (f.contains("s")) opts.add(RegexOption.DOT_MATCHES_ALL)
            val re = if (opts.isEmpty()) Regex(pattern) else Regex(pattern, opts)
            val matches = JSONArray()
            re.findAll(text).take(200).forEach { m ->
                matches.put(
                    JSONObject()
                        .put("match", m.value.take(500))
                        .put("start", m.range.first)
                        .put("end", m.range.last + 1)
                )
            }
            envelope(true, JSONObject().put("count", matches.length()).put("matches", matches))
        } catch (e: Exception) {
            envelope(false, error = e.message)
        }
    }

    @JavascriptInterface
    fun textRegexReplace(text: String, pattern: String, replacement: String): String {
        return try {
            val out = Regex(pattern).replace(text, replacement)
            envelope(true, JSONObject().put("text", out).put("length", out.length))
        } catch (e: Exception) {
            envelope(false, error = e.message)
        }
    }

    @JavascriptInterface
    fun utilBase64(op: String, data: String): String {
        return try {
            when (op.lowercase()) {
                "encode", "enc" -> {
                    val b64 = Base64.encodeToString(data.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                    envelope(true, JSONObject().put("result", b64))
                }
                "decode", "dec" -> {
                    val bytes = Base64.decode(data, Base64.DEFAULT)
                    envelope(true, JSONObject().put("result", String(bytes, Charsets.UTF_8)))
                }
                else -> envelope(false, error = "op must be encode|decode")
            }
        } catch (e: Exception) {
            envelope(false, error = e.message)
        }
    }

    @JavascriptInterface
    fun utilUuid(): String {
        return envelope(true, JSONObject().put("uuid", java.util.UUID.randomUUID().toString()))
    }

    @JavascriptInterface
    fun utilTime(): String {
        val now = System.currentTimeMillis()
        val tz = java.util.TimeZone.getDefault()
        return envelope(
            true,
            JSONObject()
                .put("epochMs", now)
                .put("iso", java.time.Instant.ofEpochMilli(now).toString())
                .put("timezone", tz.id)
                .put("offsetMs", tz.rawOffset)
        )
    }


    // ── Extra coding / GitHub tools (1.9.7) ───────────────────

    private fun readWorkspaceText(path: String): String = safeFile(path).readText()

    @JavascriptInterface
    fun codeSearch(query: String, path: String?, ext: String?, maxHits: Int): String {
        return try {
            val root = if (path.isNullOrBlank()) workspaceRoot else safeFile(path)
            val limit = if (maxHits <= 0) 40 else maxHits.coerceAtMost(200)
            val re = Regex(query)
            val exts = ext?.split(',', ' ')?.map { it.trim().trimStart('.').lowercase() }?.filter { it.isNotEmpty() }?.toSet()
            val hits = JSONArray()
            fun walk(f: java.io.File) {
                if (hits.length() >= limit) return
                if (f.isDirectory) {
                    f.listFiles()?.forEach { walk(it) }
                    return
                }
                if (f.length() > 1_500_000) return
                val name = f.name.lowercase()
                val e = name.substringAfterLast('.', "")
                if (exts != null && e !in exts) return
                f.readLines().forEachIndexed { i, line ->
                    if (hits.length() >= limit) return
                    if (re.containsMatchIn(line)) {
                        val rel = try { f.relativeTo(workspaceRoot).path } catch (_: Exception) { f.name }
                        hits.put(JSONObject().put("path", rel).put("line", i + 1).put("text", line.trim().take(200)))
                    }
                }
            }
            walk(root)
            JSONObject().put("ok", true).put("count", hits.length()).put("hits", hits).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun codeSlice(path: String, start: Int, end: Int): String {
        return try {
            val lines = readWorkspaceText(path).split('\n')
            val s = start.coerceAtLeast(1)
            val e = (if (end <= 0) lines.size else end).coerceAtMost(lines.size)
            if (s > e) return JSONObject().put("ok", false).put("error", "bad range").toString()
            val slice = lines.subList(s - 1, e).joinToString("\n")
            JSONObject().put("ok", true).put("start", s).put("end", e).put("text", slice).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun codeCountLines(path: String?, content: String?): String {
        return try {
            val src = when {
                !content.isNullOrBlank() -> content
                !path.isNullOrBlank() -> readWorkspaceText(path)
                else -> return JSONObject().put("ok", false).put("error", "path or content").toString()
            }
            val lines = src.split('\n')
            JSONObject().put("ok", true)
                .put("lines", lines.size)
                .put("nonEmpty", lines.count { it.isNotBlank() })
                .put("chars", src.length)
                .toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun codeImports(path: String?, content: String?): String {
        return try {
            val src = when {
                !content.isNullOrBlank() -> content
                !path.isNullOrBlank() -> readWorkspaceText(path)
                else -> return JSONObject().put("ok", false).put("error", "path or content").toString()
            }
            val re = Regex("""(?m)^\s*(?:import\s+.+|from\s+\S+\s+import\s+.+|require\s*\(.+\)|#include\s+[<"].+[>"])""")
            val arr = JSONArray()
            src.lineSequence().forEach { line ->
                if (re.containsMatchIn(line)) arr.put(line.trim())
            }
            JSONObject().put("ok", true).put("imports", arr).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun codeDetectLang(path: String?, content: String?): String {
        val p = path ?: ""
        val ext = p.substringAfterLast('.', "").lowercase()
        val map = mapOf(
            "kt" to "kotlin", "kts" to "kotlin", "java" to "java", "js" to "javascript",
            "ts" to "typescript", "tsx" to "typescript", "py" to "python", "go" to "go",
            "rs" to "rust", "c" to "c", "cpp" to "cpp", "h" to "c", "md" to "markdown",
            "json" to "json", "xml" to "xml", "html" to "html", "css" to "css", "sh" to "shell"
        )
        var lang = map[ext]
        val sample = (content ?: "").take(400)
        if (lang == null) {
            lang = when {
                "fun " in sample && "package " in sample -> "kotlin"
                "def " in sample && "import " in sample -> "python"
                "fn " in sample && "let " in sample -> "rust"
                "function " in sample || "const " in sample -> "javascript"
                else -> "unknown"
            }
        }
        return JSONObject().put("ok", true).put("lang", lang).put("ext", ext).toString()
    }

    @JavascriptInterface
    fun workspaceReplace(path: String, find: String, replace: String, regex: Boolean): String {
        return try {
            val f = safeFile(path)
            val src = f.readText()
            val out = if (regex) Regex(find).replace(src, replace) else src.replace(find, replace)
            val count = if (regex) Regex(find).findAll(src).count() else src.split(find).size - 1
            f.writeText(out)
            JSONObject().put("ok", true).put("replacements", count.coerceAtLeast(0)).put("bytes", out.length).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun workspaceHead(path: String, lines: Int): String {
        return try {
            val n = if (lines <= 0) 20 else lines.coerceAtMost(500)
            val text = readWorkspaceText(path).lineSequence().take(n).joinToString("\n")
            JSONObject().put("ok", true).put("text", text).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun workspaceTail(path: String, lines: Int): String {
        return try {
            val n = if (lines <= 0) 20 else lines.coerceAtMost(500)
            val all = readWorkspaceText(path).split('\n')
            val text = all.takeLast(n).joinToString("\n")
            JSONObject().put("ok", true).put("text", text).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun workspaceGlob(pattern: String, path: String?, max: Int): String {
        return try {
            val root = if (path.isNullOrBlank()) workspaceRoot else safeFile(path)
            val limit = if (max <= 0) 200 else max.coerceAtMost(1000)
            val pat = pattern.trim()
            val recursive = pat.contains("**/") || pat.startsWith("**/")
            val suffix = pat.removePrefix("**/").removePrefix("**")
            val matches = JSONArray()
            fun matchName(name: String): Boolean {
                if (suffix.startsWith("*.")) {
                    val ext = suffix.removePrefix("*")
                    return name.endsWith(ext)
                }
                return name == suffix || name.contains(suffix.trim('*'))
            }
            fun walk(f: java.io.File) {
                if (matches.length() >= limit) return
                if (f.isDirectory) {
                    f.listFiles()?.forEach { walk(it) }
                    return
                }
                if (matchName(f.name)) {
                    val rel = try { f.relativeTo(workspaceRoot).path } catch (_: Exception) { f.name }
                    matches.put(rel)
                }
            }
            walk(root)
            JSONObject().put("ok", true).put("count", matches.length()).put("files", matches).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun jsonMerge(a: String, b: String): String {
        return try {
            val oa = JSONObject(a)
            val ob = JSONObject(b)
            val keys = ob.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                oa.put(k, ob.get(k))
            }
            JSONObject().put("ok", true).put("json", oa).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun jsonKeys(jsonStr: String): String {
        return try {
            val o = JSONObject(jsonStr)
            val arr = JSONArray()
            val keys = o.keys()
            while (keys.hasNext()) arr.put(keys.next())
            JSONObject().put("ok", true).put("keys", arr).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun textWordCount(text: String): String {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return JSONObject().put("ok", true)
            .put("words", words.size)
            .put("lines", text.split('\n').size)
            .put("chars", text.length)
            .toString()
    }

    @JavascriptInterface
    fun diffFile(pathA: String, pathB: String): String {
        return try {
            diffLines(readWorkspaceText(pathA), readWorkspaceText(pathB))
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    private fun ghJsonArray(res: JSONObject): JSONArray {
        val j = res.opt("json")
        return when (j) {
            is JSONArray -> j
            else -> JSONArray()
        }
    }

    private fun ghJsonObject(res: JSONObject): JSONObject? = res.optJSONObject("json")

    @JavascriptInterface
    fun githubPrList(owner: String, repo: String, state: String?, perPage: Int): String {
        return try {
            val token = githubToken() ?: return JSONObject().put("ok", false).put("error", "no github token").toString()
            val n = if (perPage <= 0) 15 else perPage.coerceAtMost(50)
            val st = state?.ifBlank { "open" } ?: "open"
            val res = githubRequestSync("GET", "/repos/$owner/$repo/pulls?state=$st&per_page=$n", null, token)
            if (!res.optBoolean("ok")) return JSONObject().put("ok", false).put("status", res.optInt("status")).put("error", res.optString("text").take(300)).toString()
            val arr = ghJsonArray(res)
            val out = JSONArray()
            for (i in 0 until arr.length()) {
                val p = arr.optJSONObject(i) ?: continue
                out.put(JSONObject().put("number", p.optInt("number")).put("title", p.optString("title"))
                    .put("state", p.optString("state")).put("user", p.optJSONObject("user")?.optString("login"))
                    .put("html_url", p.optString("html_url")))
            }
            JSONObject().put("ok", true).put("prs", out).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun githubPrMerge(owner: String, repo: String, number: Int, method: String?): String {
        return try {
            val token = githubToken() ?: return JSONObject().put("ok", false).put("error", "no github token").toString()
            val m = when (method?.lowercase()) {
                "squash" -> "squash"
                "rebase" -> "rebase"
                else -> "merge"
            }
            val body = JSONObject().put("merge_method", m).toString()
            val res = githubRequestSync("PUT", "/repos/$owner/$repo/pulls/$number/merge", body, token)
            val j = ghJsonObject(res)
            JSONObject().put("ok", res.optBoolean("ok"))
                .put("merged", j?.optBoolean("merged") ?: false)
                .put("message", j?.optString("message") ?: res.optString("text").take(200))
                .put("sha", j?.optString("sha"))
                .toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun githubIssueUpdate(owner: String, repo: String, number: Int, title: String?, body: String?, state: String?): String {
        return try {
            val token = githubToken() ?: return JSONObject().put("ok", false).put("error", "no github token").toString()
            val payload = JSONObject()
            if (!title.isNullOrBlank()) payload.put("title", title)
            if (body != null) payload.put("body", body)
            if (!state.isNullOrBlank()) payload.put("state", state)
            val res = githubRequestSync("PATCH", "/repos/$owner/$repo/issues/$number", payload.toString(), token)
            val j = ghJsonObject(res)
            JSONObject().put("ok", res.optBoolean("ok"))
                .put("number", j?.optInt("number") ?: number)
                .put("state", j?.optString("state"))
                .put("html_url", j?.optString("html_url"))
                .put("error", if (!res.optBoolean("ok")) res.optString("text").take(300) else null)
                .toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun githubLabels(owner: String, repo: String): String {
        return try {
            val token = githubToken() ?: return JSONObject().put("ok", false).put("error", "no github token").toString()
            val res = githubRequestSync("GET", "/repos/$owner/$repo/labels?per_page=50", null, token)
            val arr = ghJsonArray(res)
            val out = JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.put(JSONObject().put("name", o.optString("name")).put("color", o.optString("color")))
            }
            JSONObject().put("ok", res.optBoolean("ok")).put("labels", out).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun githubBranches(owner: String, repo: String, perPage: Int): String {
        return try {
            val token = githubToken() ?: return JSONObject().put("ok", false).put("error", "no github token").toString()
            val n = if (perPage <= 0) 30 else perPage.coerceAtMost(100)
            val res = githubRequestSync("GET", "/repos/$owner/$repo/branches?per_page=$n", null, token)
            val arr = ghJsonArray(res)
            val out = JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.put(JSONObject().put("name", o.optString("name")).put("sha", o.optJSONObject("commit")?.optString("sha")?.take(8)))
            }
            JSONObject().put("ok", res.optBoolean("ok")).put("branches", out).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun githubReleaseLatest(owner: String, repo: String): String {
        return try {
            val token = githubToken() ?: return JSONObject().put("ok", false).put("error", "no github token").toString()
            val res = githubRequestSync("GET", "/repos/$owner/$repo/releases/latest", null, token)
            val j = ghJsonObject(res)
            JSONObject().put("ok", res.optBoolean("ok"))
                .put("tag", j?.optString("tag_name"))
                .put("name", j?.optString("name"))
                .put("html_url", j?.optString("html_url"))
                .put("published_at", j?.optString("published_at"))
                .put("body", j?.optString("body")?.take(2000))
                .toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun githubReleases(owner: String, repo: String, perPage: Int): String {
        return try {
            val token = githubToken() ?: return JSONObject().put("ok", false).put("error", "no github token").toString()
            val n = if (perPage <= 0) 10 else perPage.coerceAtMost(30)
            val res = githubRequestSync("GET", "/repos/$owner/$repo/releases?per_page=$n", null, token)
            val arr = ghJsonArray(res)
            val out = JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.put(JSONObject().put("tag", o.optString("tag_name")).put("name", o.optString("name")).put("html_url", o.optString("html_url")))
            }
            JSONObject().put("ok", res.optBoolean("ok")).put("releases", out).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun githubWorkflows(owner: String, repo: String): String {
        return try {
            val token = githubToken() ?: return JSONObject().put("ok", false).put("error", "no github token").toString()
            val res = githubRequestSync("GET", "/repos/$owner/$repo/actions/workflows", null, token)
            val j = ghJsonObject(res)
            val arr = j?.optJSONArray("workflows") ?: JSONArray()
            val out = JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.put(JSONObject().put("id", o.optLong("id")).put("name", o.optString("name")).put("state", o.optString("state")).put("path", o.optString("path")))
            }
            JSONObject().put("ok", res.optBoolean("ok")).put("workflows", out).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun githubWorkflowRuns(owner: String, repo: String, perPage: Int): String {
        return try {
            val token = githubToken() ?: return JSONObject().put("ok", false).put("error", "no github token").toString()
            val n = if (perPage <= 0) 10 else perPage.coerceAtMost(30)
            val res = githubRequestSync("GET", "/repos/$owner/$repo/actions/runs?per_page=$n", null, token)
            val j = ghJsonObject(res)
            val arr = j?.optJSONArray("workflow_runs") ?: JSONArray()
            val out = JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.put(JSONObject()
                    .put("id", o.optLong("id"))
                    .put("name", o.optString("name"))
                    .put("status", o.optString("status"))
                    .put("conclusion", o.optString("conclusion"))
                    .put("html_url", o.optString("html_url"))
                    .put("head_branch", o.optString("head_branch")))
            }
            JSONObject().put("ok", res.optBoolean("ok")).put("runs", out).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun githubTree(owner: String, repo: String, ref: String?, recursive: Boolean): String {
        return try {
            val token = githubToken() ?: return JSONObject().put("ok", false).put("error", "no github token").toString()
            val r = ref?.ifBlank { "HEAD" } ?: "HEAD"
            val path = "/repos/$owner/$repo/git/trees/$r" + if (recursive) "?recursive=1" else ""
            val res = githubRequestSync("GET", path, null, token)
            val j = ghJsonObject(res)
            val arr = j?.optJSONArray("tree") ?: JSONArray()
            val out = JSONArray()
            val limit = 300
            for (i in 0 until minOf(arr.length(), limit)) {
                val o = arr.optJSONObject(i) ?: continue
                out.put(JSONObject().put("path", o.optString("path")).put("type", o.optString("type")).put("size", o.optInt("size")))
            }
            JSONObject().put("ok", res.optBoolean("ok")).put("truncated", j?.optBoolean("truncated") ?: false).put("tree", out).put("count", out.length()).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun githubUser(username: String): String {
        return try {
            val token = githubToken() ?: return JSONObject().put("ok", false).put("error", "no github token").toString()
            val res = githubRequestSync("GET", "/users/$username", null, token)
            val j = ghJsonObject(res)
            JSONObject().put("ok", res.optBoolean("ok"))
                .put("login", j?.optString("login"))
                .put("name", j?.optString("name"))
                .put("bio", j?.optString("bio"))
                .put("public_repos", j?.optInt("public_repos"))
                .put("followers", j?.optInt("followers"))
                .put("html_url", j?.optString("html_url"))
                .toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun githubGistCreate(filesJson: String, description: String?, isPublic: Boolean): String {
        return try {
            val token = githubToken() ?: return JSONObject().put("ok", false).put("error", "no github token").toString()
            val filesIn = JSONObject(filesJson)
            val files = JSONObject()
            val keys = filesIn.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                files.put(k, JSONObject().put("content", filesIn.get(k).toString()))
            }
            val payload = JSONObject()
                .put("description", description ?: "")
                .put("public", isPublic)
                .put("files", files)
            val res = githubRequestSync("POST", "/gists", payload.toString(), token)
            val j = ghJsonObject(res)
            JSONObject().put("ok", res.optBoolean("ok"))
                .put("id", j?.optString("id"))
                .put("html_url", j?.optString("html_url"))
                .put("error", if (!res.optBoolean("ok")) res.optString("text").take(300) else null)
                .toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun githubPrComment(owner: String, repo: String, number: Int, body: String): String {
        return try {
            val token = githubToken() ?: return JSONObject().put("ok", false).put("error", "no github token").toString()
            val payload = JSONObject().put("body", body).toString()
            val res = githubRequestSync("POST", "/repos/$owner/$repo/issues/$number/comments", payload, token)
            val j = ghJsonObject(res)
            JSONObject().put("ok", res.optBoolean("ok"))
                .put("id", j?.optLong("id"))
                .put("html_url", j?.optString("html_url"))
                .toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun githubForks(owner: String, repo: String, perPage: Int): String {
        return try {
            val token = githubToken() ?: return JSONObject().put("ok", false).put("error", "no github token").toString()
            val n = if (perPage <= 0) 10 else perPage.coerceAtMost(30)
            val res = githubRequestSync("GET", "/repos/$owner/$repo/forks?per_page=$n", null, token)
            val arr = ghJsonArray(res)
            val out = JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.put(JSONObject().put("full_name", o.optString("full_name")).put("html_url", o.optString("html_url")))
            }
            JSONObject().put("ok", res.optBoolean("ok")).put("forks", out).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun githubTags(owner: String, repo: String, perPage: Int): String {
        return try {
            val token = githubToken() ?: return JSONObject().put("ok", false).put("error", "no github token").toString()
            val n = if (perPage <= 0) 20 else perPage.coerceAtMost(50)
            val res = githubRequestSync("GET", "/repos/$owner/$repo/tags?per_page=$n", null, token)
            val arr = ghJsonArray(res)
            val out = JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.put(JSONObject().put("name", o.optString("name")).put("sha", o.optJSONObject("commit")?.optString("sha")?.take(8)))
            }
            JSONObject().put("ok", res.optBoolean("ok")).put("tags", out).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }


    // ─── History / rollback (Cursor-style) ─────────────────────
    private val historyRoot: File by lazy {
        File(context.filesDir, "history").also { it.mkdirs() }
    }
    private fun historySnapshot(f: File) {
        try {
            if (!f.isFile) return
            val key = sha256(f.absolutePath.toByteArray()).take(16)
            val dir = File(historyRoot, key).also { it.mkdirs() }
            val ver = System.currentTimeMillis()
            f.copyTo(File(dir, "$ver.bak"), overwrite = true)
            // keep last 10
            dir.listFiles()?.sortedByDescending { it.name }?.drop(10)?.forEach { it.delete() }
            File(dir, "path.txt").writeText(f.absolutePath)
        } catch (_: Exception) {}
    }

    @JavascriptInterface
    fun historyList(path: String): String {
        return try {
            val f = try { safeWorkspace(path) } catch (_: Exception) { safeFile(path) }
            val key = sha256(f.absolutePath.toByteArray()).take(16)
            val dir = File(historyRoot, key)
            val arr = JSONArray()
            dir.listFiles()?.filter { it.name.endsWith(".bak") }?.sortedByDescending { it.name }?.forEach {
                arr.put(JSONObject().put("version", it.name.removeSuffix(".bak")).put("bytes", it.length()))
            }
            JSONObject().put("ok", true).put("path", f.absolutePath).put("versions", arr).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun historyRevert(path: String, version: String): String {
        return try {
            val f = try { safeWorkspace(path) } catch (_: Exception) { safeFile(path) }
            val key = sha256(f.absolutePath.toByteArray()).take(16)
            val bak = File(File(historyRoot, key), "${version}.bak")
            if (!bak.isFile) return JSONObject().put("ok", false).put("error", "version not found").toString()
            historySnapshot(f)
            bak.copyTo(f, overwrite = true)
            JSONObject().put("ok", true).put("path", f.absolutePath).put("restored", version)
                .put("sha256", sha256(f.readBytes())).put("verified", true).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    // ─── Task list (Claude Code TodoWrite) ─────────────────────
    private val taskStore by lazy { context.getSharedPreferences("dharness_tasks", Context.MODE_PRIVATE) }

    @JavascriptInterface
    fun taskAdd(content: String): String {
        val id = "t-" + System.currentTimeMillis().toString(36)
        val o = JSONObject().put("id", id).put("content", content).put("status", "pending")
            .put("activeForm", content).put("created", System.currentTimeMillis())
        taskStore.edit().putString(id, o.toString()).apply()
        return JSONObject().put("ok", true).put("id", id).put("task", o).toString()
    }

    @JavascriptInterface
    fun taskUpdate(id: String, status: String): String {
        val raw = taskStore.getString(id, null)
            ?: return JSONObject().put("ok", false).put("error", "not found").toString()
        val o = JSONObject(raw)
        val st = status.lowercase()
        if (st !in setOf("pending", "in_progress", "completed", "cancelled")) {
            return JSONObject().put("ok", false).put("error", "bad status").toString()
        }
        o.put("status", st).put("updated", System.currentTimeMillis())
        taskStore.edit().putString(id, o.toString()).apply()
        return JSONObject().put("ok", true).put("task", o).toString()
    }

    @JavascriptInterface
    fun taskList(): String {
        val arr = JSONArray()
        taskStore.all.forEach { (_, v) ->
            try { arr.put(JSONObject(v.toString())) } catch (_: Exception) {}
        }
        return JSONObject().put("ok", true).put("tasks", arr).put("count", arr.length()).toString()
    }

    @JavascriptInterface
    fun taskClear(): String {
        taskStore.edit().clear().apply()
        return JSONObject().put("ok", true).toString()
    }

    // ─── Session save/load (Codex-style) ───────────────────────
    private val sessionRoot: File by lazy { File(context.filesDir, "sessions").also { it.mkdirs() } }

    @JavascriptInterface
    fun sessionSave(name: String, stateJson: String?): String {
        return try {
            val safe = name.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(64).ifBlank { "default" }
            val f = File(sessionRoot, "$safe.json")
            val payload = JSONObject()
                .put("name", safe)
                .put("savedAt", System.currentTimeMillis())
                .put("tasks", JSONObject(taskList()).optJSONArray("tasks"))
                .put("state", if (stateJson.isNullOrBlank()) JSONObject() else try { JSONObject(stateJson) } catch (_: Exception) { JSONObject().put("raw", stateJson) })
            f.writeText(payload.toString(2))
            JSONObject().put("ok", true).put("path", f.absolutePath).put("name", safe).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun sessionLoad(name: String): String {
        return try {
            val safe = name.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(64)
            val f = File(sessionRoot, "$safe.json")
            if (!f.isFile) return JSONObject().put("ok", false).put("error", "not found").toString()
            JSONObject().put("ok", true).put("session", JSONObject(f.readText())).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun sessionList(): String {
        val arr = JSONArray()
        sessionRoot.listFiles()?.filter { it.name.endsWith(".json") }?.forEach {
            arr.put(JSONObject().put("name", it.name.removeSuffix(".json")).put("bytes", it.length()).put("mtime", it.lastModified()))
        }
        return JSONObject().put("ok", true).put("sessions", arr).toString()
    }

    // ─── Policy (allow/deny/ask) ───────────────────────────────
    private val policyStore by lazy { context.getSharedPreferences("dharness_policy", Context.MODE_PRIVATE) }

    @JavascriptInterface
    fun policyAllow(pattern: String): String {
        policyStore.edit().putString("allow:" + pattern, "allow").apply()
        return JSONObject().put("ok", true).put("pattern", pattern).put("action", "allow").toString()
    }

    @JavascriptInterface
    fun policyDeny(pattern: String): String {
        policyStore.edit().putString("deny:" + pattern, "deny").apply()
        return JSONObject().put("ok", true).put("pattern", pattern).put("action", "deny").toString()
    }

    @JavascriptInterface
    fun policyCheck(tool: String): String {
        val denies = policyStore.all.filter { it.key.startsWith("deny:") }.keys.map { it.removePrefix("deny:") }
        val allows = policyStore.all.filter { it.key.startsWith("allow:") }.keys.map { it.removePrefix("allow:") }
        for (d in denies) if (tool.contains(d) || tool == d || d == "*") {
            return JSONObject().put("ok", true).put("allowed", false).put("reason", "denied:$d").toString()
        }
        for (a in allows) if (tool.contains(a) || tool == a || a == "*") {
            return JSONObject().put("ok", true).put("allowed", true).put("reason", "allowed:$a").toString()
        }
        return JSONObject().put("ok", true).put("allowed", true).put("reason", "default").toString()
    }

    // ─── Dispatch log ─────────────────────────────────────────
    private val dispatchLog = java.util.concurrent.ConcurrentLinkedDeque<JSONObject>()

    private fun logDispatch(entry: JSONObject) {
        dispatchLog.addFirst(entry)
        while (dispatchLog.size > 50) dispatchLog.pollLast()
    }

    @JavascriptInterface
    fun dispatchLog(): String {
        val arr = JSONArray()
        dispatchLog.forEach { arr.put(it) }
        return JSONObject().put("ok", true).put("entries", arr).put("count", arr.length()).toString()
    }

    @JavascriptInterface
    fun dispatchErrors(): String {
        val arr = JSONArray()
        dispatchLog.filter { it.optString("status") != "ok" }.forEach { arr.put(it) }
        return JSONObject().put("ok", true).put("entries", arr).toString()
    }

    // ─── Idempotency ──────────────────────────────────────────
    private val idempotencyCache = ConcurrentHashMap<String, String>()

    @JavascriptInterface
    fun idempotencyGet(key: String): String {
        val v = idempotencyCache[key]
        return if (v != null) JSONObject().put("ok", true).put("hit", true).put("result", JSONObject(v)).toString()
        else JSONObject().put("ok", true).put("hit", false).toString()
    }

    // ─── Intent open URL (safe) ───────────────────────────────
    @JavascriptInterface
    fun intentOpenUrl(url: String): String {
        return try {
            if (url.isBlank() || url == "[object Object]") {
                return JSONObject().put("ok", false).put("error", "ARGS_INVALID")
                    .put("hint", "pass args.url as string").toString()
            }
            val uri = android.net.Uri.parse(url)
            val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            webView.post {
                try {
                    context.startActivity(intent)
                } catch (e: Exception) {
                    android.util.Log.e("DHarness", "openUrl", e)
                }
            }
            JSONObject().put("ok", true).put("url", url).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    // ─── Index (lightweight Cursor-style) ─────────────────────
    @JavascriptInterface
    fun indexBuild(path: String?): String {
        return try {
            val root = if (path.isNullOrBlank()) workspaceRoot else safeWorkspace(path)
            val arr = JSONArray()
            var n = 0
            root.walkTopDown().maxDepth(6).forEach { f ->
                if (n >= 500) return@forEach
                if (f.isFile && f.length() < 2_000_000) {
                    val rel = try { f.relativeTo(workspaceRoot).path } catch (_: Exception) { f.name }
                    val ext = f.extension.lowercase()
                    arr.put(JSONObject().put("path", rel).put("size", f.length()).put("mtime", f.lastModified()).put("ext", ext))
                    n++
                }
            }
            val idxFile = File(context.filesDir, "workspace_index.json")
            idxFile.writeText(arr.toString())
            JSONObject().put("ok", true).put("count", n).put("indexedAt", System.currentTimeMillis()).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun indexFind(query: String): String {
        return try {
            val idxFile = File(context.filesDir, "workspace_index.json")
            if (!idxFile.isFile) indexBuild(null)
            val arr = JSONArray(idxFile.readText())
            val q = query.lowercase()
            val hits = JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.optString("path").lowercase().contains(q)) hits.put(o)
                if (hits.length() >= 40) break
            }
            JSONObject().put("ok", true).put("hits", hits).put("count", hits.length()).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    // Snapshot workspace writes too
    // (workspaceWrite already exists — patch to call historySnapshot)


}
}
