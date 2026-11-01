package com.endroid.dharness

object UserAgentHelper {
    private const val APP_TAG = "DHarness"

    /** Strip WebView markers so sites treat us closer to mobile Chrome. */
    fun chromeLikeMobile(defaultUa: String): String {
        return defaultUa
            .replace(Regex(""";\s*wv(?=\))"""), "")
            .replace(Regex("""\bVersion/\d+(?:\.\d+)*\s*"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim() + " $APP_TAG/${BuildConfig.VERSION_NAME}"
    }

    fun desktopLike(defaultUa: String): String {
        val chrome = Regex("""Chrome/[\d.]+""").find(defaultUa)?.value ?: "Chrome/120.0.0.0"
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) $chrome Safari/537.36 $APP_TAG/${BuildConfig.VERSION_NAME}"
    }
}
