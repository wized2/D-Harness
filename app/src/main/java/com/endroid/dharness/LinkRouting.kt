package com.endroid.dharness

import android.net.Uri

/** Keep auth / captcha / chat in WebView; open other http(s) links externally. */
object LinkRouting {

    fun isDeepseekHost(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val h = host.lowercase()
        return h == "chat.deepseek.com" || h == "deepseek.com" || h.endsWith(".deepseek.com")
    }

    fun isGoogleAuthHost(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val h = host.lowercase()
        return h == "accounts.google.com" ||
            h == "www.google.com" ||
            h == "google.com" ||
            h.endsWith(".google.com") && (
                h.startsWith("accounts.") ||
                    h.startsWith("login.") ||
                    h.contains("oauth") ||
                    h == "myaccount.google.com"
                ) ||
            h.endsWith(".googleusercontent.com") ||
            h.endsWith(".gstatic.com") ||
            h == "accounts.youtube.com"
    }

    fun isHcaptchaHost(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val h = host.lowercase()
        return h == "hcaptcha.com" || h.endsWith(".hcaptcha.com")
    }

    fun shouldOpenExternally(uri: Uri?): Boolean {
        if (uri == null) return false
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "http" && scheme != "https") return false
        val host = uri.host
        if (isDeepseekHost(host)) return false
        if (isGoogleAuthHost(host)) return false
        if (isHcaptchaHost(host)) return false
        // Keep Google identity flows; open generic APIs externally
        if (host?.endsWith("googleapis.com") == true) return true
        return true
    }

    fun shouldCapturePopupInApp(uri: Uri?): Boolean {
        if (uri == null) return false
        val host = uri.host
        return isDeepseekHost(host) || isGoogleAuthHost(host) || isHcaptchaHost(host)
    }
}
