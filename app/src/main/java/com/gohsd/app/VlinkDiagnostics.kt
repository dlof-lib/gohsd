package com.gohsd.app

import android.util.Base64
import java.net.HttpURLConnection
import java.net.URL

/**
 * يقرأ صفحات لوحة راوتر V-Link (مصادقة Basic) لإنشاء تقرير أبني عليه دعماً أصلياً كاملاً.
 * يتجاوز الصفحات الخطرة (إعادة تشغيل/تصفير/ترقية/تسجيل خروج) ويخفي كلمات السر قدر الإمكان.
 */
object VlinkDiagnostics {

    private val skip = Regex("(?i)reboot|restart|reset|restore|default|upgrade|firmware|logout|logoff|factory|save|apply|backup")
    private val okPage = Regex("(?i)\\.(htm|html|asp|cgi|shtml)(\\?.*)?$|/$")
    private val linkRx = Regex("(?i)(?:href|src|action)\\s*=\\s*[\"']?([^\"'\\s>#]+)")
    private val jsRx = Regex("[\"']([\\w./-]+\\.(?:htm|html|asp|cgi|shtml))[\"']")
    private val inputRx = Regex("(?i)<input[^>]*>")
    private val secretTag = Regex("(?i)(pass|psk|key|pwd|secret)")
    private val valueRx = Regex("(?i)(value\\s*=\\s*[\"'])[^\"']*([\"'])")
    private val jsSecretRx = Regex("(?i)(\\w*(?:pass|psk|pwd|key)\\w*\\s*[=:]\\s*[\"'])[^\"']*([\"'])")

    fun run(address: String, user: String, pass: String, maxPages: Int = 30): String {
        val base = (if (address.startsWith("http")) address else "http://$address").trimEnd('/') + "/"
        val auth = "Basic " + Base64.encodeToString("$user:$pass".toByteArray(), Base64.NO_WRAP)
        val sb = StringBuilder("GOHSD V-Link diagnostics\nbase=$base\n\n")
        val queue = ArrayDeque<String>()
        queue.add(base)
        listOf("index.htm", "status.htm", "home.htm", "wireless.htm", "wlbasic.htm", "wlstatbl.htm", "wlwpa.htm")
            .forEach { queue.add(base + it) }
        val seen = HashSet<String>()

        while (queue.isNotEmpty() && seen.size < maxPages && sb.length < 150_000) {
            val url = queue.removeFirst()
            if (!seen.add(url)) continue
            val (code, body) = fetch(url, auth)
            sb.append("=== ").append(url).append(" [").append(code).append("] ===\n")
            if (code != 200) { sb.append(if (code <= 0) body else "").append("\n"); continue }
            sb.append(scrub(body)).append("\n\n")
            for (l in extractLinks(url, body, base)) if (l !in seen) queue.add(l)
        }
        return sb.toString()
    }

    private fun fetch(url: String, auth: String): Pair<Int, String> = try {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 5000
            c.readTimeout = 8000
            c.setRequestProperty("Authorization", auth)
            val code = c.responseCode
            val body = if (code == 200) c.inputStream.bufferedReader().use { it.readText() } else ""
            code to body
        } finally {
            c.disconnect()
        }
    } catch (e: Exception) {
        -1 to "error: ${e.message}"
    }

    private fun extractLinks(current: String, body: String, base: String): Set<String> {
        val host = URL(base).host
        val out = LinkedHashSet<String>()
        for (m in linkRx.findAll(body) + jsRx.findAll(body)) {
            val raw = m.groupValues[1]
            if (raw.startsWith("javascript:") || raw.startsWith("mailto:")) continue
            val abs = try { URL(URL(current), raw).toString() } catch (e: Exception) { continue }
            if (URL(abs).host != host) continue
            if (okPage.containsMatchIn(abs) && !skip.containsMatchIn(abs)) out += abs
        }
        return out
    }

    private fun scrub(body: String): String {
        var s = inputRx.replace(body) { m ->
            if (secretTag.containsMatchIn(m.value)) valueRx.replace(m.value, "\$1***\$2") else m.value
        }
        s = jsSecretRx.replace(s, "\$1***\$2")
        return s.take(20_000)
    }
}
