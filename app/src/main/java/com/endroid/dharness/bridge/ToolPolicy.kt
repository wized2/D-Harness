package com.endroid.dharness.bridge

import java.io.InputStream
import java.net.URI

/** Pure helpers for tool policy, path cleaning and bounded reads. Unit tested; no Android deps. */
object ToolPolicy {

    private val MUTATING = setOf(
        // files
        "workspace.write", "workspace.write_b64", "workspace.append", "workspace.replace",
        "workspace.apply_patch", "workspace.mkdir", "workspace.rm", "workspace.mv", "workspace.cp",
        "fs.write", "fs.delete", "fs.append", "fs.mkdir", "fs.touch", "fs.copy", "fs.move",
        "file.commit", "file.append", "file.save", "archive.zip_create", "http.fetch_to_file",
        "mkdir", "rm", "cp", "mv", "touch", "chmod", "ln", "tee", "sed", "tar", "gzip", "unzip", "wget",
        // processes
        "exec", "exec.lang", "shell", "sh", "cmd", "term", "terminal", "run", "run_js",
        "kill", "process.kill", "am", "pm.install", "input.text", "input.key", "toybox.run", "xargs",
        // network / GitHub writes
        "github.request", "github.pull", "github.diff", "github.pr_create", "github.pr_ready",
        "github.pr_merge", "github.push_file", "github.push_file_from_path", "github.push_files_batch",
        "github.fork", "github.issue_create", "github.issue_update", "github.issue_comment",
        "github.pr_comment", "github.branch_create", "github.gist_create", "verify_roundtrip",
        // agent state and secrets
        "keys.set", "keys.delete", "memory.set", "memory.delete", "memory.clear", "memory.append",
        "note.set", "task.add", "task.update", "task.delete", "task.clear", "session.save", "session.fork",
        // device
        "settings.put", "torch.set", "audio.volume", "audio.ringer", "wakelock.acquire", "wakelock.release",
        "clipboard.write", "clipboard.copy", "share", "notify", "vibrate", "intent.open_url"
    )

    /** True when the tool changes state outside the model's own conversation. Exact names only. */
    fun isMutating(tool: String): Boolean = tool in MUTATING

    /**
     * Whether a policy rule applies to a tool: exact name, "*" for everything, "group" or "group.*"
     * for a dotted group. A rule never matches by substring ("rm" does not match "time.format").
     */
    fun ruleMatches(tool: String, rule: String): Boolean {
        val r = rule.trim()
        if (r.isEmpty()) return false
        if (r == "*" || r == tool) return true
        if (r.endsWith(".*")) return tool.startsWith(r.dropLast(1))
        return tool.startsWith("$r.")
    }

    /**
     * Splits a relative path into segments and rejects any ".." segment instead of silently
     * deleting the dots (which turned "a/../b" into "a/b"). "." and empty segments are dropped.
     */
    fun cleanRelative(path: String): String {
        val segments = path.trim().removePrefix("/").split('/', '\\').filter { it.isNotEmpty() && it != "." }
        require(segments.none { it == ".." }) { "path escapes workspace" }
        return segments.joinToString("/")
    }

    /** True when [child] is [root] or inside it. A sibling like "/ws-evil" is NOT inside "/ws". */
    fun isInside(rootPath: String, childPath: String): Boolean =
        childPath == rootPath || childPath.startsWith(rootPath.trimEnd('/') + "/")

    /**
     * Resolves what `github.request` may call. A bare path goes to api.github.com; a full URL is
     * accepted only for https://api.github.com. Anything else returns null so the token is never
     * sent to another host.
     */
    fun githubUrl(path: String): String? {
        val p = path.trim()
        if (p.startsWith("http://") || p.startsWith("https://")) {
            val u = try { URI(p) } catch (_: Exception) { return null }
            return if (u.scheme == "https" && u.host == "api.github.com" && u.userInfo == null) p else null
        }
        if (p.contains("://")) return null
        return "https://api.github.com" + (if (p.startsWith("/")) p else "/$p")
    }

    /**
     * Reads at most [maxChars] characters and discards the rest. Draining (instead of stopping)
     * keeps a child process from blocking on a full pipe, while memory stays bounded.
     */
    fun readCapped(input: InputStream, maxChars: Int): String {
        val sb = StringBuilder()
        val reader = input.bufferedReader()
        val buf = CharArray(8192)
        while (true) {
            val n = reader.read(buf)
            if (n < 0) break
            val room = maxChars - sb.length
            if (room > 0) sb.append(buf, 0, minOf(n, room))
        }
        return sb.toString()
    }
}
