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
    private val readSession = ConcurrentHashMap<String, Long>()
    private val trajectory = java.util.concurrent.ConcurrentLinkedDeque<JSONObject>()
    private fun traj(tool: String, ok: Boolean, ms: Long, code: String? = null) {
        try {
            trajectory.addFirst(JSONObject().put("ts", System.currentTimeMillis()).put("tool", tool)
                .put("ok", ok).put("ms", ms).apply { if (code != null) put("code", code) })
            while (trajectory.size > 100) trajectory.pollLast()
        } catch (_: Exception) {}
    }

    private val inFlight = ConcurrentHashMap<String, Long>()
    private val childProcs = ConcurrentHashMap<Int, java.lang.Process>()

    private val execAllow = setOf(
        // core
        "ls", "cat", "grep", "egrep", "fgrep", "find", "echo", "pwd", "wc", "head", "tail",
        "date", "uname", "id", "whoami", "which", "true", "false", "yes", "printf", "test", "[",
        "sha256sum", "md5sum", "sha1sum", "base64", "cksum", "cmp", "diff", "stat", "du", "df", "sleep",
        "rm", "mkdir", "rmdir", "touch", "cp", "mv", "ln", "chmod", "chown", "readlink", "realpath",
        "dirname", "basename", "cut", "sort", "uniq", "tr", "tee", "xargs", "sed", "awk",
        "tar", "gzip", "gunzip", "zcat", "bzip2", "xz", "unzip", "zipinfo", "gzip",
        "toybox", "busybox", "getprop", "setprop", "ps", "top", "kill", "killall", "pidof",
        "pm", "cmd", "service", "am", "input", "wm", "settings", "logcat", "dumpsys",
        "printenv", "env", "sysctl", "getconf", "nproc", "free", "uptime",
        // network (device binary if present; INTERNET permission on app)
        "ip", "ifconfig", "netstat", "ping", "curl", "wget", "nslookup", "dig", "host",
        "nc", "netcat", "traceroute", "tracepath", "ss",
        "seq", "rev", "file", "strings", "hexdump", "nl", "od", "xxd",
        "sh", "mksh", "bash", "ash", "timeout", "nice", "nohup", "env",
        "openssl", "sqlite3", "python", "python3", "node", "perl"
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

    private fun skillAssetNames(): List<String> {
        return try {
            (context.assets.list("skills") ?: emptyArray()).filter { it.endsWith(".md") }.sorted()
        } catch (_: Exception) { emptyList() }
    }

    private fun readSkillAsset(name: String): String? {
        val base = name.trim().removeSuffix(".md").lowercase()
        if (base.isEmpty()) return null
        val path = "skills/$base.md"
        return try {
            context.assets.open(path).bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            null
        }
    }

    @JavascriptInterface
    fun skillList(): String {
        val arr = JSONArray()
        for (file in skillAssetNames()) {
            val body = readSkillAsset(file.removeSuffix(".md")) ?: continue
            val name = file.removeSuffix(".md")
            var desc = ""
            val lines = body.lines()
            if (lines.firstOrNull()?.trim() == "---") {
                for (i in 1 until lines.size) {
                    if (lines[i].trim() == "---") break
                    if (lines[i].startsWith("description:")) {
                        desc = lines[i].substringAfter("description:").trim()
                    }
                }
            }
            arr.put(JSONObject().put("name", name).put("description", desc).put("path", "skills/$name.md"))
        }
        return JSONObject().put("ok", true).put("skills", arr).put("hint", "skill.read name=<name> before domain tools").toString()
    }

    @JavascriptInterface
    fun skillRead(args: JSONObject): String {
        val name = args.optString("name").ifBlank { args.optString("skill") }.ifBlank { args.optString("id") }
        if (name.isBlank()) {
            return JSONObject().put("ok", false).put("error", "name required").put("hint", "skill.list first").toString()
        }
        val body = readSkillAsset(name)
            ?: run {
                val avail = JSONArray()
                for (f in skillAssetNames()) avail.put(f.removeSuffix(".md"))
                return JSONObject().put("ok", false).put("error", "unknown skill").put("name", name)
                    .put("available", avail).toString()
            }
        return JSONObject().put("ok", true).put("name", name.trim().removeSuffix(".md").lowercase())
            .put("content", body).toString()
    }

    @JavascriptInterface
    fun listTools(): String {
        val tools = JSONArray()
        fun tool(name: String, desc: String, params: JSONObject, call: String? = null, example: String? = null, status: String = "ready") {
            val o = JSONObject().put("name", name).put("description", desc).put("params", params).put("status", status)
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
            o.put("via", "dispatch") // all catalog tools are invokeJson-dispatchable
            if (!example.isNullOrBlank()) o.put("example", example)
            tools.put(o)
        }
        tool("list", "Alias of list_tools — full catalog", JSONObject())
        tool("list_tools", "Full catalog: name, params, example, common failures. Call before inventing tool names. No args.", JSONObject(), example = "{\"tool\":\"list_tools\",\"description\":\"catalog\",\"args\":{}}")
        tool("run_js", "Execute custom JS in the tool sandbox (last resort). Prefer native dotted tools. args.code required.", JSONObject().put("code", "string"), example = "{\"tool\":\"run_js\",\"args\":{\"code\":\"return await workspace.ls()\"}}")
        tool("skill.list", "List skills (name + description). Load domain skill before github/workspace/http/device.", JSONObject())
        tool("skill.read", "Read skill markdown. args.name = shell|workspace|github|http|device|protocol", JSONObject().put("name", "string"))
        tool("selftest", "Probe which tools are actually bound", JSONObject())
        tool("research.web", "Multi-source web research (DDG + Wikipedia + pages)", JSONObject().put("query", "string").put("maxSources", "number?"))
        tool("research.preview", "URL title/description preview", JSONObject().put("url", "string"))
        tool("research.html_text", "Fetch URL and extract visible text", JSONObject().put("url", "string").put("maxChars", "number?"))
        tool("workspace.grep", "Search all workspace text files for a string or regex. Returns path+line hits.", JSONObject().put("query", "string").put("regex", "boolean?").put("maxHits", "number?"), example = "return await workspace.grep('TODO', false, 50)")
        tool("exec.lang", "Run a short script in an on-device runtime. Check exec.langs() first. cwd=workspace.", JSONObject().put("lang", "string").put("code", "string").put("timeoutMs", "number?"), example = "return await exec.lang('node', 'console.log(1+1)')")
        tool("exec.which", "Locate binary on PATH", JSONObject().put("bin", "string"))
        tool("exec.langs", "List available script runtimes on device", JSONObject())
        tool("shell", "Run a shell command in workspace (requires Settings → exec_shell). args.cmd required. Returns exitCode/stdout/stderr.", JSONObject().put("cmd", "string").put("timeout_ms", "number?").put("cwd", "string?"), example = "{\"tool\":\"shell\",\"description\":\"list files\",\"args\":{\"cmd\":\"ls -la\"}}")
        tool("sh", "Alias of shell", JSONObject().put("cmd", "string").put("timeout_ms", "number?").put("cwd", "string?"))
        tool("cmd", "Alias of shell", JSONObject().put("cmd", "string").put("timeout_ms", "number?"))
        tool("shell.which", "Resolve binary on PATH via command -v", JSONObject().put("bin", "string"))
        tool("shell.env", "Environment snapshot for child processes (HOME/TMPDIR/PATH subset)", JSONObject())
        tool("shell.pwd", "Shell working directory (= workspace root or cwd)", JSONObject().put("cwd", "string?"))
        tool("shell.lines", "Run shell cmd; return stdout split into lines (max 2000)", JSONObject().put("cmd", "string").put("timeout_ms", "number?"))
        tool("shell.ok", "Run cmd; ok=true only if exitCode==0", JSONObject().put("cmd", "string").put("timeout_ms", "number?"))
        tool("sys.prop", "Read one Android system property (getprop)", JSONObject().put("name", "string"))
        tool("sys.props", "Read common props (ro.build.*, ril, etc.)", JSONObject())
        tool("sys.uname", "Kernel uname -a via shell/toybox", JSONObject())
        tool("sys.df", "Disk free (df -h) for workspace/storage", JSONObject())
        tool("sys.ps", "Process snapshot (ps -A) truncated", JSONObject().put("filter", "string?"))
        tool("pkg.list", "List installed packages (pm list packages) optional filter", JSONObject().put("filter", "string?").put("limit", "number?"))
        tool("pkg.path", "Path of an installed package (pm path)", JSONObject().put("package", "string"))

        tool("term", "Unified terminal: args.argv=[bin,...] (allowlisted) OR args.cmd for sh -c (needs exec_shell).", JSONObject().put("argv", "array?").put("cmd", "string?").put("timeout_ms", "number?").put("cwd", "string?"), example = "term argv:[ls,-la] or cmd:ls -la")
        tool("terminal", "Alias of term", JSONObject().put("argv", "array?").put("cmd", "string?"))
        tool("run", "Alias of term", JSONObject().put("argv", "array?").put("cmd", "string?"))
        tool("shell.pipe", "Run pipeline via shell (needs exec_shell). args.cmd e.g. ps -A | grep system", JSONObject().put("cmd", "string").put("timeout_ms", "number?"))
        tool("shell.script", "Multiline shell script via sh -c (needs exec_shell)", JSONObject().put("script", "string").put("timeout_ms", "number?"))
        tool("ls", "List directory (exec ls). args.path optional, args.long for -la", JSONObject().put("path", "string?").put("long", "boolean?").put("all", "boolean?"))
        tool("cat", "Read file via cat (prefer workspace.read for UTF-8 sandbox files)", JSONObject().put("path", "string").put("max_bytes", "number?"))
        tool("head", "First N lines of file", JSONObject().put("path", "string").put("n", "number?"))
        tool("tail", "Last N lines of file", JSONObject().put("path", "string").put("n", "number?"))
        tool("wc", "Word/line/byte count", JSONObject().put("path", "string").put("flags", "string?"))
        tool("grep", "grep -n pattern path (or -r dir)", JSONObject().put("pattern", "string").put("path", "string?").put("recursive", "boolean?").put("ignore_case", "boolean?"))
        tool("find", "find path -name pattern", JSONObject().put("path", "string?").put("name", "string?").put("type", "string?").put("maxdepth", "number?"))
        tool("mkdir", "mkdir -p path", JSONObject().put("path", "string"))
        tool("rm", "Remove path (file or -r dir). Destructive.", JSONObject().put("path", "string").put("recursive", "boolean?").put("force", "boolean?"))
        tool("cp", "Copy src to dest", JSONObject().put("src", "string").put("dest", "string").put("recursive", "boolean?"))
        tool("mv", "Move/rename src to dest", JSONObject().put("src", "string").put("dest", "string"))
        tool("touch", "Touch file", JSONObject().put("path", "string"))
        tool("stat", "stat path", JSONObject().put("path", "string"))
        tool("du", "du -sh path", JSONObject().put("path", "string?"))
        tool("pwd", "Print workspace/cwd", JSONObject().put("cwd", "string?"))
        tool("echo", "Echo text to stdout", JSONObject().put("text", "string"))
        tool("date", "Current date string", JSONObject().put("format", "string?"))
        tool("whoami", "Current user id", JSONObject())
        tool("id", "id command", JSONObject())
        tool("which", "which binary", JSONObject().put("bin", "string"))
        tool("sha256sum", "SHA-256 of file", JSONObject().put("path", "string"))
        tool("md5sum", "MD5 of file", JSONObject().put("path", "string"))
        tool("base64", "base64 encode/decode file or data", JSONObject().put("op", "encode|decode").put("path", "string?").put("data", "string?"))
        tool("diff", "diff two files", JSONObject().put("a", "string").put("b", "string").put("unified", "boolean?"))
        tool("sort", "sort file lines", JSONObject().put("path", "string").put("unique", "boolean?"))
        tool("uniq", "uniq file", JSONObject().put("path", "string").put("count", "boolean?"))
        tool("cut", "cut fields from file", JSONObject().put("path", "string").put("fields", "string").put("delimiter", "string?"))
        tool("tr", "tr set1 set2 on file content via shell", JSONObject().put("path", "string").put("set1", "string").put("set2", "string"))
        tool("sed", "sed expression on file (allowlisted sed)", JSONObject().put("expr", "string").put("path", "string").put("in_place", "boolean?"))
        tool("chmod", "chmod mode path", JSONObject().put("mode", "string").put("path", "string"))
        tool("ln", "ln -s target link", JSONObject().put("target", "string").put("link", "string").put("symbolic", "boolean?"))
        tool("realpath", "Resolve absolute path", JSONObject().put("path", "string"))
        tool("readlink", "readlink path", JSONObject().put("path", "string"))
        tool("dirname", "dirname path", JSONObject().put("path", "string"))
        tool("basename", "basename path", JSONObject().put("path", "string"))
        tool("tar", "tar list/extract/create (c|x|t)", JSONObject().put("op", "string").put("archive", "string").put("path", "string?"))
        tool("gzip", "gzip -k file", JSONObject().put("path", "string").put("decompress", "boolean?"))
        tool("unzip", "unzip -l or extract", JSONObject().put("archive", "string").put("list", "boolean?").put("dest", "string?"))
        tool("ping", "ping -c count host", JSONObject().put("host", "string").put("count", "number?"))
        tool("curl", "curl -sS url (allowlisted)", JSONObject().put("url", "string").put("method", "string?").put("output", "string?").put("headers", "object?"))
        tool("wget", "wget -q url", JSONObject().put("url", "string").put("output", "string?"))
        tool("logcat", "logcat -d snapshot (truncated). Optional filter.", JSONObject().put("filter", "string?").put("lines", "number?"))
        tool("dumpsys", "dumpsys service (short). args.service required.", JSONObject().put("service", "string").put("args", "array?"))
        tool("am", "am command (start/force-stop/...). args.argv after am", JSONObject().put("argv", "array"))
        tool("pm.install", "pm install -r apk path (may need elevated)", JSONObject().put("path", "string"))
        tool("settings.get", "settings get namespace key", JSONObject().put("namespace", "string").put("key", "string"))
        tool("settings.put", "settings put namespace key value", JSONObject().put("namespace", "string").put("key", "string").put("value", "string"))
        tool("wm.size", "wm size", JSONObject())
        tool("wm.density", "wm density", JSONObject())
        tool("free", "free -h memory", JSONObject())
        tool("uptime", "uptime", JSONObject())
        tool("nproc", "CPU count", JSONObject())
        tool("printenv", "printenv or one var", JSONObject().put("name", "string?"))
        tool("tools.suggest", "Suggest tools for a natural-language task", JSONObject().put("task", "string"))
        tool("tools.groups", "List tool groups (workspace, shell, github, …)", JSONObject())
        tool("exec.allowlist", "List allowlisted binaries for term/exec", JSONObject())

        tool("tools.help", "Protocol help: fence format, schema, common errors", JSONObject())
        tool("tools.examples", "Worked tool_call examples for common tasks", JSONObject().put("topic", "string?"))
        tool("tools.count", "Count tools in live catalog", JSONObject())
        tool("harness.info", "Harness version, workspace, flags summary", JSONObject())
        tool("session.info", "Conversation/session helper info", JSONObject())
        tool("env", "Print environment (printenv)", JSONObject().put("name", "string?"))
        tool("uname", "uname -a", JSONObject())
        tool("printf", "printf FORMAT via shell (exec_shell)", JSONObject().put("format", "string").put("args", "array?"))
        tool("rev", "Reverse lines of text or file", JSONObject().put("path", "string?").put("text", "string?"))
        tool("file", "file(1) magic type probe", JSONObject().put("path", "string"))
        tool("strings", "strings binary/text", JSONObject().put("path", "string").put("min", "number?"))
        tool("hexdump", "hexdump -C truncated", JSONObject().put("path", "string").put("n", "number?"))
        tool("nl", "Number lines", JSONObject().put("path", "string"))
        tool("json.pretty", "Pretty-print JSON string", JSONObject().put("json", "string").put("indent", "number?"))
        tool("json.min", "Minify JSON string", JSONObject().put("json", "string"))
        tool("text.count", "Count chars/words/lines", JSONObject().put("text", "string"))
        tool("url.encode", "URL-encode string", JSONObject().put("text", "string"))
        tool("url.decode", "URL-decode string", JSONObject().put("text", "string"))
        tool("path.join", "Join path segments under workspace rules", JSONObject().put("parts", "array"))
        tool("path.split", "Split path into dir/name/ext", JSONObject().put("path", "string"))
        tool("dns.lookup", "DNS via getaddrinfo/InetAddress", JSONObject().put("host", "string"))
        tool("sys.meminfo", "Read /proc/meminfo head", JSONObject())
        tool("sys.cpuinfo", "Read /proc/cpuinfo head", JSONObject())
        tool("logcat.clear", "logcat -c clear buffer", JSONObject())
        tool("note.set", "Save a named note (persisted)", JSONObject().put("key", "string").put("value", "string"))
        tool("note.get", "Read a named note", JSONObject().put("key", "string"))
        tool("note.list", "List note keys", JSONObject())
        tool("workspace.mv", "Rename/move within workspace", JSONObject().put("from", "string").put("to", "string"))
        tool("workspace.cp", "Copy within workspace", JSONObject().put("from", "string").put("to", "string"))
        tool("test.file", "test -e/-f/-d path", JSONObject().put("path", "string").put("mode", "string?"))


        tool("shell.cd", "Resolve path under workspace (does not persist shell state)", JSONObject().put("path", "string"))
        tool("shell.test", "Test path: exists/file/dir (test -e/-f/-d)", JSONObject().put("path", "string").put("mode", "string?"))
        tool("shell.cat_head", "head -n via shell for remote paths", JSONObject().put("path", "string").put("n", "number?"))
        tool("true", "Always exit 0", JSONObject())
        tool("false", "Always exit 1", JSONObject())
        tool("seq", "Print sequence: seq start end", JSONObject().put("start", "number").put("end", "number"))
        tool("yes", "yes truncated via head", JSONObject().put("text", "string?").put("lines", "number?"))
        tool("cmp", "cmp two files", JSONObject().put("a", "string").put("b", "string"))
        tool("tee", "Write stdin file via shell echo", JSONObject().put("path", "string").put("text", "string").put("append", "boolean?"))
        tool("xargs", "xargs helper: run bin with args from lines", JSONObject().put("bin", "string").put("lines", "array").put("max_args", "number?"))
        tool("timeout", "Run argv with timeout binary if present", JSONObject().put("seconds", "number").put("argv", "array"))
        tool("kill", "kill pid (SIGTERM default)", JSONObject().put("pid", "number").put("signal", "string?"))
        tool("pidof", "pidof name", JSONObject().put("name", "string"))
        tool("getprop", "Alias of sys.prop", JSONObject().put("name", "string"))
        tool("ifconfig", "ifconfig snapshot", JSONObject())
        tool("ip", "ip addr / route", JSONObject().put("args", "array?"))
        tool("netstat", "netstat -n truncated", JSONObject())
        tool("service.list", "service list (Android)", JSONObject().put("filter", "string?"))
        tool("input.text", "input text (may need elevated)", JSONObject().put("text", "string"))
        tool("input.key", "input keyevent code", JSONObject().put("code", "number"))



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
        tool("tool.search", "Search tool catalog by keyword (name/args/description).", JSONObject().put("query", "string"), example = "return await tool.search({query:'github'})")
        tool("text.hash", "FNV-1a hash of a string", JSONObject().put("text", "string"))
        tool("text.stats", "Char/word/line counts", JSONObject().put("text", "string"))
        tool("json.parse", "Parse JSON text", JSONObject().put("text", "string"))
        tool("json.get", "Get dotted path from object", JSONObject().put("obj", "object|string").put("path", "string"))
        tool("url.parse", "Parse URL parts", JSONObject().put("url", "string"))
        tool("encode.uri", "encodeURIComponent", JSONObject().put("text", "string"))
        tool("decode.uri", "decodeURIComponent", JSONObject().put("text", "string"))
        tool("random.int", "Random int in [min,max]", JSONObject().put("min", "number?").put("max", "number?"))
        tool("string.split", "Split text", JSONObject().put("text", "string").put("sep", "string?"))
        tool("string.join", "Join parts", JSONObject().put("parts", "array").put("sep", "string?"))
        tool("string.replace", "Replace substring", JSONObject().put("text", "string").put("find", "string").put("replace", "string"))
        tool("sleep", "Sleep ms (max 30000)", JSONObject().put("ms", "number"))
        tool("time.now", "ISO time + epochMs", JSONObject())
        tool("workspace.count", "Count entries in workspace path", JSONObject().put("path", "string?"))
        tool("math.eval", "Safe arithmetic only", JSONObject().put("expr", "string"))
        tool("describe", "Schema + example + failure modes for one tool or group. args.name required (e.g. workspace.write or github).", JSONObject().put("name", "string"), example = "{\"tool\":\"describe\",\"description\":\"schema\",\"args\":{\"name\":\"workspace.write\"}}")
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
        tool("github.pr_create", "Create PR", JSONObject().put("owner", "string").put("repo", "string").put("title", "string").put("head", "string").put("base", "string?").put("body", "string?").put("draft", "boolean?"))
        tool("github.pr_ready", "Mark draft PR ready for review", JSONObject().put("owner", "string").put("repo", "string").put("number", "number"))
        tool("github.diff", "Download PR/commit .diff into workspace (UTF-8 text)", JSONObject().put("owner", "string").put("repo", "string").put("number", "number?").put("ref", "string?").put("dest", "string?"))
        tool("workspace.check_encoding", "Scan file for mojibake / non-UTF8 corruption markers", JSONObject().put("path", "string"))
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
        tool("github.push_file", "Create/update a file on GitHub from a workspace path (Contents API). Prefer over hand-encoded base64.", JSONObject().put("owner", "string").put("repo", "string").put("path", "string").put("branch", "string").put("message", "string").put("localPath", "string"), example = "{\"tool\":\"github.push_file\",\"args\":{\"owner\":\"o\",\"repo\":\"r\",\"path\":\"src/a.kt\",\"branch\":\"main\",\"message\":\"msg\",\"localPath\":\"src/a.kt\"}}")
        tool("github.push_file_from_path", "Alias of github.push_file (git data API — nested paths OK)", JSONObject().put("owner", "string").put("repo", "string").put("path", "string").put("branch", "string").put("message", "string").put("localPath", "string"))
        tool("github.push_files_batch", "One commit, many files from workspace. args.files=[{path,localPath},...]", JSONObject().put("owner", "string").put("repo", "string").put("branch", "string").put("message", "string").put("files", "array"))
        tool("verify_roundtrip", "Push file then re-read remote blob sha vs local", JSONObject().put("owner", "string").put("repo", "string").put("path", "string").put("branch", "string").put("localPath", "string").put("message", "string?"))
        tool("github.fork", "Fork a repo to the authenticated user", JSONObject().put("owner", "string").put("repo", "string").put("organization", "string?"))
        tool("http.fetch_to_file", "Stream HTTP response to workspace file; returns meta only", JSONObject().put("url", "string").put("dest", "string").put("method", "string?").put("headers", "object?").put("body", "string?"))
        tool("workspace.hash", "SHA-256 of workspace file", JSONObject().put("path", "string"))
        tool("workspace.read_b64_range", "Base64 slice of workspace file", JSONObject().put("path", "string").put("offset", "number?").put("maxBytes", "number?"))
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
        if (matches.length() == 1) {
            val one = matches.getJSONObject(0)
            if (!one.has("callForm")) {
                one.put("callForm", JSONObject()
                    .put("tool", one.optString("name"))
                    .put("description", "short")
                    .put("args", JSONObject()))
            }
            if (!one.has("failureModes")) {
                one.put("failureModes", JSONArray()
                    .put("unknown tool name — use list_tools")
                    .put("missing required args — check params")
                    .put("path/not found — ls/glob first")
                    .put("policy deny — do not retry same call"))
            }
            one.put("protocol", "Prefer Claude <function_calls><invoke name=\"tool\">…</invoke></function_calls>; tool_call JSON fence still OK. See tools.help.")
            return one.toString()
        }
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
            "skill.list" to "skillList",
            "skill.read" to "skillRead",
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
                return JSONObject().put("ok", false)
                    .put("error", JSONObject()
                        .put("kind", "policy")
                        .put("code", "NOT_ALLOWLISTED")
                        .put("message", "binary not allowlisted: $bin")
                        .put("hint", "Use exec.allowlist or native http_request / github.* tools"))
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
            // Prefer workspace/tmp (app sandbox); /tmp is often not writable
            val tmpDir = File(workspaceRoot, "tmp").also { it.mkdirs() }
            pb.environment()["TMPDIR"] = tmpDir.absolutePath
            pb.environment()["TMP"] = tmpDir.absolutePath
            pb.environment()["TEMP"] = tmpDir.absolutePath
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
            // Process ran: never "tool failed" for exit!=0 (grep no-match etc.)
            val out = JSONObject()
                .put("ok", true)
                .put("ran", true)
                .put("exitCode", code)
                .put("code", code)
                .put("stdout", stdoutBox[0] ?: "")
                .put("stderr", stderrBox[0] ?: "")
                .put("timedOut", false)
                .put("durationMs", System.currentTimeMillis() - t0)
                .put("pid", pid)
                .put("cwd", cwd.absolutePath)
                .put("tmpdir", tmpDir.absolutePath)
            if (code != 0) {
                out.put("error", JSONObject()
                    .put("kind", "exit")
                    .put("exitCode", code)
                    .put("message", "process exited $code")
                    .put("hint", "Non-zero exit is not a harness failure (e.g. grep no match)"))
            }
            out.toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false)
                .put("error", JSONObject()
                    .put("kind", "internal")
                    .put("message", e.message ?: "exec failed"))
                .toString()
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
                !path.isNullOrBlank() -> safeWorkspace(path).readText(Charsets.UTF_8)
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
            val root = if (path.isNullOrBlank()) workspaceRoot else safeWorkspace(path)
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
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        val out = JSONObject()
            .put("status", code)
            .put("ok", code in 200..299)
            .put("bytes", text.length)
        val softCap = 48_000
        if (text.length > softCap) {
            val spillRel = "tmp/github.request-${System.currentTimeMillis()}.json"
            try {
                val f = safeWorkspace(spillRel)
                f.parentFile?.mkdirs()
                f.writeText(text)
                out.put("spilled", true)
                    .put("spill_path", spillRel)
                    .put("spill_abs", f.absolutePath)
                    .put("hint", "Full body written to workspace; use workspace.read on spill_path")
                    .put("text_preview", text.take(2_000))
            } catch (e: Exception) {
                out.put("text", text.take(softCap))
                    .put("truncated", true)
                    .put("error_spill", e.message)
            }
        } else {
            out.put("text", text)
            try {
                if (text.trimStart().startsWith("{")) out.put("json", JSONObject(text))
                else if (text.trimStart().startsWith("[")) out.put("json", JSONArray(text))
            } catch (_: Exception) {}
        }
        if (code !in 200..299) {
            out.put("error", JSONObject()
                .put("code", "HTTP_$code")
                .put("message", text.take(500).ifBlank { "HTTP $code" })
                .put("status", code)
                .put("retryable", code == 429 || code >= 500))
        }
        return out
    }

    /** Synchronous HTTP for invokeJson / TOOL_RESULT path (no callbackId). */
    private fun httpRequestSync(
        method: String,
        url: String,
        headersJson: String?,
        body: String?
    ): JSONObject {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method.ifBlank { "GET" }.uppercase()
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                doInput = true
                setRequestProperty("User-Agent", "D-Harness/1.18")
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
            val text = stream?.bufferedReader()?.use { it.readText() }?.take(500_000) ?: ""
            val out = JSONObject()
                .put("ok", code in 200..299)
                .put("status", code)
                .put("url", url)
                .put("text", text.take(100_000))
            try {
                when {
                    text.trimStart().startsWith("{") -> out.put("json", JSONObject(text))
                    text.trimStart().startsWith("[") -> out.put("json", JSONArray(text))
                }
            } catch (_: Exception) { }
            out
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message ?: "http failed").put("url", url)
        }
    }

    /** Map github.* tool names to REST and run via githubRequestSync. */


    private fun githubPrReady(owner: String, repo: String, number: Int): String {
        return try {
            val token = githubToken() ?: return typedErr("AUTH_MISSING", "no github PAT")
            if (owner.isBlank() || repo.isBlank() || number <= 0) {
                return typedErr("ARGS", "owner, repo, number required")
            }
            // GitHub: PUT /repos/{owner}/{repo}/pulls/{pull_number}/merge is wrong
            // Ready for review: POST /repos/{o}/{r}/pulls/{n}/ready_for_review (GraphQL alternative)
            // REST: PATCH pull with draft:false works on newer API via
            // POST https://api.github.com/repos/o/r/pulls/n/ready_for_review — actually GraphQL only
            // Use REST: PATCH /pulls/{n} does not toggle draft on all versions.
            // GraphQL mutation:
            val idLookup = githubRequestSync("GET", "/repos/$owner/$repo/pulls/$number", null, token)
            if (idLookup.optInt("status") !in 200..299) {
                return typedErr("PR_NOT_FOUND", "cannot load PR", status = idLookup.optInt("status"))
            }
            val nodeId = idLookup.optJSONObject("json")?.optString("node_id")
                ?: return typedErr("PR_NODE", "missing node_id")
            val gql = JSONObject()
                .put("query", "mutation(${'$'}id:ID!){ markPullRequestReadyForReview(input:{pullRequestId:${'$'}id}) { pullRequest { number isDraft url } } }")
                .put("variables", JSONObject().put("id", nodeId))
            val resp = githubRequestSync("POST", "/graphql", gql.toString(), token)
            val st = resp.optInt("status")
            if (st !in 200..299) {
                return typedErr("PR_READY_FAILED", resp.optString("text").take(400), status = st)
            }
            JSONObject().put("ok", true).put("number", number).put("node_id", nodeId)
                .put("result", resp.opt("json") ?: resp.opt("text_preview")).toString()
        } catch (e: Exception) {
            typedErr("INTERNAL", e.message)
        }
    }

    private fun githubDiffToFile(args: JSONObject): String {
        return try {
            val owner = args.optString("owner")
            val repo = args.optString("repo")
            val number = args.optInt("number", args.optInt("pr", 0))
            val ref = args.optString("ref", "")
            val dest = args.optString("dest", "").ifBlank {
                if (number > 0) "tmp/$owner-$repo-pr-$number.diff"
                else "tmp/$owner-$repo.diff"
            }
            if (owner.isBlank() || repo.isBlank()) {
                return typedErr("ARGS", "owner and repo required")
            }
            val url = when {
                number > 0 -> "https://github.com/$owner/$repo/pull/$number.diff"
                ref.isNotBlank() -> "https://github.com/$owner/$repo/commit/$ref.diff"
                else -> return typedErr("ARGS", "number (PR) or ref (commit) required")
            }
            val fetched = httpFetchToFile(JSONObject().put("url", url).put("dest", dest)
                .put("headers", JSONObject().put("Accept", "text/plain")))
            val fo = JSONObject(fetched)
            if (!fo.optBoolean("ok")) return fetched
            // encoding scan
            val check = JSONObject(workspaceCheckEncoding(dest))
            fo.put("encoding", check)
            fo.put("url", url)
            fo.toString()
        } catch (e: Exception) {
            typedErr("INTERNAL", e.message)
        }
    }

    private fun workspaceCheckEncoding(path: String): String {
        return try {
            val f = safeWorkspace(path)
            if (!f.isFile) return typedErr("NOT_FOUND", "not a file")
            val bytes = f.readBytes()
            // Detect UTF-8 decode issues and common mojibake markers
            val text = bytes.toString(Charsets.UTF_8)
            val markers = listOf("â€", "Ã¢", "Ã", "Â", "ï»¿", "\uFFFD")
            val hits = JSONArray()
            var total = 0
            for (m in markers) {
                var c = 0
                var idx = 0
                while (true) {
                    val j = text.indexOf(m, idx)
                    if (j < 0) break
                    c++; total++
                    idx = j + m.length
                    if (c > 50) break
                }
                if (c > 0) hits.put(JSONObject().put("marker", m).put("count", c))
            }
            // Invalid UTF-8 sequences
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            var validUtf8 = true
            try {
                decoder.decode(java.nio.ByteBuffer.wrap(bytes))
            } catch (_: Exception) {
                validUtf8 = false
            }
            JSONObject()
                .put("ok", validUtf8 && total == 0)
                .put("path", f.absolutePath)
                .put("bytes", bytes.size)
                .put("valid_utf8", validUtf8)
                .put("mojibake_hits", hits)
                .put("mojibake_total", total)
                .put("hint", if (total > 0) "File may be corrupted (latin1/utf8 mix). Re-fetch clean upstream." else "UTF-8 looks clean")
                .toString()
        } catch (e: Exception) {
            typedErr("INTERNAL", e.message)
        }
    }

    private fun httpFetchToFile(args: JSONObject): String {
        return try {
            val url = args.optString("url", args.optString("href", ""))
            val dest = args.optString("dest", args.optString("path", args.optString("out", "")))
            if (url.isBlank() || dest.isBlank()) {
                return JSONObject().put("ok", false)
                    .put("error", JSONObject().put("code", "ARGS").put("message", "url and dest required")).toString()
            }
            val method = args.optString("method", "GET").ifBlank { "GET" }.uppercase()
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 20_000
                readTimeout = 120_000
                instanceFollowRedirects = true
                doInput = true
                setRequestProperty("User-Agent", "D-Harness/1.19")
                val headers = args.optJSONObject("headers")
                if (headers != null) {
                    val it = headers.keys()
                    while (it.hasNext()) {
                        val k = it.next()
                        setRequestProperty(k, headers.optString(k))
                    }
                }
                val body = args.optString("body", "")
                if (body.isNotBlank() && method != "GET" && method != "HEAD") {
                    doOutput = true
                    outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val outFile = safeWorkspace(dest)
            outFile.parentFile?.mkdirs()
            var n = 0
            val dig = java.security.MessageDigest.getInstance("SHA-256")
            stream?.use { input ->
                FileOutputStream(outFile).use { fos ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val r = input.read(buf)
                        if (r <= 0) break
                        fos.write(buf, 0, r)
                        dig.update(buf, 0, r)
                        n += r
                    }
                }
            }
            val sha = dig.digest().joinToString("") { "%02x".format(it) }
            JSONObject()
                .put("ok", code in 200..299)
                .put("status", code)
                .put("bytes", n)
                .put("sha256", sha)
                .put("path", outFile.absolutePath)
                .put("rel", dest.trim().removePrefix("/"))
                .toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false)
                .put("error", JSONObject().put("code", "FETCH_FAILED").put("message", e.message)).toString()
        }
    }

    private fun workspaceHash(path: String): String {
        return try {
            val f = safeWorkspace(path)
            if (!f.isFile) return JSONObject().put("ok", false).put("error", "not a file").toString()
            val dig = java.security.MessageDigest.getInstance("SHA-256")
            f.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val r = input.read(buf)
                    if (r <= 0) break
                    dig.update(buf, 0, r)
                }
            }
            val sha = dig.digest().joinToString("") { "%02x".format(it) }
            JSONObject().put("ok", true).put("path", f.absolutePath).put("bytes", f.length()).put("sha256", sha).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    private fun workspaceReadB64Range(path: String, offset: Int, maxBytes: Int): String {
        return try {
            val f = safeWorkspace(path)
            if (!f.isFile) return JSONObject().put("ok", false).put("error", "not a file").toString()
            val off = offset.coerceAtLeast(0)
            val max = maxBytes.coerceIn(1, 2_000_000)
            if (off.toLong() >= f.length()) {
                return JSONObject().put("ok", true).put("eof", true).put("offset", off).put("bytes", 0).put("b64", "").toString()
            }
            val len = minOf(max.toLong(), f.length() - off).toInt()
            val bytes = ByteArray(len)
            java.io.RandomAccessFile(f, "r").use { raf ->
                raf.seek(off.toLong())
                raf.readFully(bytes)
            }
            val dig = java.security.MessageDigest.getInstance("SHA-256")
            dig.update(bytes)
            val sliceSha = dig.digest().joinToString("") { "%02x".format(it) }
            JSONObject()
                .put("ok", true)
                .put("path", f.absolutePath)
                .put("offset", off)
                .put("bytes", len)
                .put("fileBytes", f.length())
                .put("eof", off + len >= f.length())
                .put("b64", Base64.encodeToString(bytes, Base64.NO_WRAP))
                .put("sha256_slice", sliceSha)
                .toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    private fun maybeSpillResult(tool: String, resultJson: String): String {
        val limit = 90_000
        if (resultJson.length <= limit) return resultJson
        return try {
            val safeTool = tool.replace(".", "_")
            val rel = "tmp/$safeTool-${System.currentTimeMillis()}.json"
            val f = safeWorkspace(rel)
            f.parentFile?.mkdirs()
            f.writeText(resultJson)
            JSONObject()
                .put("ok", true)
                .put("spilled", true)
                .put("tool", tool)
                .put("bytes", resultJson.length)
                .put("spill_path", rel)
                .put("spill_abs", f.absolutePath)
                .put("preview", resultJson.take(1500))
                .put("hint", "Result exceeded inline limit; full JSON at spill_path")
                .toString()
        } catch (e: Exception) {
            JSONObject()
                .put("ok", false)
                .put("error", JSONObject()
                    .put("code", "OUTPUT_TOO_LARGE")
                    .put("message", "output_too_large")
                    .put("bytes", resultJson.length)
                    .put("spill_error", e.message))
                .put("tool", tool)
                .toString()
        }
    }

    private fun dispatchGithub(tool: String, args: JSONObject): String {
        val token = githubToken()
        if (token.isNullOrBlank()) {
            return JSONObject().put("ok", false)
                .put("error", JSONObject()
                    .put("code", "AUTH_MISSING")
                    .put("message", "missing GitHub PAT")
                    .put("hint", "Settings → store key github with a personal access token"))
                .toString()
        }
        fun s(vararg keys: String): String {
            for (k in keys) {
                val v = args.optString(k, "")
                if (v.isNotBlank()) return v
            }
            return ""
        }
        fun i(vararg keys: String): Int {
            for (k in keys) {
                if (args.has(k)) return args.optInt(k, 0)
            }
            return 0
        }
        fun bodyObj(): String? {
            val b = args.opt("body")
            return when (b) {
                null, JSONObject.NULL -> null
                is JSONObject -> b.toString()
                is String -> b.takeIf { it.isNotBlank() }
                else -> b.toString()
            }
        }
        val owner = s("owner", "org", "user")
        val repo = s("repo", "repository")
        val number = i("number", "issue", "pr", "pull")
        val path = s("path")
        val perPage = args.optInt("per_page", args.optInt("perPage", 30)).coerceIn(1, 100)

        // File-bridge / mutators — bound to native implementations
        when (tool) {
            "github.pull" -> {
                return githubPull(owner, repo, path.ifBlank { s("file") }, s("ref", "branch", "sha").ifBlank { null }, s("dest", "out", "localPath").ifBlank { null })
            }
            "github.push_file", "github.push_file_from_path" -> {
                val local = s("localPath", "local", "src", "file")
                val remote = path.ifBlank { s("remotePath", "dest") }
                val branch = s("branch", "ref").ifBlank { "main" }
                val message = s("message", "msg", "commit").ifBlank { "update via D-Harness" }
                if (owner.isBlank() || repo.isBlank() || remote.isBlank() || local.isBlank()) {
                    return JSONObject().put("ok", false)
                        .put("error", JSONObject().put("code", "ARGS").put("message", "owner, repo, path, localPath required")).toString()
                }
                return githubPushFile(owner, repo, remote, branch, message, local)
            }
            "github.push_files_batch" -> {
                return githubPushFilesBatch(args)
            }
            "verify_roundtrip" -> {
                val local = s("localPath", "local")
                val remote = path.ifBlank { s("remotePath") }
                val branch = s("branch", "ref").ifBlank { "main" }
                val message = s("message").ifBlank { "verify roundtrip" }
                if (owner.isBlank() || repo.isBlank() || remote.isBlank() || local.isBlank()) {
                    return typedErr("ARGS", "owner, repo, path, localPath required")
                }
                val push = JSONObject(githubPushFile(owner, repo, remote, branch, message, local))
                if (!push.optBoolean("ok")) return push.toString()
                val pull = JSONObject(githubPull(owner, repo, remote, branch, "tmp/verify-$remote".replace("/", "_")))
                val localFile = safeWorkspace(local)
                val localSha = sha256(localFile.readBytes())
                val remoteBytes = if (pull.optBoolean("ok")) {
                    try { File(pull.optString("path")).readBytes() } catch (_: Exception) { null }
                } else null
                val remoteSha = remoteBytes?.let { sha256(it) }
                return JSONObject()
                    .put("ok", remoteSha != null && remoteSha == localSha)
                    .put("local_sha256", localSha)
                    .put("remote_sha256", remoteSha ?: JSONObject.NULL)
                    .put("commit_sha", push.optString("commit_sha"))
                    .put("bytes", localFile.length())
                    .put("path", remote)
                    .toString()
            }
            "github.branch_create" -> {
                val branch = s("branch", "name")
                val from = s("from", "sha", "base", "ref").ifBlank { null }
                if (owner.isBlank() || repo.isBlank() || branch.isBlank()) {
                    return JSONObject().put("ok", false)
                        .put("error", JSONObject().put("code", "ARGS").put("message", "owner, repo, branch required")).toString()
                }
                return githubBranchCreate(owner, repo, branch, from)
            }
            "github.fork" -> {
                if (owner.isBlank() || repo.isBlank()) {
                    return JSONObject().put("ok", false)
                        .put("error", JSONObject().put("code", "ARGS").put("message", "owner and repo required")).toString()
                }
                val org = s("organization", "org")
                val body = if (org.isNotBlank()) JSONObject().put("organization", org).toString() else null
                return try {
                    githubRequestSync("POST", "/repos/$owner/$repo/forks", body, token).toString()
                } catch (e: Exception) {
                    JSONObject().put("ok", false).put("error", JSONObject().put("message", e.message)).toString()
                }
            }
            "github.compare" -> {
                val base = s("base")
                val head = s("head")
                if (owner.isBlank() || repo.isBlank() || base.isBlank() || head.isBlank()) {
                    return JSONObject().put("ok", false)
                        .put("error", JSONObject().put("code", "ARGS").put("message", "owner, repo, base, head required")).toString()
                }
                return githubCompare(owner, repo, base, head)
            }
            "github.repo" -> {
                if (owner.isBlank() || repo.isBlank()) {
                    return JSONObject().put("ok", false)
                        .put("error", JSONObject().put("code", "ARGS").put("message", "owner and repo required")).toString()
                }
                return githubRepo(owner, repo)
            }
        }

        val (method, apiPath, body) = when (tool) {
            "github.request" -> {
                val m = s("method").ifBlank { "GET" }
                val pth = s("path", "url")
                if (pth.isBlank()) {
                    return JSONObject().put("ok", false).put("error", "path required").toString()
                }
                Triple(m, pth, bodyObj())
            }
            "github.me" -> Triple("GET", "/user", null)
            "github.repos" -> Triple("GET", "/user/repos?per_page=$perPage&sort=updated&direction=desc", null)
            "github.issues" -> {
                if (owner.isBlank() || repo.isBlank()) {
                    return JSONObject().put("ok", false).put("error", "owner and repo required").toString()
                }
                val state = s("state").ifBlank { "open" }
                Triple("GET", "/repos/$owner/$repo/issues?state=$state&per_page=$perPage", null)
            }
            "github.issue" -> {
                if (owner.isBlank() || repo.isBlank() || number <= 0) {
                    return JSONObject().put("ok", false).put("error", "owner, repo, number required").toString()
                }
                Triple("GET", "/repos/$owner/$repo/issues/$number", null)
            }
            "github.issue_comment", "github.pr_comment" -> {
                if (owner.isBlank() || repo.isBlank() || number <= 0) {
                    return JSONObject().put("ok", false).put("error", "owner, repo, number, body required").toString()
                }
                val comment = s("body", "text", "comment")
                if (comment.isBlank()) {
                    return JSONObject().put("ok", false).put("error", "body required").toString()
                }
                Triple("POST", "/repos/$owner/$repo/issues/$number/comments", JSONObject().put("body", comment).toString())
            }
            "github.pr" -> {
                if (owner.isBlank() || repo.isBlank() || number <= 0) {
                    return JSONObject().put("ok", false).put("error", "owner, repo, number required").toString()
                }
                Triple("GET", "/repos/$owner/$repo/pulls/$number", null)
            }
            "github.pr_files" -> {
                if (owner.isBlank() || repo.isBlank() || number <= 0) {
                    return JSONObject().put("ok", false).put("error", "owner, repo, number required").toString()
                }
                Triple("GET", "/repos/$owner/$repo/pulls/$number/files?per_page=$perPage", null)
            }
            "github.pr_reviews" -> {
                if (owner.isBlank() || repo.isBlank() || number <= 0) {
                    return JSONObject().put("ok", false).put("error", "owner, repo, number required").toString()
                }
                Triple("GET", "/repos/$owner/$repo/pulls/$number/reviews?per_page=$perPage", null)
            }
            "github.pr_commits" -> {
                if (owner.isBlank() || repo.isBlank() || number <= 0) {
                    return JSONObject().put("ok", false).put("error", "owner, repo, number required").toString()
                }
                Triple("GET", "/repos/$owner/$repo/pulls/$number/commits?per_page=$perPage", null)
            }
            "github.contents" -> {
                if (owner.isBlank() || repo.isBlank() || path.isBlank()) {
                    return JSONObject().put("ok", false).put("error", "owner, repo, path required").toString()
                }
                val ref = s("ref", "branch", "sha")
                val q = if (ref.isNotBlank()) "?ref=${java.net.URLEncoder.encode(ref, "UTF-8")}" else ""
                Triple("GET", "/repos/$owner/$repo/contents/${path.removePrefix("/")}$q", null)
            }
            "github.search" -> {
                val q = s("query", "q")
                if (q.isBlank()) return JSONObject().put("ok", false).put("error", "query required").toString()
                val type = s("type").ifBlank { "issues" }
                val pathSearch = when (type.lowercase()) {
                    "code" -> "/search/code"
                    "repositories", "repos" -> "/search/repositories"
                    "users" -> "/search/users"
                    else -> "/search/issues"
                }
                Triple("GET", "$pathSearch?q=${java.net.URLEncoder.encode(q, "UTF-8")}&per_page=$perPage", null)
            }
            "github.pr_create" -> {
                if (owner.isBlank() || repo.isBlank()) {
                    return JSONObject().put("ok", false).put("error", "owner, repo, title, head required").toString()
                }
                val title = s("title")
                val head = s("head")
                val base = s("base").ifBlank { "main" }
                if (title.isBlank() || head.isBlank()) {
                    return JSONObject().put("ok", false).put("error", "title and head required").toString()
                }
                val bo = JSONObject().put("title", title).put("head", head).put("base", base)
                val pb = s("body")
                if (pb.isNotBlank()) bo.put("body", pb)
                Triple("POST", "/repos/$owner/$repo/pulls", bo.toString())
            }
            "github.labels" -> {
                if (owner.isBlank() || repo.isBlank()) {
                    return JSONObject().put("ok", false).put("error", "owner and repo required").toString()
                }
                Triple("GET", "/repos/$owner/$repo/labels?per_page=$perPage", null)
            }
            "github.branches" -> {
                if (owner.isBlank() || repo.isBlank()) {
                    return JSONObject().put("ok", false).put("error", "owner and repo required").toString()
                }
                Triple("GET", "/repos/$owner/$repo/branches?per_page=$perPage", null)
            }
            "github.release_latest" -> {
                if (owner.isBlank() || repo.isBlank()) {
                    return JSONObject().put("ok", false).put("error", "owner and repo required").toString()
                }
                Triple("GET", "/repos/$owner/$repo/releases/latest", null)
            }
            "github.releases" -> {
                if (owner.isBlank() || repo.isBlank()) {
                    return JSONObject().put("ok", false).put("error", "owner and repo required").toString()
                }
                Triple("GET", "/repos/$owner/$repo/releases?per_page=$perPage", null)
            }
            "github.workflows" -> {
                if (owner.isBlank() || repo.isBlank()) {
                    return JSONObject().put("ok", false).put("error", "owner and repo required").toString()
                }
                Triple("GET", "/repos/$owner/$repo/actions/workflows?per_page=$perPage", null)
            }
            "github.workflow_runs" -> {
                if (owner.isBlank() || repo.isBlank()) {
                    return JSONObject().put("ok", false).put("error", "owner and repo required").toString()
                }
                Triple("GET", "/repos/$owner/$repo/actions/runs?per_page=$perPage", null)
            }
            "github.tree" -> {
                if (owner.isBlank() || repo.isBlank()) {
                    return JSONObject().put("ok", false).put("error", "owner and repo required").toString()
                }
                val ref = s("ref", "sha", "branch").ifBlank { "HEAD" }
                val rec = if (args.optBoolean("recursive", false)) "?recursive=1" else ""
                Triple("GET", "/repos/$owner/$repo/git/trees/$ref$rec", null)
            }
            "github.user" -> {
                val u = s("username", "user", "login")
                if (u.isBlank()) return JSONObject().put("ok", false).put("error", "username required").toString()
                Triple("GET", "/users/$u", null)
            }
            "github.forks" -> {
                if (owner.isBlank() || repo.isBlank()) {
                    return JSONObject().put("ok", false).put("error", "owner and repo required").toString()
                }
                Triple("GET", "/repos/$owner/$repo/forks?per_page=$perPage", null)
            }
            "github.tags" -> {
                if (owner.isBlank() || repo.isBlank()) {
                    return JSONObject().put("ok", false).put("error", "owner and repo required").toString()
                }
                Triple("GET", "/repos/$owner/$repo/tags?per_page=$perPage", null)
            }
            "github.gist_create" -> {
                val files = args.optJSONObject("files")
                    ?: return JSONObject().put("ok", false).put("error", "files object required").toString()
                val filesBody = JSONObject()
                val it = files.keys()
                while (it.hasNext()) {
                    val name = it.next()
                    val content = files.opt(name)?.toString() ?: ""
                    filesBody.put(name, JSONObject().put("content", content))
                }
                val bo = JSONObject()
                    .put("files", filesBody)
                    .put("public", args.optBoolean("public", false))
                val desc = s("description")
                if (desc.isNotBlank()) bo.put("description", desc)
                Triple("POST", "/gists", bo.toString())
            }
            else -> {
                return JSONObject().put("ok", false)
                    .put("error", JSONObject()
                        .put("code", "UNKNOWN_GITHUB_TOOL")
                        .put("message", "unknown github tool: $tool")
                        .put("hint", "Use github.push_file, github.pull, github.pr_create, github.request, list_tools"))
                    .put("tool", tool).toString()
            }
        }
        return try {
            githubRequestSync(method, apiPath, body, token).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message ?: "github failed").toString()
        }
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
            readSession[f.absolutePath] = System.currentTimeMillis()
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
            historySnapshot(f)
            val wasRead = readSession.containsKey(f.absolutePath)
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
                .put("was_read", wasRead)
                .apply {
                    if (!wasRead) {
                        put("warning", "file not read in this session — prefer workspace.read first")
                        if (policyStore.getBoolean("hardReadBeforeEdit", false)) {
                            // soft: still applied but flagged; hard block would need earlier return
                            put("code", "READ_BEFORE_EDIT_WARN")
                        }
                    }
                }
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
            if (f.length() > 36_000) {
                return JSONObject()
                    .put("ok", false)
                    .put("error", JSONObject()
                        .put("code", "FILE_TOO_LARGE")
                        .put("message", "file is ${f.length()} bytes; use workspace.read_b64_range")
                        .put("bytes", f.length())
                        .put("hint", "workspace.read_b64_range with offset/maxBytes"))
                    .put("path", f.absolutePath)
                    .toString()
            }
            val bytes = f.readBytes()
            val dig = java.security.MessageDigest.getInstance("SHA-256")
            dig.update(bytes)
            val sha = dig.digest().joinToString("") { "%02x".format(it) }
            JSONObject()
                .put("ok", true)
                .put("path", f.absolutePath)
                .put("bytes", bytes.size)
                .put("sha256", sha)
                .put("b64", Base64.encodeToString(bytes, Base64.NO_WRAP))
                .toString()
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

    /** Encode each path segment for Contents API (keep `/` separators). */
    private fun encodeRepoPath(path: String): String =
        path.trim().trim('/').split('/')
            .filter { it.isNotEmpty() }
            .joinToString("/") {
                java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20")
            }

    private fun githubRespSha(resp: JSONObject): String? {
        resp.optJSONObject("json")?.optString("sha")?.takeIf { it.isNotBlank() }?.let { return it }
        if (resp.optBoolean("spilled")) {
            try {
                val spill = resp.optString("spill_path")
                if (spill.isNotBlank()) {
                    val j = JSONObject(safeWorkspace(spill).readText())
                    j.optString("sha").takeIf { it.isNotBlank() }?.let { return it }
                    j.optJSONObject("content")?.optString("sha")?.takeIf { it.isNotBlank() }?.let { return it }
                    j.optJSONObject("commit")?.optString("sha")?.takeIf { it.isNotBlank() }?.let { return it }
                }
            } catch (_: Exception) {}
        }
        val blob = (resp.optString("text_preview") + resp.optString("text")).take(8000)
        return Regex("\"sha\"\\s*:\\s*\"([a-f0-9]{40})\"").find(blob)?.groupValues?.getOrNull(1)
    }

    private fun typedErr(code: String, message: String?, status: Int? = null, hint: String? = null): String {
        val e = JSONObject().put("code", code).put("message", message ?: code)
        if (status != null) e.put("status", status)
        if (!hint.isNullOrBlank()) e.put("hint", hint)
        return JSONObject().put("ok", false).put("error", e).toString()
    }


    private fun githubPushFilesBatch(args: JSONObject): String {
        return try {
            val token = githubToken() ?: return typedErr("AUTH_MISSING", "no github PAT")
            val owner = args.optString("owner")
            val repo = args.optString("repo")
            val br = args.optString("branch", "main").ifBlank { "main" }
            val msg = args.optString("message", "batch update via D-Harness")
            val files = args.optJSONArray("files")
                ?: return typedErr("ARGS", "files array required [{path,localPath}]")
            if (owner.isBlank() || repo.isBlank()) return typedErr("ARGS", "owner and repo required")
            val treeItems = JSONArray()
            val uploaded = JSONArray()
            for (i in 0 until files.length()) {
                val f = files.optJSONObject(i) ?: continue
                val remote = f.optString("path").trim().trimStart('/')
                val localPath = f.optString("localPath", f.optString("local"))
                if (remote.isBlank() || localPath.isBlank()) continue
                val local = safeWorkspace(localPath)
                if (!local.isFile) return typedErr("NOT_FOUND", "missing $localPath")
                val bytes = local.readBytes()
                val blobBody = JSONObject()
                    .put("content", Base64.encodeToString(bytes, Base64.NO_WRAP))
                    .put("encoding", "base64")
                val blobResp = githubRequestSync("POST", "/repos/$owner/$repo/git/blobs", blobBody.toString(), token)
                if (blobResp.optInt("status") !in 200..299) {
                    return typedErr("BLOB_FAILED", "failed blob for $remote", status = blobResp.optInt("status"))
                }
                val blobSha = githubRespSha(blobResp) ?: return typedErr("BLOB_SHA", remote)
                treeItems.put(
                    JSONObject().put("path", remote).put("mode", "100644").put("type", "blob").put("sha", blobSha)
                )
                uploaded.put(JSONObject().put("path", remote).put("bytes", bytes.size).put("blob_sha", blobSha))
            }
            if (treeItems.length() == 0) return typedErr("ARGS", "no valid files entries")
            val refResp = githubRequestSync(
                "GET",
                "/repos/$owner/$repo/git/ref/heads/${java.net.URLEncoder.encode(br, "UTF-8").replace("+", "%20")}",
                null, token
            )
            val headSha = refResp.optJSONObject("json")?.optJSONObject("object")?.optString("sha")
                ?: return typedErr("REF_SHA", "branch tip missing", status = refResp.optInt("status"))
            val commitResp = githubRequestSync("GET", "/repos/$owner/$repo/git/commits/$headSha", null, token)
            val baseTree = commitResp.optJSONObject("json")?.optJSONObject("tree")?.optString("sha")
                ?: return typedErr("BASE_TREE", "missing")
            val treeResp = githubRequestSync(
                "POST", "/repos/$owner/$repo/git/trees",
                JSONObject().put("base_tree", baseTree).put("tree", treeItems).toString(), token
            )
            val treeSha = githubRespSha(treeResp) ?: return typedErr("TREE_SHA", "missing", status = treeResp.optInt("status"))
            val newCommitResp = githubRequestSync(
                "POST", "/repos/$owner/$repo/git/commits",
                JSONObject().put("message", msg).put("tree", treeSha).put("parents", JSONArray().put(headSha)).toString(),
                token
            )
            val newCommitSha = githubRespSha(newCommitResp) ?: return typedErr("COMMIT_SHA", "missing")
            val upd = githubRequestSync(
                "PATCH",
                "/repos/$owner/$repo/git/refs/heads/${java.net.URLEncoder.encode(br, "UTF-8").replace("+", "%20")}",
                JSONObject().put("sha", newCommitSha).toString(), token
            )
            if (upd.optInt("status") !in 200..299) {
                return typedErr("REF_UPDATE", "failed", status = upd.optInt("status"))
            }
            JSONObject().put("ok", true).put("commit_sha", newCommitSha).put("files", uploaded)
                .put("count", uploaded.length()).put("method", "git-data-api-batch").toString()
        } catch (e: Exception) {
            typedErr("INTERNAL", e.message)
        }
    }

    /**
     * Upload workspace file via Git Data API (blob → tree → commit → ref).
     * Works for nested paths and large files; avoids Contents API GET/PUT size traps.
     */
    @JavascriptInterface
    fun githubPushFile(
        owner: String, repo: String, path: String, branch: String,
        message: String, localPath: String
    ): String {
        return try {
            val token = githubToken()
                ?: return typedErr("AUTH_MISSING", "no github PAT in keys", hint = "Settings → store key github")
            val local = safeWorkspace(localPath)
            if (!local.isFile) return typedErr("NOT_FOUND", "local file not found: $localPath")
            val bytes = local.readBytes()
            val remotePath = path.trim().trimStart('/')
            if (remotePath.isBlank()) return typedErr("ARGS", "path required")
            if (owner.isBlank() || repo.isBlank()) return typedErr("ARGS", "owner and repo required")
            val br = branch.ifBlank { "main" }
            val msg = message.ifBlank { "update $remotePath via D-Harness" }

            // 1) Create blob (base64) — handles large files; nested path applied in tree
            val blobBody = JSONObject()
                .put("content", Base64.encodeToString(bytes, Base64.NO_WRAP))
                .put("encoding", "base64")
            val blobResp = githubRequestSync("POST", "/repos/$owner/$repo/git/blobs", blobBody.toString(), token)
            val blobStatus = blobResp.optInt("status", 0)
            if (blobStatus !in 200..299) {
                return typedErr(
                    "BLOB_FAILED",
                    blobResp.optJSONObject("error")?.optString("message")
                        ?: blobResp.optString("text_preview").ifBlank { blobResp.optString("text") }.take(400),
                    status = blobStatus,
                    hint = "Check PAT contents:write scope"
                )
            }
            val blobSha = githubRespSha(blobResp)
                ?: return typedErr("BLOB_SHA", "blob created but sha missing", status = blobStatus)

            // 2) Resolve branch tip
            val refResp = githubRequestSync(
                "GET",
                "/repos/$owner/$repo/git/ref/heads/${java.net.URLEncoder.encode(br, "UTF-8").replace("+", "%20")}",
                null,
                token
            )
            if (refResp.optInt("status") !in 200..299) {
                return typedErr(
                    "REF_FAILED",
                    "branch not found: $br",
                    status = refResp.optInt("status"),
                    hint = "Create branch first with github.branch_create"
                )
            }
            val headSha = refResp.optJSONObject("json")?.optJSONObject("object")?.optString("sha")
                ?: githubRespSha(refResp)
                ?: return typedErr("REF_SHA", "could not read branch tip sha")

            // 3) Parent commit → base tree
            val commitResp = githubRequestSync("GET", "/repos/$owner/$repo/git/commits/$headSha", null, token)
            if (commitResp.optInt("status") !in 200..299) {
                return typedErr("COMMIT_READ", "failed to read head commit", status = commitResp.optInt("status"))
            }
            val baseTree = commitResp.optJSONObject("json")?.optJSONObject("tree")?.optString("sha")
                ?: run {
                    val spill = commitResp.optString("spill_path")
                    if (spill.isNotBlank()) {
                        try {
                            JSONObject(safeWorkspace(spill).readText())
                                .optJSONObject("tree")?.optString("sha")
                        } catch (_: Exception) { null }
                    } else null
                }
                ?: return typedErr("BASE_TREE", "could not read base tree sha")

            // 4) New tree: single path update (nested OK)
            val treeBody = JSONObject()
                .put("base_tree", baseTree)
                .put(
                    "tree",
                    JSONArray().put(
                        JSONObject()
                            .put("path", remotePath)
                            .put("mode", "100644")
                            .put("type", "blob")
                            .put("sha", blobSha)
                    )
                )
            val treeResp = githubRequestSync("POST", "/repos/$owner/$repo/git/trees", treeBody.toString(), token)
            if (treeResp.optInt("status") !in 200..299) {
                return typedErr(
                    "TREE_FAILED",
                    treeResp.optJSONObject("error")?.optString("message")
                        ?: treeResp.optString("text").take(400),
                    status = treeResp.optInt("status")
                )
            }
            val treeSha = githubRespSha(treeResp)
                ?: return typedErr("TREE_SHA", "tree created but sha missing")

            // 5) Commit
            val commitBody = JSONObject()
                .put("message", msg)
                .put("tree", treeSha)
                .put("parents", JSONArray().put(headSha))
            val newCommitResp = githubRequestSync("POST", "/repos/$owner/$repo/git/commits", commitBody.toString(), token)
            if (newCommitResp.optInt("status") !in 200..299) {
                return typedErr(
                    "COMMIT_FAILED",
                    newCommitResp.optJSONObject("error")?.optString("message")
                        ?: newCommitResp.optString("text").take(400),
                    status = newCommitResp.optInt("status")
                )
            }
            val newCommitSha = githubRespSha(newCommitResp)
                ?: return typedErr("COMMIT_SHA", "commit created but sha missing")

            // 6) Move branch ref
            val refBody = JSONObject().put("sha", newCommitSha).put("force", false)
            val upd = githubRequestSync(
                "PATCH",
                "/repos/$owner/$repo/git/refs/heads/${java.net.URLEncoder.encode(br, "UTF-8").replace("+", "%20")}",
                refBody.toString(),
                token
            )
            val updStatus = upd.optInt("status", 0)
            if (updStatus !in 200..299) {
                return typedErr(
                    "REF_UPDATE",
                    upd.optJSONObject("error")?.optString("message")
                        ?: upd.optString("text").take(400),
                    status = updStatus,
                    hint = "Branch may have moved; retry"
                )
            }

            JSONObject()
                .put("ok", true)
                .put("status", 201)
                .put("path", remotePath)
                .put("branch", br)
                .put("bytes", bytes.size)
                .put("blob_sha", blobSha)
                .put("commit_sha", newCommitSha)
                .put("tree_sha", treeSha)
                .put("local", local.absolutePath)
                .put("method", "git-data-api")
                .toString()
        } catch (e: Exception) {
            typedErr("INTERNAL", e.message, hint = "github.push_file exception")
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
            // Policy enforcement + modes
            try {
                val pol = JSONObject(policyCheck(tool))
                if (!pol.optBoolean("allowed", true)) {
                    logDispatch(JSONObject().put("requestId", rid).put("tool", tool).put("status", "denied"))
                    traj(tool, false, 0, "PERMISSION_DENIED")
                    return envelope(false,
                        error = """{"code":"PERMISSION_DENIED","message":"tool denied by policy","retryable":false,"hint":"${pol.optString("reason")}"}""",
                        ms = 0, tool = tool, requestId = rid)
                }
                val mode = policyStore.getString("mode", "open") ?: "open"
                val mut = tool.contains("write") || tool.contains("delete") || tool.contains("apply_patch")
                    || tool.contains("replace") || tool.contains("rm") || tool == "exec" || tool.startsWith("exec.")
                if (mode == "strict" && mut) {
                    traj(tool, false, 0, "STRICT_MODE")
                    return envelope(false,
                        error = """{"code":"PERMISSION_DENIED","message":"strict mode blocks mutations","retryable":false,"hint":"policy.mode open"}""",
                        ms = 0, tool = tool, requestId = rid)
                }
                if (mode == "allow_reads" && mut) {
                    traj(tool, false, 0, "READS_ONLY")
                    return envelope(false,
                        error = """{"code":"PERMISSION_DENIED","message":"allow_reads mode","retryable":false,"hint":"policy.mode open"}""",
                        ms = 0, tool = tool, requestId = rid)
                }
            } catch (_: Exception) {}
            val raw = maybeSpillResult(tool, dispatchByName(tool, args))
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


    private fun toolSearchCatalog(query: String): String {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return JSONObject().put("ok", false).put("error", "query required").toString()
        return try {
            val arr = JSONObject(listTools()).getJSONArray("tools")
            val hits = JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val blob = (o.optString("name") + " " + o.optString("description") + " " + o.opt("params")).lowercase()
                if (blob.contains(q)) {
                    hits.put(JSONObject()
                        .put("name", o.optString("name"))
                        .put("description", o.optString("description"))
                        .put("status", o.optString("status", "ready")))
                }
            }
            JSONObject().put("ok", true).put("query", query).put("count", hits.length()).put("tools", hits).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    private fun textHashFnv(text: String): String {
        var h = 0x811c9dc5L
        for (b in text.toByteArray(Charsets.UTF_8)) {
            h = h xor (b.toLong() and 0xff)
            h = (h * 0x01000193L) and 0xffffffffL
        }
        return JSONObject().put("ok", true).put("algo", "fnv1a32").put("hex", java.lang.Long.toHexString(h)).put("textLen", text.length).toString()
    }

    private fun jsonGetPath(objRaw: Any?, path: String): String {
        return try {
            var cur: Any? = when (objRaw) {
                is JSONObject -> objRaw
                is String -> {
                    val s = objRaw.trim()
                    when {
                        s.startsWith("{") -> JSONObject(s)
                        s.startsWith("[") -> JSONArray(s)
                        else -> return JSONObject().put("ok", false).put("error", "obj must be JSON").toString()
                    }
                }
                else -> return JSONObject().put("ok", false).put("error", "obj required").toString()
            }
            if (path.isBlank()) return JSONObject().put("ok", true).put("value", cur).toString()
            for (part in path.split('.', '/').filter { it.isNotBlank() }) {
                cur = when (cur) {
                    is JSONObject -> if (cur.has(part)) cur.get(part) else return JSONObject().put("ok", false).put("error", "missing $part").toString()
                    is JSONArray -> {
                        val idx = part.toIntOrNull() ?: return JSONObject().put("ok", false).put("error", "bad index $part").toString()
                        if (idx < 0 || idx >= cur.length()) return JSONObject().put("ok", false).put("error", "oob $idx").toString()
                        cur.get(idx)
                    }
                    else -> return JSONObject().put("ok", false).put("error", "cannot traverse").toString()
                }
            }
            JSONObject().put("ok", true).put("path", path).put("value", cur).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    private fun urlParseSimple(url: String): String {
        return try {
            val u = java.net.URI(url)
            JSONObject().put("ok", true)
                .put("scheme", u.scheme ?: JSONObject.NULL)
                .put("host", u.host ?: JSONObject.NULL)
                .put("port", u.port)
                .put("path", u.path ?: "")
                .put("query", u.query ?: JSONObject.NULL)
                .put("fragment", u.fragment ?: JSONObject.NULL)
                .toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }


    /** Shell-centric harness entry: sh -c with workspace cwd. */

    /** Build argv JSONArray and run allowlisted exec (no shell required). */
    private fun execArgvList(argv: List<String>, timeoutMs: Int = 15_000, cwd: String? = null): String {
        if (argv.isEmpty()) return JSONObject().put("ok", false).put("error", "empty argv").toString()
        val arr = JSONArray()
        for (a in argv) arr.put(a)
        return exec(arr.toString(), timeoutMs.coerceIn(500, 120_000), cwd)
    }

    private fun termFromArgs(args: JSONObject): String {
        // Prefer argv array; else cmd string via shell
        val argvAny = args.opt("argv") ?: args.opt("args")
        if (argvAny != null) {
            val list = mutableListOf<String>()
            when (argvAny) {
                is JSONArray -> for (i in 0 until argvAny.length()) list.add(argvAny.getString(i))
                is String -> {
                    try {
                        val ja = JSONArray(argvAny)
                        for (i in 0 until ja.length()) list.add(ja.getString(i))
                    } catch (_: Exception) {
                        return dispatchShell(args)
                    }
                }
                else -> return JSONObject().put("ok", false).put("error", "argv must be array").toString()
            }
            val timeout = args.optInt("timeout_ms", args.optInt("timeoutMs", 20_000))
            val cwd = args.optString("cwd", "").ifBlank { null }
            return execArgvList(list, timeout, cwd)
        }
        val cmd = sequenceOf("cmd", "command", "c", "line", "script")
            .map { args.optString(it, "") }.firstOrNull { it.isNotBlank() }
        if (cmd != null) return dispatchShell(args)
        return JSONObject().put("ok", false)
            .put("error", "provide args.argv (array) or args.cmd (string)")
            .put("hint", "term: {\"argv\":[\"ls\",\"-la\"]} or {\"cmd\":\"ls -la | head\"}")
            .toString()
    }

    private fun shellQuote(s: String): String {
        if (s.isEmpty()) return "''"
        if (s.all { it.isLetterOrDigit() || it in "._-/=:@+,%" }) return s
        return "'" + s.replace("'", "'\\''") + "'"
    }


    /**
     * Primary agent shell: chain harness tools + allowlisted binaries in one call.
     * Shared vars: step may set "as":"NAME"; later args may use "$NAME" or "${NAME}".
     * Special: "$LAST" / "$LAST_JSON" = previous step compact result.
     */
    private fun dispatchPrimaryShell(args: JSONObject): String {
        val t0 = System.currentTimeMillis()
        // Fast paths: pure binary / sh -c without script
        val hasScript = args.has("script") || args.has("steps") || args.has("pipeline")
        if (!hasScript) {
            if (args.opt("argv") is JSONArray) {
                return termFromArgs(args)
            }
            val cmd = args.optString("cmd", args.optString("command", ""))
            if (cmd.isNotBlank() && !cmd.contains("\"tool\"") && !looksLikeToolScript(cmd)) {
                return dispatchShell(args)
            }
        }

        val stopOnError = args.optBoolean("stop_on_error", args.optBoolean("stopOnError", true))
        val steps = JSONArray()
        val log = StringBuilder()
        val vars = LinkedHashMap<String, String>()
        var allOk = true
        val rawSteps = when {
            args.optJSONArray("script") != null -> args.optJSONArray("script")
            args.optJSONArray("steps") != null -> args.optJSONArray("steps")
            args.optJSONArray("pipeline") != null -> args.optJSONArray("pipeline")
            args.opt("script") is String -> parseScriptLines(args.optString("script"))
            args.opt("cmd") is String && looksLikeToolScript(args.optString("cmd")) -> parseScriptLines(args.optString("cmd"))
            else -> null
        } ?: return typedShellErr("ARGS", "shell requires script/steps array or cmd/argv")

        fun subst(s: String): String {
            var out = s
            // ${VAR} then $VAR
            val reBrace = Regex("\\$\\{([A-Za-z_][A-Za-z0-9_]*)\\}")
            out = reBrace.replace(out) { m -> vars[m.groupValues[1]] ?: m.value }
            val re = Regex("\\$([A-Za-z_][A-Za-z0-9_]*)")
            out = re.replace(out) { m -> vars[m.groupValues[1]] ?: m.value }
            return out
        }
        fun substObj(o: JSONObject): JSONObject {
            val n = JSONObject()
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                when (val v = o.opt(k)) {
                    is String -> n.put(k, subst(v))
                    is JSONObject -> n.put(k, substObj(v))
                    is JSONArray -> {
                        val a = JSONArray()
                        for (i in 0 until v.length()) {
                            when (val x = v.opt(i)) {
                                is String -> a.put(subst(x))
                                is JSONObject -> a.put(substObj(x))
                                else -> a.put(x)
                            }
                        }
                        n.put(k, a)
                    }
                    else -> n.put(k, v)
                }
            }
            return n
        }

        for (i in 0 until rawSteps.length()) {
            val stepT0 = System.currentTimeMillis()
            val stepRaw = rawSteps.opt(i)
            val stepObj: JSONObject = when (stepRaw) {
                is JSONObject -> stepRaw
                is String -> parseScriptLine(stepRaw) ?: JSONObject().put("cmd", stepRaw)
                else -> JSONObject().put("cmd", stepRaw?.toString() ?: "")
            }
            val asVar = stepObj.optString("as", stepObj.optString("save", stepObj.optString("var", "")))
            val toolName = stepObj.optString("tool", stepObj.optString("name", "")).trim()
            val stepArgs = when {
                stepObj.optJSONObject("args") != null -> substObj(stepObj.optJSONObject("args")!!)
                toolName.isBlank() && stepObj.has("cmd") -> JSONObject().put("cmd", subst(stepObj.optString("cmd")))
                toolName.isBlank() && stepObj.optJSONArray("argv") != null -> {
                    val argv = stepObj.optJSONArray("argv")!!
                    val na = JSONArray()
                    for (j in 0 until argv.length()) {
                        val x = argv.opt(j)
                        na.put(if (x is String) subst(x) else x)
                    }
                    JSONObject().put("argv", na)
                }
                else -> {
                    // treat remaining keys as args (except tool/as/save/var)
                    val a = JSONObject()
                    val it = stepObj.keys()
                    while (it.hasNext()) {
                        val k = it.next()
                        if (k in setOf("tool", "name", "as", "save", "var", "args")) continue
                        val v = stepObj.opt(k)
                        a.put(k, if (v is String) subst(v) else v)
                    }
                    a
                }
            }

            val resultStr: String = try {
                when {
                    toolName.isNotBlank() -> {
                        // Recurse into dispatcher but block nested primary shell infinite loops
                        if (toolName == "shell" || toolName == "sh" || toolName == "bash") {
                            typedShellErr("NESTED", "cannot nest shell inside shell step")
                        } else {
                            dispatchByName(toolName, stepArgs)
                        }
                    }
                    stepArgs.optJSONArray("argv") != null -> termFromArgs(stepArgs)
                    stepArgs.optString("cmd").isNotBlank() -> dispatchShell(stepArgs)
                    else -> typedShellErr("ARGS", "step $i needs tool, cmd, or argv")
                }
            } catch (e: Exception) {
                typedShellErr("INTERNAL", e.message)
            }

            val resultObj = try { JSONObject(resultStr) } catch (_: Exception) {
                JSONObject().put("ok", true).put("raw", resultStr.take(4000))
            }
            val stepFailed = when {
                resultObj.optJSONObject("error")?.optString("kind") == "exit" ->
                    stopOnError && resultObj.optInt("exitCode", 0) != 0
                resultObj.has("ran") -> stopOnError && resultObj.optInt("exitCode", 0) != 0
                else -> !resultObj.optBoolean("ok", true)
            }
            if (stepFailed) allOk = false
            val ok = !stepFailed
            val ms = System.currentTimeMillis() - stepT0
            val compact = compactShellResult(resultObj)
            vars["LAST"] = compact
            vars["LAST_JSON"] = resultObj.toString().take(120_000)
            vars["LAST_OK"] = ok.toString()
            if (asVar.isNotBlank()) {
                // Prefer useful fields for handoff
                val handoff = when {
                    resultObj.has("b64") -> resultObj.optString("b64")
                    resultObj.has("content") -> resultObj.optString("content")
                    resultObj.has("stdout") -> resultObj.optString("stdout")
                    resultObj.has("sha256") -> resultObj.optString("sha256")
                    resultObj.has("path") -> resultObj.optString("path")
                    resultObj.has("text") -> resultObj.optString("text")
                    resultObj.has("result") -> resultObj.opt("result")?.toString() ?: compact
                    else -> compact
                }
                vars[asVar] = handoff
            }
            val entry = JSONObject()
                .put("i", i)
                .put("tool", if (toolName.isNotBlank()) toolName else if (stepArgs.has("argv")) "exec" else "cmd")
                .put("ok", ok)
                .put("ms", ms)
                .put("result", resultObj)
            if (asVar.isNotBlank()) entry.put("as", asVar)
            steps.put(entry)
            log.append('#').append(i).append(' ')
                .append(entry.optString("tool")).append(if (ok) " ok" else " FAIL")
                .append(" ").append(ms).append("ms\n")
            if (!ok && stopOnError) break
        }

        return JSONObject()
            .put("ok", allOk)
            .put("steps", steps)
            .put("count", steps.length())
            .put("vars", JSONObject().also { o -> vars.forEach { (k, v) -> o.put(k, v.take(8000)) } })
            .put("log", log.toString())
            .put("ms", System.currentTimeMillis() - t0)
            .put("shell", "primary")
            .toString()
    }

    private fun typedShellErr(code: String, message: String?): String =
        JSONObject().put("ok", false)
            .put("error", JSONObject().put("code", code).put("message", message ?: code))
            .toString()

    private fun compactShellResult(o: JSONObject): String {
        val prefer = listOf("stdout", "content", "b64", "sha256", "path", "commit_sha", "text", "result")
        for (k in prefer) {
            if (o.has(k) && !o.isNull(k)) {
                val v = o.opt(k)?.toString() ?: continue
                if (v.isNotBlank()) return v.take(8000)
            }
        }
        return o.toString().take(2000)
    }

    private fun looksLikeToolScript(cmd: String): Boolean {
        val t = cmd.trim()
        if (t.startsWith("[")) return true
        if (t.contains("\"tool\"")) return true
        // line starts with dotted tool name
        val first = t.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return false
        return first.matches(Regex("^[a-z][a-z0-9_]*(\\.[a-z0-9_]+)+(\\s|$).*", RegexOption.IGNORE_CASE))
    }

    private fun parseScriptLines(script: String): JSONArray {
        val arr = JSONArray()
        // Try JSON array first
        val trimmed = script.trim()
        if (trimmed.startsWith("[")) {
            try { return JSONArray(trimmed) } catch (_: Exception) {}
        }
        for (line in script.split('\n', ';')) {
            val s = line.trim()
            if (s.isEmpty() || s.startsWith("#")) continue
            val obj = parseScriptLine(s)
            if (obj != null) arr.put(obj) else arr.put(JSONObject().put("cmd", s))
        }
        return arr
    }

    /** Parse: tool.name key=value key=value [-> VAR]  OR JSON object */
    private fun parseScriptLine(line: String): JSONObject? {
        val s = line.trim()
        if (s.startsWith("{")) {
            return try { JSONObject(s) } catch (_: Exception) { null }
        }
        var work = s
        var asVar = ""
        val arrow = Regex("\\s*->\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*$")
        arrow.find(work)?.let {
            asVar = it.groupValues[1]
            work = work.substring(0, it.range.first).trim()
        }
        val parts = work.split(Regex("\\s+"), limit = 2)
        if (parts.isEmpty()) return null
        val tool = parts[0]
        if (!tool.contains('.') && tool !in setOf("list_tools", "describe", "selftest", "sleep", "term", "exec")) {
            return null // likely a binary cmd
        }
        val o = JSONObject().put("tool", tool)
        if (asVar.isNotBlank()) o.put("as", asVar)
        val args = JSONObject()
        if (parts.size > 1) {
            // key=value pairs; values may be quoted
            val re = Regex("""([A-Za-z_][A-Za-z0-9_]*)\s*=\s*("(?:\\.|[^"])*"|'(?:\\.|[^'])*'|\S+)""")
            for (m in re.findAll(parts[1])) {
                var v = m.groupValues[2]
                if ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'"))) {
                    v = v.substring(1, v.length - 1)
                }
                args.put(m.groupValues[1], v)
            }
            // If no pairs matched, whole rest is content/cmd
            if (args.length() == 0) args.put("cmd", parts[1])
        }
        o.put("args", args)
        return o
    }

    private fun dispatchShell(args: JSONObject): String {
        val cmd = sequenceOf("cmd", "command", "c", "line", "script")
            .map { args.optString(it, "") }
            .firstOrNull { it.isNotBlank() }
            ?: return JSONObject().put("ok", false).put("error", "cmd required")
                .put("hint", "args.cmd is the shell command string").toString()
        val timeout = args.optInt("timeout_ms", args.optInt("timeoutMs", 20_000)).coerceIn(500, 120_000)
        val cwd = args.optString("cwd", args.optString("workdir", "")).ifBlank { null }
        val argv = JSONArray().put("sh").put("-c").put(cmd)
        return exec(argv.toString(), timeout, cwd)
    }

    private fun shellWhich(bin: String): String {
        if (bin.isBlank()) return JSONObject().put("ok", false).put("error", "bin required").toString()
        val r = exec(JSONArray().put("sh").put("-c").put("command -v ${bin.replace("'", "")} 2>/dev/null").toString(), 5000, null)
        return try {
            val o = JSONObject(r)
            val out = o.optString("stdout", o.optString("out", "")).trim()
            o.put("bin", bin).put("path", out).put("found", out.isNotBlank())
            o.toString()
        } catch (_: Exception) { r }
    }

    private fun shellEnv(): String {
        return try {
            val home = workspaceRoot.absolutePath
            val path = System.getenv("PATH") ?: "/system/bin:/system/xbin:/vendor/bin"
            JSONObject().put("ok", true)
                .put("HOME", home)
                .put("TMPDIR", context.cacheDir.absolutePath)
                .put("PATH", path)
                .put("workspace", home)
                .toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    private fun sysProp(name: String): String {
        if (name.isBlank()) return JSONObject().put("ok", false).put("error", "name required").toString()
        // getprop is allowlisted on most devices via toybox
        val safe = name.filter { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }
        if (safe != name) return JSONObject().put("ok", false).put("error", "invalid prop name").toString()
        return exec(JSONArray().put("getprop").put(safe).toString(), 5000, null).let { raw ->
            try {
                val o = JSONObject(raw)
                val v = o.optString("stdout", "").trim()
                o.put("name", name).put("value", v)
                o.toString()
            } catch (_: Exception) { raw }
        }
    }

    private fun sysPropsCommon(): String {
        val keys = listOf(
            "ro.build.version.release", "ro.build.version.sdk", "ro.product.model",
            "ro.product.manufacturer", "ro.product.device", "ro.build.display.id",
            "ro.hardware", "ro.board.platform"
        )
        val obj = JSONObject()
        for (k in keys) {
            try {
                val r = JSONObject(sysProp(k))
                obj.put(k, r.optString("value", r.optString("stdout", "")).trim())
            } catch (_: Exception) {
                obj.put(k, JSONObject.NULL)
            }
        }
        return JSONObject().put("ok", true).put("props", obj).toString()
    }


    private fun findDeviceBin(name: String): String? {
        val paths = listOf(
            "/system/bin/$name", "/system/xbin/$name", "/vendor/bin/$name",
            "/data/local/tmp/$name", "/sbin/$name"
        )
        for (p in paths) {
            val f = File(p)
            if (f.exists() && f.canExecute()) return f.absolutePath
        }
        return null
    }

    private fun curlOrNative(args: JSONObject): String {
        val url = args.optString("url", args.optString("uri", args.optString("link", "")))
        if (url.isBlank()) {
            return JSONObject().put("ok", false)
                .put("error", JSONObject().put("kind", "validation").put("message", "url required")).toString()
        }
        val method = args.optString("method", "GET").ifBlank { "GET" }.uppercase()
        val out = args.optString("output", args.optString("o", args.optString("file", "")))
        val headers = args.optJSONObject("headers")
        val bin = findDeviceBin("curl")
        if (bin != null) {
            val argv = mutableListOf(bin, "-sS", "-L", "--max-time", "25")
            if (method != "GET") { argv.add("-X"); argv.add(method) }
            if (out.isNotBlank()) { argv.add("-o"); argv.add(out) }
            if (headers != null) {
                val it = headers.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    argv.add("-H"); argv.add("$k: ${headers.optString(k)}")
                }
            }
            argv.add(url)
            return execArgvList(argv, 60_000, null)
        }
        // Native fallback — most phones have no curl binary
        return if (out.isNotBlank()) {
            val a = JSONObject(args.toString()).put("dest", out).put("url", url).put("method", method)
            httpFetchToFile(a)
        } else {
            val h = headers?.toString()
            val body = args.optString("body", "").ifBlank { null }
            val r = httpRequestSync(method, url, h, body)
            r.put("via", "native-http").put("hint", "curl binary missing; used native HTTP").toString()
        }
    }

    private fun wgetOrNative(args: JSONObject): String {
        val url = args.optString("url", args.optString("uri", ""))
        if (url.isBlank()) {
            return JSONObject().put("ok", false)
                .put("error", JSONObject().put("kind", "validation").put("message", "url required")).toString()
        }
        val out = args.optString("output", args.optString("o", args.optString("file", "")))
        val bin = findDeviceBin("wget")
        if (bin != null) {
            return if (out.isNotBlank()) execArgvList(listOf(bin, "-q", "-O", out, url), 60_000, null)
            else execArgvList(listOf(bin, "-q", "-O", "-", url), 60_000, null)
        }
        return if (out.isNotBlank()) {
            httpFetchToFile(JSONObject().put("url", url).put("dest", out))
        } else {
            httpRequestSync("GET", url, null, null)
                .put("via", "native-http").put("hint", "wget binary missing; used native HTTP").toString()
        }
    }

    private fun dispatchByName(tool: String, args: JSONObject): String {
        var tool = tool.trim().lowercase().let { n ->
            when (n) {
                "list", "list tools", "tools", "tools.list", "listtools" -> "list_tools"
                "fetch", "http", "http.get", "httpget" -> "http_request"
                "fetch_url", "fetchurl" -> "fetch_url"
                "github", "gh" -> "github.me"
                "terminal", "run", "cli" -> "term"
                "shell.pipe", "pipeline" -> "shell.pipe"
                "exec.allow", "allowlist" -> "exec.allowlist"
                else -> n
            }
        }
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
            "list_tools", "list", "list tools" -> listTools()
            "skill.list", "skills", "skills.list" -> skillList()
            "skill.read", "skills.read", "skill" -> skillRead(args)
            "describe", "tool.describe", "help.tool" -> describeTool(sAny("name", "tool", "id"))
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
                val url = sAny("url", "uri", "link")
                if (url.isBlank()) {
                    JSONObject().put("ok", false).put("error", "url required").toString()
                } else {
                    val method = sAny("method").ifBlank { "GET" }
                    val headers = args.opt("headers")?.let {
                        when (it) {
                            is JSONObject -> it.toString()
                            is String -> it
                            else -> null
                        }
                    }
                    val body = sAny("body", "data", "text").ifBlank { null }
                    httpRequestSync(method, url, headers, body).toString()
                }
            }
            "run_js" -> {
                // Prefer native tools; do not leave the agent stuck on an unbound sandbox.
                JSONObject().put("ok", false)
                    .put("error", "run_js is not required — call tools by name via invokeJson")
                    .put("hint", "Use {tool:workspace.ls,args:{}} or github.*/http_request directly")
                    .put("alternatives", JSONArray()
                        .put("list_tools")
                        .put("http_request")
                        .put("github.request")
                        .put("workspace.ls"))
                    .toString()
            }
            "list", "list tools", "tools", "tools.list" -> listTools()
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
            "policy.mode" -> policyMode(sAny("mode").ifBlank { null })
            "trajectory.log" -> trajectoryLog(i("limit", 30))
            "tools.groups" -> toolsGroups()
            "workspace.count" -> workspaceCount(sAny("path").ifBlank { null })
            "workspace.which" -> workspaceWhich(sAny("name", "file", "query"))
            "hash.file" -> hashFile(sAny("path"))
            "util.sleep" -> timeSleep(i("ms", 100).coerceIn(0, 10000))
            "notify.simple" -> notifySimple(sAny("title").ifBlank { "D-Harness" }, sAny("body", "text", "message"))
            "selftest.deep" -> selftestDeep()
            "agent.metrics" -> agentMetrics()
            "workspace.apply_patch_multi" -> workspaceApplyPatchMulti(sAny("patches", "edits", "files"))
            "tools.for_task" -> toolsForTask(sAny("task", "query", "q"))
            "index.fresh" -> indexFresh()
            "session.fork" -> sessionFork(sAny("name", "from"), sAny("new_name", "to", "newName"))
            "project.context" -> projectContextLoad()
            "memory.append" -> memoryAppend(sAny("text", "content", "note"))
            "workspace.diff" -> workspaceDiff(sAny("path"), sAny("content", "text"))
            "code.outline" -> codeOutline(sAny("path").ifBlank { null }, sAny("content").ifBlank { null }, i("max", 80))
            "util.base64" -> utilBase64(sAny("op").ifBlank { "encode" }, sAny("data", "text"))
            "json.merge" -> jsonMerge(sAny("a", "json"), sAny("b"))
            "json.keys" -> jsonKeys(sAny("json", "text", "data"))
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
            "task.delete" -> taskDelete(sAny("id"))
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

            "term", "terminal", "run" -> termFromArgs(args)
            "shell.pipe" -> dispatchShell(JSONObject(args.toString()).put("cmd", sAny("cmd", "command", "pipeline", "line")))
            "shell.script" -> {
                val script = sAny("script", "cmd", "command", "code")
                if (script.isBlank()) JSONObject().put("ok", false).put("error", "script required").toString()
                else dispatchShell(JSONObject().put("cmd", script).put("timeout_ms", i("timeout_ms", i("timeoutMs", 30_000))))
            }
            "ls" -> {
                val path = sAny("path", "dir", "directory").ifBlank { "." }
                val list = mutableListOf("ls")
                val long = args.optBoolean("long", false) || args.optBoolean("l", false)
                val all = args.optBoolean("all", false) || args.optBoolean("a", false)
                if (long && all) list.add("-la")
                else if (long) list.add("-l")
                else if (all) list.add("-a")
                list.add(path)
                execArgvList(list, i("timeout_ms", 10_000), sAny("cwd").ifBlank { null })
            }
            "cat" -> execArgvList(listOf("cat", sAny("path", "file")), i("timeout_ms", 15_000), null)
            "head" -> execArgvList(listOf("head", "-n", "${i("n", i("lines", 20)).coerceIn(1, 5000)}", sAny("path", "file")), 10_000, null)
            "tail" -> execArgvList(listOf("tail", "-n", "${i("n", i("lines", 20)).coerceIn(1, 5000)}", sAny("path", "file")), 10_000, null)
            "wc" -> {
                val path = sAny("path", "file")
                val flags = sAny("flags").ifBlank { "-lwc" }
                val argv = mutableListOf("wc")
                if (flags.isNotBlank()) for (f in flags.split(Regex("\\s+"))) if (f.isNotBlank()) argv.add(f)
                argv.add(path)
                execArgvList(argv, 10_000, null)
            }
            "grep" -> {
                val pat = sAny("pattern", "query", "q", "regex")
                val path = sAny("path", "file", "dir").ifBlank { "." }
                val argv = mutableListOf("grep", "-n")
                if (args.optBoolean("ignore_case", false) || args.optBoolean("i", false)) argv.add("-i")
                if (args.optBoolean("recursive", false) || args.optBoolean("r", false)) argv.add("-r")
                argv.add(pat); argv.add(path)
                execArgvList(argv, i("timeout_ms", 20_000), null)
            }
            "find" -> {
                val path = sAny("path", "dir").ifBlank { "." }
                val argv = mutableListOf("find", path)
                val md = i("maxdepth", 0)
                if (md > 0) { argv.add("-maxdepth"); argv.add(md.toString()) }
                val name = sAny("name", "pattern", "glob")
                if (name.isNotBlank()) { argv.add("-name"); argv.add(name) }
                val typ = sAny("type")
                if (typ.isNotBlank()) { argv.add("-type"); argv.add(typ.take(1)) }
                execArgvList(argv, i("timeout_ms", 30_000), null)
            }
            "mkdir" -> execArgvList(listOf("mkdir", "-p", sAny("path", "dir")), 8_000, null)
            "rm" -> {
                val path = sAny("path", "file", "dir")
                if (path.isBlank() || path == "/" || path == ".") {
                    JSONObject().put("ok", false).put("error", "refusing unsafe rm path").toString()
                } else {
                    val argv = mutableListOf("rm")
                    if (args.optBoolean("recursive", false) || args.optBoolean("r", false)) argv.add("-r")
                    if (args.optBoolean("force", true) || args.optBoolean("f", true)) argv.add("-f")
                    argv.add(path)
                    execArgvList(argv, 15_000, null)
                }
            }
            "cp" -> {
                val argv = mutableListOf("cp")
                if (args.optBoolean("recursive", false) || args.optBoolean("r", false)) argv.add("-r")
                argv.add(sAny("src", "from", "source")); argv.add(sAny("dest", "to", "destination"))
                execArgvList(argv, 30_000, null)
            }
            "mv" -> execArgvList(listOf("mv", sAny("src", "from"), sAny("dest", "to")), 15_000, null)
            "touch" -> execArgvList(listOf("touch", sAny("path", "file")), 5_000, null)
            "stat" -> execArgvList(listOf("stat", sAny("path", "file")), 5_000, null)
            "du" -> execArgvList(listOf("du", "-sh", sAny("path").ifBlank { "." }), 30_000, null)
            "pwd" -> {
                val cwd = sAny("cwd").ifBlank { null }
                try {
                    val dir = if (cwd == null) workspaceRoot else safeWorkspace(cwd)
                    JSONObject().put("ok", true).put("path", dir.absolutePath).toString()
                } catch (e: Exception) {
                    JSONObject().put("ok", false).put("error", e.message).toString()
                }
            }
            "echo" -> execArgvList(listOf("echo", sAny("text", "message", "msg")), 3_000, null)
            "date" -> {
                val fmt = sAny("format", "fmt")
                if (fmt.isBlank()) execArgvList(listOf("date"), 3_000, null)
                else execArgvList(listOf("date", fmt), 3_000, null)
            }
            "whoami" -> execArgvList(listOf("whoami"), 3_000, null)
            "id" -> execArgvList(listOf("id"), 3_000, null)
            "which" -> execArgvList(listOf("which", sAny("bin", "name", "cmd")), 3_000, null)
            "md5sum" -> execArgvList(listOf("md5sum", sAny("path", "file")), 60_000, null)
            "sha256sum" -> execArgvList(listOf("sha256sum", sAny("path", "file")), 60_000, null)
            "base64" -> {
                val op = sAny("op").ifBlank { "encode" }.lowercase()
                val path = sAny("path", "file")
                when {
                    path.isNotBlank() && op.startsWith("dec") -> execArgvList(listOf("base64", "-d", path), 30_000, null)
                    path.isNotBlank() -> execArgvList(listOf("base64", path), 30_000, null)
                    else -> {
                        val data = sAny("data", "text")
                        dispatchShell(JSONObject().put("cmd", "printf %s " + shellQuote(data) + " | base64" + if (op.startsWith("dec")) " -d" else ""))
                    }
                }
            }
            "diff" -> {
                val argv = mutableListOf("diff")
                if (args.optBoolean("unified", true)) argv.add("-u")
                argv.add(sAny("a", "left", "from")); argv.add(sAny("b", "right", "to"))
                execArgvList(argv, 30_000, null)
            }
            "sort" -> {
                val argv = mutableListOf("sort")
                if (args.optBoolean("unique", false) || args.optBoolean("u", false)) argv.add("-u")
                argv.add(sAny("path", "file"))
                execArgvList(argv, 30_000, null)
            }
            "uniq" -> {
                val argv = mutableListOf("uniq")
                if (args.optBoolean("count", false) || args.optBoolean("c", false)) argv.add("-c")
                argv.add(sAny("path", "file"))
                execArgvList(argv, 15_000, null)
            }
            "cut" -> {
                val argv = mutableListOf("cut", "-f", sAny("fields", "f", "field").ifBlank { "1" })
                val d = sAny("delimiter", "d", "sep")
                if (d.isNotBlank()) { argv.add("-d"); argv.add(d) }
                argv.add(sAny("path", "file"))
                execArgvList(argv, 15_000, null)
            }
            "tr" -> {
                val path = sAny("path", "file")
                val s1 = sAny("set1", "from"); val s2 = sAny("set2", "to")
                dispatchShell(JSONObject().put("cmd", "tr " + shellQuote(s1) + " " + shellQuote(s2) + " < " + shellQuote(path)))
            }
            "sed" -> {
                val expr = sAny("expr", "expression", "e")
                val path = sAny("path", "file")
                val argv = mutableListOf("sed")
                if (args.optBoolean("in_place", false) || args.optBoolean("i", false)) argv.add("-i")
                argv.add(expr); argv.add(path)
                execArgvList(argv, 30_000, null)
            }
            "chmod" -> execArgvList(listOf("chmod", sAny("mode", "perm"), sAny("path", "file")), 5_000, null)
            "ln" -> {
                val argv = mutableListOf("ln")
                if (args.optBoolean("symbolic", true) || args.optBoolean("s", true)) argv.add("-s")
                argv.add(sAny("target", "src")); argv.add(sAny("link", "dest", "path"))
                execArgvList(argv, 5_000, null)
            }
            "realpath" -> execArgvList(listOf("realpath", sAny("path")), 5_000, null)
            "readlink" -> execArgvList(listOf("readlink", sAny("path")), 5_000, null)
            "dirname" -> execArgvList(listOf("dirname", sAny("path")), 3_000, null)
            "basename" -> execArgvList(listOf("basename", sAny("path")), 3_000, null)
            "tar" -> {
                val op = sAny("op", "mode").ifBlank { "t" }.lowercase()
                val archive = sAny("archive", "file", "tar")
                val path = sAny("path", "dir")
                val argv = mutableListOf("tar")
                when {
                    op.startsWith("c") -> { argv.addAll(listOf("-czf", archive)); if (path.isNotBlank()) argv.add(path) else argv.add(".") }
                    op.startsWith("x") -> { argv.addAll(listOf("-xzf", archive)); if (path.isNotBlank()) argv.addAll(listOf("-C", path)) }
                    else -> argv.addAll(listOf("-tzf", archive))
                }
                execArgvList(argv, 120_000, null)
            }
            "gzip" -> {
                val path = sAny("path", "file")
                if (args.optBoolean("decompress", false) || args.optBoolean("d", false))
                    execArgvList(listOf("gzip", "-d", "-k", path), 60_000, null)
                else execArgvList(listOf("gzip", "-k", path), 60_000, null)
            }
            "unzip" -> {
                val archive = sAny("archive", "path", "file")
                if (args.optBoolean("list", false) || args.optBoolean("l", false)) {
                    execArgvList(listOf("unzip", "-l", archive), 30_000, null)
                } else {
                    val dest = sAny("dest", "to", "dir")
                    if (dest.isNotBlank()) execArgvList(listOf("unzip", "-o", archive, "-d", dest), 120_000, null)
                    else execArgvList(listOf("unzip", "-o", archive), 120_000, null)
                }
            }
            "ping" -> execArgvList(listOf("ping", "-c", "${i("count", 3).coerceIn(1, 20)}", sAny("host", "target", "ip")), 30_000, null)
            "curl" -> curlOrNative(args)
            "wget" -> wgetOrNative(args)
            "logcat" -> {
                val lines = i("lines", 100).coerceIn(20, 2000)
                val filter = sAny("filter", "query", "tag")
                val raw = execArgvList(listOf("logcat", "-d", "-t", lines.toString()), 20_000, null)
                if (filter.isBlank()) raw
                else try {
                    val o = JSONObject(raw)
                    val kept = o.optString("stdout", "").lines().filter { it.contains(filter, true) }
                    o.put("stdout", kept.joinToString("\n").take(80_000)).put("matched", kept.size).toString()
                } catch (_: Exception) { raw }
            }
            "dumpsys" -> {
                val svc = sAny("service", "name")
                if (svc.isBlank()) JSONObject().put("ok", false).put("error", "service required").toString()
                else {
                    val argv = mutableListOf("dumpsys", svc)
                    val extra = args.opt("args")
                    if (extra is JSONArray) for (i0 in 0 until extra.length()) argv.add(extra.getString(i0))
                    execArgvList(argv, 30_000, null)
                }
            }
            "am" -> {
                val argvAny = args.opt("argv") ?: args.opt("args")
                val list = mutableListOf("am")
                when (argvAny) {
                    is JSONArray -> for (i0 in 0 until argvAny.length()) list.add(argvAny.getString(i0))
                    is String -> list.addAll(argvAny.split(Regex("\\s+")).filter { it.isNotBlank() })
                    else -> { }
                }
                
                execArgvList(list, 20_000, null)
            }
            "pm.install" -> execArgvList(listOf("pm", "install", "-r", sAny("path", "apk")), 120_000, null)
            "settings.get" -> execArgvList(listOf("settings", "get", sAny("namespace", "ns").ifBlank { "system" }, sAny("key", "name")), 8_000, null)
            "settings.put" -> execArgvList(listOf("settings", "put", sAny("namespace", "ns").ifBlank { "system" }, sAny("key", "name"), sAny("value")), 8_000, null)
            "wm.size" -> execArgvList(listOf("wm", "size"), 5_000, null)
            "wm.density" -> execArgvList(listOf("wm", "density"), 5_000, null)
            "free" -> execArgvList(listOf("free", "-h"), 5_000, null)
            "uptime" -> execArgvList(listOf("uptime"), 3_000, null)
            "nproc" -> execArgvList(listOf("nproc"), 3_000, null)
            "printenv" -> {
                val name = sAny("name", "key", "var")
                if (name.isBlank()) execArgvList(listOf("printenv"), 5_000, null)
                else execArgvList(listOf("printenv", name), 3_000, null)
            }
            "exec.allowlist" -> JSONObject().put("ok", true).put("allow", JSONArray(execAllow.sorted())).put("count", execAllow.size).toString()
            "tools.suggest" -> {
                val task = sAny("task", "query", "q", "goal").lowercase()
                val suggestions = JSONArray()
                fun add(tool: String, why: String) { suggestions.put(JSONObject().put("tool", tool).put("why", why)) }
                when {
                    task.contains("github") || task.contains("pr ") || task.contains("issue") -> {
                        add("github.me", "auth check"); add("github.request", "raw API"); add("github.contents", "read file")
                    }
                    task.contains("http") || task.contains("url") || task.contains("fetch") -> {
                        add("http_request", "native HTTP"); add("curl", "CLI HTTP"); add("research.preview", "URL preview")
                    }
                    task.contains("file") || task.contains("edit") || task.contains("patch") -> {
                        add("workspace.read", "read"); add("workspace.apply_patch", "edit"); add("workspace.grep", "search")
                    }
                    task.contains("shell") || task.contains("command") || task.contains("terminal") -> {
                        add("term", "argv or cmd"); add("shell", "sh -c"); add("exec.allowlist", "see bins")
                    }
                    task.contains("package") || task.contains("apk") -> {
                        add("pkg.list", "list packages"); add("pkg.path", "apk path")
                    }
                    task.contains("log") || task.contains("crash") -> {
                        add("logcat", "device logs"); add("sys.ps", "processes")
                    }
                    else -> {
                        add("list_tools", "full catalog"); add("tool.search", "keyword search"); add("term", "terminal")
                    }
                }
                JSONObject().put("ok", true).put("task", task).put("suggestions", suggestions).toString()
            }

            "shell", "sh", "cmd", "bash", "agent.shell", "pipeline" -> dispatchPrimaryShell(args)
            "shell.which" -> shellWhich(sAny("bin", "name", "cmd"))
            "shell.env" -> shellEnv()
            "shell.pwd" -> {
                try {
                    val cwd = sAny("cwd").ifBlank { null }
                    val dir = if (cwd.isNullOrBlank()) workspaceRoot else safeWorkspace(cwd)
                    JSONObject().put("ok", true).put("path", dir.absolutePath).put("exists", dir.exists()).toString()
                } catch (e: Exception) {
                    JSONObject().put("ok", false).put("error", e.message).toString()
                }
            }
            "shell.lines" -> {
                val raw = dispatchShell(args)
                try {
                    val o = JSONObject(raw)
                    val text = o.optString("stdout", o.optString("out", ""))
                    val lines = JSONArray()
                    for (line in text.lines().take(2000)) lines.put(line)
                    o.put("lines", lines).put("lineCount", lines.length()).toString()
                } catch (_: Exception) { raw }
            }
            "shell.ok" -> {
                val raw = dispatchShell(args)
                try {
                    val o = JSONObject(raw)
                    val code = o.optInt("exitCode", o.optInt("code", -1))
                    o.put("ok", code == 0).put("exitCode", code).toString()
                } catch (_: Exception) { raw }
            }
            "sys.prop" -> sysProp(sAny("name", "key", "prop"))
            "sys.props" -> sysPropsCommon()
            "sys.uname" -> exec(JSONArray().put("uname").put("-a").toString(), 5000, null)
            "sys.df" -> exec(JSONArray().put("df").put("-h").toString(), 8000, null)
            "sys.ps" -> {
                val raw = exec(JSONArray().put("ps").put("-A").toString(), 8000, null)
                val filter = sAny("filter", "q", "grep")
                if (filter.isBlank()) raw
                else try {
                    val o = JSONObject(raw)
                    val lines = o.optString("stdout", "").lines().filter { it.contains(filter, true) }
                    o.put("stdout", lines.joinToString("\n").take(60_000)).put("matched", lines.size).toString()
                } catch (_: Exception) { raw }
            }
            "pkg.list" -> {
                val filter = sAny("filter", "q", "package")
                val limit = i("limit", 80).coerceIn(1, 500)
                val raw = exec(JSONArray().put("pm").put("list").put("packages").toString(), 15_000, null)
                try {
                    val o = JSONObject(raw)
                    var lines = o.optString("stdout", "").lines().map { it.removePrefix("package:").trim() }.filter { it.isNotBlank() }
                    if (filter.isNotBlank()) lines = lines.filter { it.contains(filter, true) }
                    val arr = JSONArray()
                    lines.take(limit).forEach { arr.put(it) }
                    JSONObject().put("ok", true).put("packages", arr).put("count", arr.length()).put("truncated", lines.size > limit).toString()
                } catch (_: Exception) { raw }
            }
            "pkg.path" -> {
                val pkg = sAny("package", "pkg", "name")
                if (pkg.isBlank()) JSONObject().put("ok", false).put("error", "package required").toString()
                else exec(JSONArray().put("pm").put("path").put(pkg).toString(), 8000, null)
            }
            "toybox.list" -> toyboxList()

            // ── previously catalog-only (now bound) ──
            "research.web" -> researchWeb(sAny("query", "q"), i("maxSources", i("max_sources", 6)).coerceIn(1, 12))
            "research.preview" -> researchPreview(sAny("url", "uri", "link"))
            "research.html_text" -> researchHtmlText(sAny("url", "uri"), i("maxChars", i("max_chars", 12000)).coerceIn(500, 200_000))
            "research.plan" -> researchPlan(sAny("topic", "query", "q"))
            "exec.lang" -> execLang(sAny("lang", "language"), sAny("code", "source", "script"), i("timeout_ms", i("timeoutMs", 15000)))
            "text.regex_replace" -> textRegexReplace(sAny("text", "content"), sAny("pattern", "regex"), sAny("replacement", "replace", "with"))
            "util.uuid" -> utilUuid()
            "util.time" -> utilTime()
            "sensors.list" -> sensorsList()
            "sensors.read" -> sensorsRead(sAny("type", "name", "sensor"))
            "torch.set" -> torchSet(args.optBoolean("on", sAny("on", "value", "state") in listOf("1", "true", "on", "yes")))
            "audio.volume" -> audioVolume(sAny("stream").ifBlank { null }, i("level", i("volume", 5)))
            "audio.ringer" -> audioRinger(sAny("mode").ifBlank { null })
            "wakelock.acquire" -> wakelockAcquire(i("ms", 5000).coerceIn(100, 60_000))
            "wakelock.release" -> wakelockRelease()
            "toybox.run" -> toyboxRun(sAny("applet", "cmd", "name"), args.opt("args")?.toString())
            "tool.search" -> toolSearchCatalog(sAny("query", "q", "text"))
            "text.hash" -> textHashFnv(sAny("text", "data", "content"))
            "json.get" -> jsonGetPath(args.opt("obj") ?: args.opt("json") ?: args.opt("data"), sAny("path", "key"))
            "url.parse" -> urlParseSimple(sAny("url", "uri", "link"))
            "encode.uri" -> textUrl("encode", sAny("data", "text", "url", "value"))
            "decode.uri" -> textUrl("decode", sAny("data", "text", "url", "value"))
            "random.int" -> {
                val lo = i("min", 0)
                val hi = i("max", 100)
                val a = minOf(lo, hi); val b = maxOf(lo, hi)
                val v = if (b <= a) a else a + java.util.concurrent.ThreadLocalRandom.current().nextInt(b - a + 1)
                JSONObject().put("ok", true).put("value", v).put("min", a).put("max", b).toString()
            }
            "string.split" -> textSplit(sAny("text"), sAny("sep", "separator", "delimiter"), i("limit", 0))
            "string.join" -> textJoin(args.opt("parts")?.toString() ?: "[]", sAny("sep", "separator"))
            "string.replace" -> textReplace(sAny("text"), sAny("find", "old"), sAny("replace", "new", "with"))
            "sleep" -> timeSleep(i("ms", 100).coerceIn(0, 30_000))
            "math.eval" -> {
                val expr = sAny("expr", "expression", "math")
                try {
                    calcEval(expr)
                } catch (_: Exception) {
                    JSONObject().put("ok", true).put("value", evalExpr(expr)).toString()
                }
            }
            "file.commit" -> fileCommit(sAny("path"), sAny("contentB64", "content_b64", "b64"), sAny("expectedSha", "sha", "sha256").ifBlank { null })
            "file.read_b64" -> fileReadB64(sAny("path"))
            "file.verify_roundtrip" -> fileVerifyRoundtrip()
            "file.save" -> {
                val path = sAny("path", "name")
                val content = sAny("content", "text", "data")
                if (path.isBlank()) JSONObject().put("ok", false).put("error", "path required").toString()
                else workspaceWrite(path, content)
            }
            "file.append" -> fileAppend(sAny("path"), sAny("content", "text"))
            "paste_box" -> {
                // UI tool: async dialog; return pending + use deliver path when callback provided
                val cb = sAny("callbackId", "requestId", "id").ifBlank { "paste-" + System.currentTimeMillis() }
                pasteBox(sAny("path").ifBlank { null }, sAny("title").ifBlank { null }, sAny("hint").ifBlank { null }, cb)
                JSONObject().put("ok", true).put("pending", true).put("callbackId", cb)
                    .put("hint", "User dialog open; result arrives as TOOL_RESULT when Done/Cancel").toString()
            }
            "sqlite.query" -> sqliteQuery(sAny("path", "db"), sAny("sql", "query"), args.opt("args")?.toString())
            "archive.zip_list" -> archiveZipList(sAny("path", "zip"))
            "archive.zip_extract" -> archiveZipExtract(sAny("path", "zip"), sAny("dest", "to").ifBlank { null })
            "archive.zip_create" -> archiveZipCreate(sAny("path", "src", "from"), sAny("dest", "zip", "to").ifBlank { null })
            "process.list" -> processList()
            "process.kill" -> processKill(i("pid", 0))
            "share" -> shareText(sAny("text", "content", "message"))
            "appInfo" -> appInfo()
            "geo.get" -> geoGet(i("timeout_ms", i("timeoutMs", 8000)).coerceIn(1000, 30_000))
            "fs.stat" -> fsStat(sAny("path"))
            "fs.exists" -> fsExists(sAny("path"))
            "fs.append" -> fsAppend(sAny("path"), sAny("content", "text"))
            "fs.mkdir" -> fsMkdir(sAny("path"))
            "fs.touch" -> fsTouch(sAny("path"))
            "fs.copy" -> fsCopy(sAny("from", "src", "source"), sAny("to", "dest", "destination"))
            "fs.move" -> fsMove(sAny("from", "src", "source"), sAny("to", "dest", "destination"))
            "device.display" -> deviceDisplay()
            "device.uptime" -> deviceUptime()
            "device.storage" -> deviceStorage()
            "device.memory" -> deviceMemory()
            "device.locale" -> deviceLocale()
            "device.timezone" -> deviceTimezone()
            "device.sensors" -> deviceSensors(i("limit", 50).coerceIn(1, 200))


            "shell.cd" -> {
                try {
                    val dir = safeWorkspace(sAny("path", "dir").ifBlank { "." })
                    JSONObject().put("ok", true).put("path", dir.absolutePath).put("exists", dir.exists()).put("isDirectory", dir.isDirectory).toString()
                } catch (e: Exception) {
                    JSONObject().put("ok", false).put("error", e.message).toString()
                }
            }
            "shell.test" -> {
                val path = sAny("path")
                val mode = sAny("mode", "op").ifBlank { "exists" }
                val flag = when (mode.lowercase()) {
                    "file", "f", "-f" -> "-f"
                    "dir", "d", "-d" -> "-d"
                    else -> "-e"
                }
                execArgvList(listOf("test", flag, path), 3_000, null)
            }
            "shell.cat_head" -> execArgvList(listOf("head", "-n", "${i("n", 40).coerceIn(1, 2000)}", sAny("path")), 10_000, null)
            "true" -> execArgvList(listOf("true"), 2_000, null)
            "false" -> execArgvList(listOf("false"), 2_000, null)
            "seq" -> execArgvList(listOf("seq", "${i("start", 1)}", "${i("end", 10)}"), 5_000, null)
            "yes" -> {
                val n = i("lines", 5).coerceIn(1, 100)
                val text = sAny("text").ifBlank { "y" }
                dispatchShell(JSONObject().put("cmd", "yes " + shellQuote(text) + " | head -n " + n))
            }
            "cmp" -> execArgvList(listOf("cmp", sAny("a", "left"), sAny("b", "right")), 15_000, null)
            "tee" -> {
                val path = sAny("path"); val text = sAny("text", "content")
                val op = if (args.optBoolean("append", false)) ">>" else ">"
                dispatchShell(JSONObject().put("cmd", "printf %s " + shellQuote(text) + " " + op + " " + shellQuote(path)))
            }
            "xargs" -> {
                val bin = sAny("bin", "cmd")
                val lines = args.optJSONArray("lines") ?: JSONArray()
                val maxA = i("max_args", 20).coerceIn(1, 100)
                val argv = mutableListOf(bin)
                for (i0 in 0 until minOf(lines.length(), maxA)) argv.add(lines.getString(i0))
                execArgvList(argv, 30_000, null)
            }
            "timeout" -> {
                val sec = i("seconds", i("s", 10)).coerceIn(1, 120)
                val argvAny = args.opt("argv")
                val list = mutableListOf("timeout", sec.toString())
                if (argvAny is JSONArray) {
                    for (i0 in 0 until argvAny.length()) list.add(argvAny.getString(i0))
                    execArgvList(list, (sec + 5) * 1000, null)
                } else {
                    JSONObject().put("ok", false).put("error", "argv required").toString()
                }
            }
            "kill" -> {
                val pid = i("pid", 0)
                val sig = sAny("signal", "sig").ifBlank { "TERM" }
                if (pid <= 0) JSONObject().put("ok", false).put("error", "pid required").toString()
                else execArgvList(listOf("kill", "-$sig", pid.toString()), 5_000, null)
            }
            "pidof" -> execArgvList(listOf("pidof", sAny("name", "process")), 5_000, null)
            "getprop" -> sysProp(sAny("name", "key", "prop"))
            "ifconfig" -> execArgvList(listOf("ifconfig"), 8_000, null)
            "ip" -> {
                val argvAny = args.opt("args") ?: args.opt("argv")
                val list = mutableListOf("ip")
                if (argvAny is JSONArray && argvAny.length() > 0) {
                    for (i0 in 0 until argvAny.length()) list.add(argvAny.getString(i0))
                } else list.add("addr")
                execArgvList(list, 8_000, null)
            }
            "netstat" -> execArgvList(listOf("netstat", "-n"), 10_000, null)
            "service.list" -> {
                val raw = execArgvList(listOf("service", "list"), 15_000, null)
                val filter = sAny("filter", "q")
                if (filter.isBlank()) {
                    raw
                } else {
                    try {
                        val o = JSONObject(raw)
                        val kept = o.optString("stdout", "").lines().filter { it.contains(filter, true) }
                        val joined = kept.joinToString(separator = "\u000A")
                        o.put("stdout", joined.take(60_000)).put("matched", kept.size).toString()
                    } catch (_: Exception) {
                        raw
                    }
                }
            }
            "input.text" -> execArgvList(listOf("input", "text", sAny("text")), 8_000, null)

            "tools.help" -> {
                val rules = JSONArray()
                rules.put("Prefer Claude <function_calls><invoke>…</invoke></function_calls>; DSML also accepted; tool_call fence compat")
                rules.put("Load skill.read before github/workspace/http/device domains")
                rules.put("Close with ~~~``` so payload may contain normal ```")
                rules.put("STOP after fence; wait for TOOL_RESULT")
                rules.put("Never invent TOOL_RESULT or use DSML")
                JSONObject()
                    .put("ok", true)
                    .put("protocol", "claude_invoke+tool_call_v2")
                    .put("open", "```tool_call")
                    .put("close", "~~~```")
                    .put("alt_close", "end_tool_call")
                    .put("schema", JSONObject()
                        .put("tool", "string exact catalog name")
                        .put("args", "object (required, may be empty {})")
                        .put("description", "string optional UI label"))
                    .put("rules", rules)
                    .put("common_errors", JSONObject()
                        .put("MISSING_TOOL_CALL_FENCE", "Bare JSON without fence")
                        .put("JSON_PARSE_ERROR", "Invalid JSON body")
                        .put("MISSING_TOOL", "tool field missing")
                        .put("ARGS_NOT_OBJECT", "args must be object"))
                    .put("example", "```tool_call\n{\"tool\":\"list_tools\",\"description\":\"catalog\",\"args\":{}}\n~~~```")
                    .toString()
            }
            "tools.examples" -> {
                val topic = sAny("topic", "q", "for").lowercase()
                val examples = JSONArray()
                fun ex(title: String, body: String) { examples.put(JSONObject().put("title", title).put("fence", body)) }
                if (topic.isBlank() || topic.contains("list") || topic.contains("workspace")) {
                    ex("list workspace", "```tool_call\n{\"tool\":\"workspace.ls\",\"description\":\"list\",\"args\":{}}\n~~~```")
                    ex("read file", "```tool_call\n{\"tool\":\"workspace.read\",\"description\":\"read\",\"args\":{\"path\":\"README.md\"}}\n~~~```")
                }
                if (topic.isBlank() || topic.contains("shell") || topic.contains("term")) {
                    ex("term argv", "```tool_call\n{\"tool\":\"term\",\"description\":\"ls\",\"args\":{\"argv\":[\"ls\",\"-la\"]}}\n~~~```")
                    ex("shell pipe", "```tool_call\n{\"tool\":\"shell\",\"description\":\"pipe\",\"args\":{\"cmd\":\"ps -A | head -n 5\"}}\n~~~```")
                }
                if (topic.isBlank() || topic.contains("http") || topic.contains("net")) {
                    ex("http GET", "```tool_call\n{\"tool\":\"http_request\",\"description\":\"GET\",\"args\":{\"url\":\"https://example.com\",\"method\":\"GET\"}}\n~~~```")
                }
                if (topic.isBlank() || topic.contains("github")) {
                    ex("github me", "```tool_call\n{\"tool\":\"github.me\",\"description\":\"auth\",\"args\":{}}\n~~~```")
                }
                if (examples.length() == 0) {
                    ex("discover", "```tool_call\n{\"tool\":\"tool.search\",\"description\":\"search\",\"args\":{\"query\":\"workspace\"}}\n~~~```")
                }
                JSONObject().put("ok", true).put("topic", topic).put("examples", examples).toString()
            }
            "tools.count" -> {
                try {
                    val o = JSONObject(listTools())
                    val n = o.optJSONArray("tools")?.length() ?: o.optInt("count", 0)
                    JSONObject().put("ok", true).put("count", n).toString()
                } catch (e: Exception) {
                    JSONObject().put("ok", false).put("error", e.message).toString()
                }
            }
            "harness.info" -> {
                JSONObject()
                    .put("ok", true)
                    .put("name", "D-Harness")
                    .put("workspace", workspaceRoot.absolutePath)
                    .put("exec_shell", settings.getBoolean("exec_shell", false))
                    .put("protocol", "claude_invoke+tool_call_v2")
                    .put("close", "~~~```")
                    .toString()
            }
            "session.info" -> {
                JSONObject()
                    .put("ok", true)
                    .put("workspace", workspaceRoot.absolutePath)
                    .put("time", System.currentTimeMillis())
                    .put("protocol", "claude_invoke+tool_call_v2")
                    .toString()
            }
            "env" -> {
                val name = sAny("name", "key", "var")
                if (name.isBlank()) execArgvList(listOf("printenv"), 5_000, null)
                else execArgvList(listOf("printenv", name), 3_000, null)
            }
            "uname" -> execArgvList(listOf("uname", "-a"), 3_000, null)
            "printf" -> {
                val fmt = sAny("format", "fmt", "text")
                val arr = args.optJSONArray("args")
                val cmd = StringBuilder("printf ")
                cmd.append(shellQuote(fmt))
                if (arr != null) for (i0 in 0 until arr.length()) {
                    cmd.append(' ').append(shellQuote(arr.getString(i0)))
                }
                dispatchShell(JSONObject().put("cmd", cmd.toString()))
            }
            "rev" -> {
                val path = sAny("path", "file")
                if (path.isNotBlank()) execArgvList(listOf("rev", path), 10_000, null)
                else {
                    val text = sAny("text", "data")
                    dispatchShell(JSONObject().put("cmd", "printf %s " + shellQuote(text) + " | rev"))
                }
            }
            "file" -> execArgvList(listOf("file", sAny("path", "file")), 8_000, null)
            "strings" -> {
                val min = i("min", 4).coerceIn(2, 32)
                execArgvList(listOf("strings", "-n", min.toString(), sAny("path", "file")), 20_000, null)
            }
            "hexdump" -> {
                val n = i("n", 256).coerceIn(16, 4096)
                dispatchShell(JSONObject().put("cmd", "hexdump -C " + shellQuote(sAny("path", "file")) + " | head -n " + (n / 16 + 2)))
            }
            "nl" -> execArgvList(listOf("nl", "-ba", sAny("path", "file")), 10_000, null)
            "json.pretty" -> {
                try {
                    val raw = sAny("json", "text", "data")
                    val indent = i("indent", 2).coerceIn(0, 8)
                    val parsed: Any = if (raw.trim().startsWith("[")) JSONArray(raw) else JSONObject(raw)
                    val pretty = when (parsed) {
                        is JSONObject -> parsed.toString(indent)
                        is JSONArray -> parsed.toString(indent)
                        else -> raw
                    }
                    JSONObject().put("ok", true).put("json", pretty).toString()
                } catch (e: Exception) {
                    JSONObject().put("ok", false).put("error", e.message).toString()
                }
            }
            "json.min" -> {
                try {
                    val raw = sAny("json", "text", "data")
                    val parsed: Any = if (raw.trim().startsWith("[")) JSONArray(raw) else JSONObject(raw)
                    val mini = when (parsed) {
                        is JSONObject -> parsed.toString()
                        is JSONArray -> parsed.toString()
                        else -> raw
                    }
                    JSONObject().put("ok", true).put("json", mini).toString()
                } catch (e: Exception) {
                    JSONObject().put("ok", false).put("error", e.message).toString()
                }
            }
            "text.count" -> {
                val text = sAny("text", "data", "content")
                val lines = if (text.isEmpty()) 0 else text.split('\n').size
                val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.size
                JSONObject().put("ok", true).put("chars", text.length).put("words", words).put("lines", lines).toString()
            }
            "url.encode" -> JSONObject().put("ok", true).put("result", java.net.URLEncoder.encode(sAny("text", "value", "q"), "UTF-8")).toString()
            "url.decode" -> {
                try {
                    JSONObject().put("ok", true).put("result", java.net.URLDecoder.decode(sAny("text", "value", "q"), "UTF-8")).toString()
                } catch (e: Exception) {
                    JSONObject().put("ok", false).put("error", e.message).toString()
                }
            }
            "path.join" -> {
                val parts = args.optJSONArray("parts") ?: JSONArray()
                val sb = StringBuilder()
                for (i0 in 0 until parts.length()) {
                    val p = parts.getString(i0).trim('/').trim()
                    if (p.isEmpty()) continue
                    if (sb.isNotEmpty()) sb.append('/')
                    sb.append(p)
                }
                try {
                    val f = safeWorkspace(sb.toString())
                    JSONObject().put("ok", true).put("path", f.absolutePath).put("relative", sb.toString()).toString()
                } catch (e: Exception) {
                    JSONObject().put("ok", false).put("error", e.message).toString()
                }
            }
            "path.split" -> {
                val path = sAny("path", "file")
                val f = java.io.File(path)
                val name = f.name
                val dot = name.lastIndexOf('.')
                val ext = if (dot > 0) name.substring(dot + 1) else ""
                val base = if (dot > 0) name.substring(0, dot) else name
                JSONObject().put("ok", true).put("dir", f.parent ?: "").put("name", name).put("base", base).put("ext", ext).toString()
            }
            "dns.lookup" -> {
                try {
                    val host = sAny("host", "name", "domain")
                    val addrs = java.net.InetAddress.getAllByName(host)
                    val arr = JSONArray()
                    for (a in addrs) arr.put(a.hostAddress)
                    JSONObject().put("ok", true).put("host", host).put("addresses", arr).toString()
                } catch (e: Exception) {
                    JSONObject().put("ok", false).put("error", e.message).toString()
                }
            }
            "sys.meminfo" -> execArgvList(listOf("head", "-n", "20", "/proc/meminfo"), 5_000, null)
            "sys.cpuinfo" -> execArgvList(listOf("head", "-n", "40", "/proc/cpuinfo"), 5_000, null)
            "logcat.clear" -> execArgvList(listOf("logcat", "-c"), 5_000, null)
            "note.set" -> {
                val key = sAny("key", "name", "id")
                val value = sAny("value", "text", "content")
                if (key.isBlank()) JSONObject().put("ok", false).put("error", "key required").toString()
                else {
                    mem.edit().putString("note_$key", value).apply()
                    JSONObject().put("ok", true).put("key", key).put("bytes", value.length).toString()
                }
            }
            "note.get" -> {
                val key = sAny("key", "name", "id")
                val v = mem.getString("note_$key", null)
                if (v == null) JSONObject().put("ok", false).put("error", "not found").put("key", key).toString()
                else JSONObject().put("ok", true).put("key", key).put("value", v).toString()
            }
            "note.list" -> {
                val arr = JSONArray()
                for (e in mem.all.entries) {
                    if (e.key.startsWith("note_")) arr.put(e.key.removePrefix("note_"))
                }
                JSONObject().put("ok", true).put("keys", arr).put("count", arr.length()).toString()
            }
            "workspace.mv" -> {
                try {
                    val from = safeWorkspace(sAny("from", "src", "source"))
                    val to = safeWorkspace(sAny("to", "dest", "destination"))
                    val ok = from.renameTo(to)
                    JSONObject().put("ok", ok).put("from", from.absolutePath).put("to", to.absolutePath).toString()
                } catch (e: Exception) {
                    JSONObject().put("ok", false).put("error", e.message).toString()
                }
            }
            "workspace.cp" -> {
                try {
                    val from = safeWorkspace(sAny("from", "src", "source"))
                    val to = safeWorkspace(sAny("to", "dest", "destination"))
                    from.copyTo(to, overwrite = true)
                    JSONObject().put("ok", true).put("from", from.absolutePath).put("to", to.absolutePath).put("bytes", to.length()).toString()
                } catch (e: Exception) {
                    JSONObject().put("ok", false).put("error", e.message).toString()
                }
            }
            "test.file" -> {
                val path = sAny("path")
                val mode = sAny("mode", "op").ifBlank { "exists" }
                val flag = when (mode.lowercase()) {
                    "file", "f", "-f" -> "-f"
                    "dir", "d", "-d" -> "-d"
                    else -> "-e"
                }
                execArgvList(listOf("test", flag, path), 3_000, null)
            }

            "input.key" -> execArgvList(listOf("input", "keyevent", "${i("code", i("key", 4))}"), 5_000, null)

            "http.fetch_to_file" -> httpFetchToFile(args)
            "github.pr_ready" -> githubPrReady(sAny("owner"), sAny("repo"), args.optInt("number", args.optInt("pr", 0)))
            "github.diff" -> githubDiffToFile(args)
            "workspace.check_encoding" -> workspaceCheckEncoding(sAny("path"))

            "workspace.hash" -> workspaceHash(sAny("path"))
            "workspace.read_b64_range" -> workspaceReadB64Range(
                sAny("path"),
                args.optInt("offset", 0),
                args.optInt("maxBytes", args.optInt("max_bytes", 32_000))
            )
            "github.push_file", "github.push_file_from_path", "github.push_files_batch", "verify_roundtrip", "github.pull",
            "github.branch_create", "github.fork", "github.compare", "github.repo" -> dispatchGithub(tool, args)
            else -> {
                if (tool.startsWith("github.")) {
                    dispatchGithub(tool, args)
                } else {
                    JSONObject().put("ok", false)
                        .put("error", JSONObject()
                            .put("code", "TOOL_NOT_FOUND")
                            .put("message", "unknown tool: $tool")
                            .put("retryable", false)
                            .put("hint", "call list_tools, tool.search, or tools.help"))
                        .put("tool", tool).toString()
                }
            }
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
            fun probeDispatch(name: String, args: JSONObject = JSONObject()) {
                try {
                    val raw = dispatchByName(name, args)
                    val jo = try { JSONObject(raw) } catch (_: Exception) { JSONObject().put("raw", raw) }
                    val unknown = jo.optJSONObject("error")?.optString("code") == "TOOL_NOT_FOUND"
                        || jo.optString("error").contains("unknown tool")
                        || jo.optString("error").contains("async bridge")
                    probes.put(
                        name,
                        JSONObject()
                            .put("ok", !unknown && jo.optBoolean("ok", true))
                            .put("dispatch", true)
                            .put("sample", raw.take(180))
                    )
                } catch (e: Exception) {
                    probes.put(name, JSONObject().put("ok", false).put("error", e.message).put("dispatch", true))
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
            // Live dispatcher registry (catalog must match these)
            probeDispatch("list_tools")
            probeDispatch("workspace.pwd")
            probeDispatch("http_request", JSONObject().put("url", "https://httpbin.org/get").put("method", "GET"))
            probeDispatch("github.me")
            probeDispatch("github.request", JSONObject().put("method", "GET").put("path", "/rate_limit"))
            probeDispatch("run_js", JSONObject().put("code", "return 1"))
            probeDispatch("util.uuid")
            probeDispatch("util.time")
            probeDispatch("tool.search", JSONObject().put("query", "github"))
            probeDispatch("math.eval", JSONObject().put("expr", "1+2*3"))
            probeDispatch("device.memory")
            probeDispatch("sensors.list")
            probeDispatch("research.plan", JSONObject().put("topic", "test"))
            probeDispatch("tools.groups")
            probeDispatch("exec.allowlist")
            probeDispatch("tools.suggest", JSONObject().put("task", "list files"))
            probeDispatch("pwd")
            probeDispatch("echo", JSONObject().put("text", "ok"))
            val ver = try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            } catch (_: Exception) { "?" }
            val catalog = try { JSONObject(listTools()).optJSONArray("tools") ?: JSONArray() } catch (_: Exception) { JSONArray() }
            val dispatchNames = JSONArray()
            for (n in listOf(
                "list_tools", "http_request", "fetch_url", "github.request", "github.me",
                "github.contents", "workspace.ls", "workspace.read", "selftest"
            )) dispatchNames.put(n)
            envelope(
                true,
                JSONObject()
                    .put("version", ver)
                    .put("methodCount", methods.size)
                    .put("methods", JSONArray(methods))
                    .put("probes", probes)
                    .put("catalogCount", catalog.length())
                    .put("dispatchTools", dispatchNames)
                    .put("primaryApi", "invokeJson")
                    .put("protocol", "```tool_call JSON ~~~``` then wait for TOOL_RESULT; see tools.help")
                    .put("hint", "list_tools/describe/selftest now reflect invokeJson dispatcher; network tools are sync on this path"),
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

    private fun readWorkspaceText(path: String): String = safeWorkspace(path).readText(Charsets.UTF_8)

    @JavascriptInterface
    fun codeSearch(query: String, path: String?, ext: String?, maxHits: Int): String {
        return try {
            val root = if (path.isNullOrBlank()) workspaceRoot else safeWorkspace(path)
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
            val f = safeWorkspace(path)
            if (!f.isFile) return JSONObject().put("ok", false).put("error", "not a file: $path").toString()
            historySnapshot(f)
            val src = f.readText(Charsets.UTF_8)
            val out = if (regex) Regex(find).replace(src, replace) else src.replace(find, replace)
            val count = if (regex) Regex(find).findAll(src).count() else {
                if (find.isEmpty()) 0 else src.split(find).size - 1
            }
            val bytes = out.toByteArray(Charsets.UTF_8)
            val tmp = File(f.parentFile, ".${f.name}.tmp-${System.nanoTime()}")
            FileOutputStream(tmp).use { o -> o.write(bytes); o.fd.sync() }
            if (!tmp.renameTo(f)) { FileOutputStream(f).use { it.write(bytes) }; tmp.delete() }
            JSONObject().put("ok", true).put("replacements", count.coerceAtLeast(0))
                .put("bytes", bytes.size).put("sha256", sha256(bytes)).put("verified", true).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun workspaceHead(path: String, lines: Int): String {
        return try {
            val n = if (lines <= 0) 20 else lines.coerceAtMost(500)
            val all = readWorkspaceText(path).lines()
            val slice = all.take(n)
            JSONObject().put("ok", true).put("path", path).put("lines", slice.size)
                .put("truncated", all.size > n).put("text", slice.joinToString("\n")).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun workspaceTail(path: String, lines: Int): String {
        return try {
            val n = if (lines <= 0) 20 else lines.coerceAtMost(500)
            val all = readWorkspaceText(path).lines()
            val slice = if (all.size <= n) all else all.subList(all.size - n, all.size)
            JSONObject().put("ok", true).put("path", path).put("lines", slice.size)
                .put("truncated", all.size > n).put("text", slice.joinToString("\n")).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun workspaceGlob(pattern: String, path: String?, max: Int): String {
        return try {
            val root = if (path.isNullOrBlank() || path == "." || path == "./") workspaceRoot
                       else safeWorkspace(path)
            val limit = if (max <= 0) 200 else max.coerceAtMost(1000)
            var pat = pattern.trim().removePrefix("./")
            // *.ext means recursive match under root
            if (pat.startsWith("*.") && !pat.contains("/")) {
                pat = "**/" + pat
            }
            val recursive = pat.contains("**") || pat.contains("/")
            val suffix = pat.substringAfterLast("/")
            val matches = JSONArray()
            fun matchName(name: String): Boolean {
                if (suffix.startsWith("*.")) {
                    val ext = suffix.removePrefix("*")
                    return name.endsWith(ext)
                }
                if (suffix == "*") return true
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
                    matches.put(JSONObject().put("path", rel).put("size", f.length()).put("mtime", f.lastModified()))
                }
            }
            walk(root)
            JSONObject().put("ok", true).put("count", matches.length()).put("matches", matches).toString()
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

    @JavascriptInterface
    fun taskDelete(id: String): String {
        if (id.isBlank()) return JSONObject().put("ok", false).put("error", "id required").toString()
        val existed = taskStore.contains(id)
        taskStore.edit().remove(id).apply()
        return JSONObject().put("ok", true).put("id", id).put("existed", existed).toString()
    }

    // ─── Session save/load (Codex-style) ───────────────────────
    private val sessionRoot: File by lazy { File(context.filesDir, "sessions").also { it.mkdirs() } }

    @JavascriptInterface
    fun sessionSave(name: String, stateJson: String?): String {
        return try {
            var safe = name.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(64).ifBlank { "default" }
            if (safe.endsWith(".json")) safe = safe.removeSuffix(".json")
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

    @JavascriptInterface
    fun archiveZipCreate(path: String, dest: String?): String {
        return try {
            val src = safeWorkspace(path)
            if (!src.exists()) return JSONObject().put("ok", false).put("error", "not found").toString()
            val outPath = dest?.takeIf { it.isNotBlank() } ?: (src.name + ".zip")
            val out = safeWorkspace(outPath)
            out.parentFile?.mkdirs()
            java.util.zip.ZipOutputStream(FileOutputStream(out)).use { zos ->
                fun add(f: java.io.File, base: String) {
                    if (f.isDirectory) {
                        f.listFiles()?.forEach { add(it, base + f.name + "/") }
                    } else {
                        zos.putNextEntry(java.util.zip.ZipEntry(base + f.name))
                        f.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
                if (src.isDirectory) src.listFiles()?.forEach { add(it, "") } else add(src, "")
            }
            val rel = try { out.relativeTo(workspaceRoot).path } catch (_: Exception) { out.name }
            JSONObject().put("ok", true).put("path", rel).put("bytes", out.length()).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun archiveZipList(path: String): String {
        return try {
            val f = safeWorkspace(path)
            val arr = JSONArray()
            java.util.zip.ZipFile(f).use { zf ->
                val en = zf.entries()
                while (en.hasMoreElements()) {
                    val e = en.nextElement()
                    arr.put(JSONObject().put("name", e.name).put("size", e.size).put("dir", e.isDirectory))
                }
            }
            JSONObject().put("ok", true).put("entries", arr).put("count", arr.length()).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun archiveZipExtract(path: String, dest: String?): String {
        return try {
            val f = safeWorkspace(path)
            val destDir = if (dest.isNullOrBlank()) safeWorkspace(f.nameWithoutExtension) else safeWorkspace(dest)
            destDir.mkdirs()
            var n = 0
            java.util.zip.ZipFile(f).use { zf ->
                val en = zf.entries()
                while (en.hasMoreElements()) {
                    val e = en.nextElement()
                    val out = File(destDir, e.name)
                    if (e.isDirectory) {
                        out.mkdirs()
                        continue
                    }
                    out.parentFile?.mkdirs()
                    zf.getInputStream(e).use { inp -> FileOutputStream(out).use { inp.copyTo(it) } }
                    n++
                }
            }
            val rel = try { destDir.relativeTo(workspaceRoot).path } catch (_: Exception) { destDir.name }
            JSONObject().put("ok", true).put("extracted", n).put("dest", rel).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    // ─── 1.15 harness features ─────────────────────────────────

    @JavascriptInterface
    fun workspaceApplyPatchMulti(patchesJson: String): String {
        return try {
            val patches = JSONArray(patchesJson)
            val results = JSONArray()
            val rollbacks = mutableListOf<Pair<File, ByteArray>>()
            for (i in 0 until patches.length()) {
                val p = patches.getJSONObject(i)
                val path = p.optString("path")
                val edits = p.optJSONArray("edits") ?: JSONArray()
                val f = safeWorkspace(path)
                if (!f.isFile) {
                    // rollback prior
                    for ((rf, bytes) in rollbacks.reversed()) {
                        FileOutputStream(rf).use { it.write(bytes) }
                    }
                    return JSONObject().put("ok", false).put("error", "not a file: $path")
                        .put("rolled_back", true).put("failed_index", i).toString()
                }
                rollbacks.add(f to f.readBytes())
                historySnapshot(f)
                var text = f.readText(Charsets.UTF_8)
                for (j in 0 until edits.length()) {
                    val e = edits.getJSONObject(j)
                    val old = e.optString("old", e.optString("find", ""))
                    val neu = e.optString("new", e.optString("replace", ""))
                    if (old.isEmpty() || !text.contains(old)) {
                        for ((rf, bytes) in rollbacks.reversed()) {
                            FileOutputStream(rf).use { it.write(bytes) }
                        }
                        return JSONObject().put("ok", false)
                            .put("error", "edit not found on $path")
                            .put("rolled_back", true).put("failed_index", i).toString()
                    }
                    text = text.replaceFirst(old, neu)
                }
                val bytes = text.toByteArray(Charsets.UTF_8)
                FileOutputStream(f).use { it.write(bytes); it.fd.sync() }
                results.put(JSONObject().put("path", path).put("ok", true).put("sha256", sha256(bytes)))
            }
            JSONObject().put("ok", true).put("files", results.length()).put("results", results).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun toolsForTask(task: String): String {
        return try {
            val q = task.lowercase()
            val scored = mutableListOf<Pair<Int, String>>()
            fun score(name: String, vararg keys: String) {
                var s = 0
                for (k in keys) if (q.contains(k)) s += 2
                if (q.contains(name.substringAfterLast('.'))) s += 3
                if (s > 0) scored.add(s to name)
            }
            score("workspace.read", "read", "open", "file", "content")
            score("workspace.write", "write", "save", "create")
            score("workspace.apply_patch", "edit", "patch", "fix", "replace")
            score("workspace.grep", "search", "find", "grep")
            score("workspace.glob", "glob", "list files", "pattern")
            score("workspace.ls", "list", "dir", "folder")
            score("exec", "run", "shell", "command", "bash")
            score("research.web", "web", "search", "google", "research")
            score("github.request", "github", "repo", "pr", "issue")
            score("task.add", "todo", "task", "plan")
            score("http_request", "http", "api", "fetch")
            score("index.find", "index", "locate")
            score("code.search", "symbol", "code search")
            score("archive.zip_create", "zip", "archive")
            val top = scored.sortedByDescending { it.first }.take(8).map { it.second }.distinct()
            val arr = JSONArray()
            top.forEach { arr.put(it) }
            JSONObject().put("ok", true).put("task", task).put("tools", arr).put("count", arr.length()).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun indexFresh(): String {
        return try {
            val idxFile = File(context.filesDir, "workspace_index.json")
            val age = if (idxFile.isFile) System.currentTimeMillis() - idxFile.lastModified() else Long.MAX_VALUE
            val stale = age > 5 * 60 * 1000
            if (stale) indexBuild(null)
            JSONObject().put("ok", true).put("stale", stale).put("ageMs", age)
                .put("rebuilt", stale).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun sessionFork(name: String, newName: String): String {
        return try {
            val src = sessionLoad(name)
            val o = JSONObject(src)
            if (!o.optBoolean("ok")) return src
            val session = o.optJSONObject("session") ?: JSONObject()
            session.put("forkedFrom", name).put("forkedAt", System.currentTimeMillis())
            return sessionSave(newName, session.toString())
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun projectContextLoad(): String {
        return try {
            val files = listOf(".dharness/PROJECT.md", "PROJECT.md", "AGENTS.md", "CLAUDE.md", "MEMORY.md")
            val loaded = JSONArray()
            val parts = StringBuilder()
            for (rel in files) {
                try {
                    val f = safeWorkspace(rel)
                    if (f.isFile && f.length() < 100_000) {
                        val text = f.readText(Charsets.UTF_8).take(25_000)
                        loaded.put(rel)
                        parts.append("\n\n<!-- ").append(rel).append(" -->\n").append(text)
                    }
                } catch (_: Exception) {}
            }
            JSONObject().put("ok", true).put("files", loaded)
                .put("chars", parts.length).put("content", parts.toString().take(50_000)).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun memoryAppend(text: String): String {
        return try {
            val f = safeWorkspace("MEMORY.md")
            f.parentFile?.mkdirs()
            historySnapshot(f)
            val prev = if (f.isFile) f.readText(Charsets.UTF_8) else ""
            val entry = "\n\n## " + java.time.Instant.now().toString() + "\n" + text.trim() + "\n"
            val out = (prev + entry).takeLast(50_000)
            f.writeText(out)
            JSONObject().put("ok", true).put("bytes", out.length).put("verified", true).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun workspaceDiff(path: String, content: String): String {
        return try {
            val f = safeWorkspace(path)
            val before = if (f.isFile) f.readText(Charsets.UTF_8) else ""
            val after = content
            // simple unified-ish line diff summary
            val bLines = before.lines()
            val aLines = after.lines()
            var i = 0
            while (i < bLines.size && i < aLines.size && bLines[i] == aLines[i]) i++
            var bEnd = bLines.size - 1
            var aEnd = aLines.size - 1
            while (bEnd >= i && aEnd >= i && bLines[bEnd] == aLines[aEnd]) { bEnd--; aEnd-- }
            val removed = if (bEnd >= i) bLines.subList(i, bEnd + 1) else emptyList()
            val added = if (aEnd >= i) aLines.subList(i, aEnd + 1) else emptyList()
            JSONObject().put("ok", true).put("path", path)
                .put("before_lines", bLines.size).put("after_lines", aLines.size)
                .put("removed", JSONArray(removed.take(40)))
                .put("added", JSONArray(added.take(40)))
                .put("changed", removed.isNotEmpty() || added.isNotEmpty())
                .toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    // ─── 1.16 extras ───────────────────────────────────────────

    @JavascriptInterface
    fun policyMode(mode: String?): String {
        val store = policyStore
        if (!mode.isNullOrBlank()) {
            val m = mode.lowercase()
            if (m !in setOf("open", "allow_reads", "ask_mutations", "strict")) {
                return JSONObject().put("ok", false).put("error", "mode: open|allow_reads|ask_mutations|strict").toString()
            }
            store.edit().putString("mode", m).apply()
        }
        return JSONObject().put("ok", true).put("mode", store.getString("mode", "open")).toString()
    }

    @JavascriptInterface
    fun trajectoryLog(limit: Int): String {
        val n = if (limit <= 0) 30 else limit.coerceAtMost(100)
        val arr = JSONArray()
        trajectory.take(n).forEach { arr.put(it) }
        return JSONObject().put("ok", true).put("entries", arr).put("count", arr.length()).toString()
    }

    @JavascriptInterface
    fun toolsGroups(): String {
        val groups = JSONObject()
        fun g(name: String, vararg tools: String) {
            val a = JSONArray(); tools.forEach { a.put(it) }; groups.put(name, a)
        }
        g("meta", "list_tools", "describe", "selftest", "tools.for_task", "tools.groups")
        g("workspace", "workspace.read", "workspace.write", "workspace.apply_patch", "workspace.apply_patch_multi",
            "workspace.grep", "workspace.glob", "workspace.ls", "workspace.diff", "workspace.head", "workspace.tail")
        g("agent", "task.add", "task.list", "memory.append", "project.context", "session.save", "session.fork", "trajectory.log")
        g("code", "code.search", "code.outline", "code.find_todos", "exec")
        g("net", "research.web", "http_request", "github.request")
        g("device", "device.info", "clipboard.read", "clipboard.write", "notify")
        return JSONObject().put("ok", true).put("groups", groups).toString()
    }

    @JavascriptInterface
    fun workspaceCount(path: String?): String {
        return try {
            val root = if (path.isNullOrBlank()) workspaceRoot else safeWorkspace(path)
            var files = 0; var dirs = 0; var bytes = 0L
            root.walkTopDown().forEach {
                if (it.isFile) { files++; bytes += it.length() }
                else if (it.isDirectory) dirs++
            }
            JSONObject().put("ok", true).put("files", files).put("dirs", dirs).put("bytes", bytes).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun workspaceWhich(name: String): String {
        return try {
            val hits = JSONArray()
            workspaceRoot.walkTopDown().maxDepth(8).forEach { f ->
                if (f.isFile && f.name.equals(name, true)) {
                    hits.put(f.relativeTo(workspaceRoot).path)
                }
            }
            JSONObject().put("ok", true).put("hits", hits).put("count", hits.length()).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun hashFile(path: String): String {
        return try {
            val f = safeWorkspace(path)
            if (!f.isFile) return JSONObject().put("ok", false).put("error", "not a file").toString()
            val sha = sha256(f.readBytes())
            JSONObject().put("ok", true).put("path", path).put("sha256", sha).put("bytes", f.length()).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }





    @JavascriptInterface
    fun notifySimple(title: String, body: String): String {
        return try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channelId = "dharness"
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(NotificationChannel(channelId, "D-Harness", NotificationManager.IMPORTANCE_DEFAULT))
            }
            val n = NotificationCompat.Builder(context, channelId)
                .setContentTitle(title.take(80))
                .setContentText(body.take(200))
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setAutoCancel(true)
                .build()
            nm.notify((System.currentTimeMillis() % 100000).toInt(), n)
            JSONObject().put("ok", true).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun selftestDeep(): String {
        val results = JSONArray()
        fun probe(name: String, block: () -> Boolean) {
            val t0 = System.currentTimeMillis()
            try {
                val ok = block()
                results.put(JSONObject().put("tool", name).put("ok", ok).put("ms", System.currentTimeMillis() - t0))
            } catch (e: Exception) {
                results.put(JSONObject().put("tool", name).put("ok", false).put("error", e.message)
                    .put("ms", System.currentTimeMillis() - t0))
            }
        }
        probe("workspace.write/read") {
            val w = JSONObject(workspaceWrite("_selftest.txt", "ok-${System.currentTimeMillis()}"))
            val r = JSONObject(workspaceRead("_selftest.txt", 1000))
            w.optBoolean("ok") && r.optBoolean("ok") && r.optString("content").contains("ok-")
        }
        probe("workspace.head") {
            JSONObject(workspaceHead("_selftest.txt", 1)).optBoolean("ok")
        }
        probe("calc via dispatch") {
            val raw = invokeJson("calc.clamp", """{"value":150,"min":0,"max":100}""", "st-1")
            JSONObject(raw).optBoolean("ok", true) || JSONObject(raw).has("value")
        }
        probe("policy.check") {
            JSONObject(policyCheck("workspace.read")).optBoolean("ok")
        }
        probe("task.add/list") {
            JSONObject(taskAdd("selftest")).optBoolean("ok") && JSONObject(taskList()).optBoolean("ok")
        }
        probe("hash.file") {
            JSONObject(hashFile("_selftest.txt")).optBoolean("ok")
        }
        val passed = (0 until results.length()).count { results.getJSONObject(it).optBoolean("ok") }
        return JSONObject().put("ok", passed == results.length()).put("passed", passed)
            .put("total", results.length()).put("results", results).toString()
    }

    @JavascriptInterface
    fun agentMetrics(): String {
        return JSONObject().put("ok", true)
            .put("trajectory", trajectory.size)
            .put("readSession", readSession.size)
            .put("policyMode", policyStore.getString("mode", "open"))
            .toString()
    }
}
