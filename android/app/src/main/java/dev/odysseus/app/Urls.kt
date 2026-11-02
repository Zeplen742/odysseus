package dev.odysseus.app

import java.net.URI

/** URL rules for the web shell, kept free of Android types so they are unit-testable. */
object Urls {
    /**
     * Turns what the user typed into a base URL ("scheme://host[:port]"), or null.
     * A bare host gets https://, except private LAN addresses, which are usually plain http.
     */
    fun normalizeBase(input: String): String? {
        var s = input.trim().trimEnd('/')
        if (s.isEmpty()) return null
        if (!s.contains("://")) s = (if (looksLan(s)) "http://" else "https://") + s
        val u = try { URI(s) } catch (_: Exception) { return null }
        val scheme = u.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = u.host ?: return null
        val port = if (u.port == -1) "" else ":${u.port}"
        return "$scheme://${host.lowercase()}$port"
    }

    private fun looksLan(s: String): Boolean {
        val host = s.substringBefore('/').substringBefore(':').lowercase()
        return host == "localhost" || host.endsWith(".local") ||
            host.startsWith("10.") || host.startsWith("192.168.") ||
            Regex("^172\\.(1[6-9]|2\\d|3[01])\\.").containsMatchIn(host) ||
            !host.contains('.')
    }

    /** "scheme://host[:port]" of [url] with default ports dropped, or null. */
    fun origin(url: String): String? {
        val u = try { URI(url) } catch (_: Exception) { return null }
        val scheme = u.scheme?.lowercase() ?: return null
        val host = u.host?.lowercase() ?: return null
        val port = when {
            u.port == -1 -> ""
            scheme == "https" && u.port == 443 -> ""
            scheme == "http" && u.port == 80 -> ""
            else -> ":${u.port}"
        }
        return "$scheme://$host$port"
    }

    /** True when [url] belongs to the configured server, so it stays inside the app. */
    fun isServerUrl(base: String, url: String): Boolean {
        val o = origin(url) ?: return false
        return o == origin(base)
    }

    /** Server session ids are uuid-like; anything else from the page is ignored. */
    fun isSessionId(s: String?): Boolean =
        s != null && s.length in 1..128 && s.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    fun chatUrl(base: String, session: String) = "$base/#$session"
}
