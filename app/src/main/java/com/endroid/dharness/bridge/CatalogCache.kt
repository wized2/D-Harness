package com.endroid.dharness.bridge

import org.json.JSONArray
import org.json.JSONObject

/**
 * Cache expensive tool-catalog JSON for the WebView session.
 * Invalidate when the catalog schema version changes.
 */
object CatalogCache {
    const val SCHEMA = 2
    @Volatile private var listJson: String? = null
    @Volatile private var builtSchema: Int = -1

    fun getListOrBuild(build: () -> String): String {
        val cached = listJson
        if (cached != null && builtSchema == SCHEMA) return cached
        synchronized(this) {
            if (listJson != null && builtSchema == SCHEMA) return listJson!!
            val built = build()
            listJson = built
            builtSchema = SCHEMA
            return built
        }
    }

    fun invalidate() {
        synchronized(this) {
            listJson = null
            builtSchema = -1
        }
    }

    fun truncatePayload(text: String, maxChars: Int = 80_000): JSONObject {
        if (text.length <= maxChars) {
            return JSONObject().put("text", text).put("truncated", false)
        }
        return JSONObject()
            .put("text", text.take(maxChars) + "\n…[truncated]")
            .put("truncated", true)
            .put("originalChars", text.length)
    }
}
