package com.endroid.dharness

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val tag: String,
    val name: String,
    val body: String,
    val htmlUrl: String,
    val apkUrl: String?
)

object UpdateChecker {
    private const val API =
        "https://api.github.com/repos/wized2/D-Harness/releases/latest"

    fun fetchLatest(): UpdateInfo? {
        return try {
            val conn = (URL(API).openConnection() as HttpURLConnection).apply {
                connectTimeout = 12_000
                readTimeout = 12_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "D-Harness-UpdateCheck")
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream.bufferedReader().use { it.readText() }
            if (code !in 200..299) return null
            val json = JSONObject(text)
            val tag = json.optString("tag_name").removePrefix("v")
            var apk: String? = null
            val assets = json.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    val n = a.optString("name")
                    if (n.endsWith(".apk", ignoreCase = true)) {
                        apk = a.optString("browser_download_url")
                        break
                    }
                }
            }
            UpdateInfo(
                tag = tag,
                name = json.optString("name", tag),
                body = json.optString("body", ""),
                htmlUrl = json.optString("html_url"),
                apkUrl = apk
            )
        } catch (_: Exception) {
            null
        }
    }

    /** true if remote is newer than installed (simple dotted numeric compare). */
    fun isNewer(remote: String, installed: String): Boolean {
        fun parts(s: String) = s.trim().removePrefix("v")
            .split('.', '-')
            .mapNotNull { it.toIntOrNull() }
        val a = parts(remote)
        val b = parts(installed)
        val n = maxOf(a.size, b.size)
        for (i in 0 until n) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
