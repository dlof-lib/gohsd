package com.gohsd.app

/** يقرر هل الرابط يبقى داخل التطبيق أم يُفتح في تطبيق خارجي. */
class UrlPolicy(homeHost: String) {

    private val base = homeHost.lowercase().removePrefix("www.")

    fun isInternal(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val h = host.lowercase()
        return h == base || h.endsWith(".$base")
    }
}
