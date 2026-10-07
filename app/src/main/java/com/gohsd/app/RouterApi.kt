package com.gohsd.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * عميل لراوتر OpenWrt (مفتوح المصدر) عبر ubus JSON-RPC.
 * يتطلب حزمة uhttpd-mod-ubus وحساب مدير الراوتر.
 */
class RouterApi(baseUrl: String) {

    private val endpoint = baseUrl.trim().trimEnd('/')
        .let { if (it.startsWith("http")) it else "http://$it" } + "/ubus"
    private var session = "00000000000000000000000000000000"
    private var rpcId = 0

    private fun post(body: JSONObject): JSONObject {
        val c = URL(endpoint).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 5000
            c.readTimeout = 15000
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
                ?: throw IOException("HTTP ${c.responseCode}")
            return JSONObject(stream.bufferedReader().use { it.readText() })
        } finally {
            c.disconnect()
        }
    }

    fun call(obj: String, method: String, args: JSONObject = JSONObject()): JSONObject {
        val req = JSONObject()
            .put("jsonrpc", "2.0").put("id", ++rpcId).put("method", "call")
            .put("params", JSONArray().put(session).put(obj).put(method).put(args))
        val res = post(req)
        if (res.has("error")) throw IOException(res.getJSONObject("error").optString("message", "error"))
        val arr = res.getJSONArray("result")
        val code = arr.getInt(0)
        if (code != 0) throw IOException("ubus code $code")
        return arr.optJSONObject(1) ?: JSONObject()
    }

    fun login(user: String, pass: String) {
        session = "00000000000000000000000000000000"
        val r = call(
            "session", "login",
            JSONObject().put("username", user).put("password", pass).put("timeout", 3600)
        )
        session = r.getString("ubus_rpc_session")
    }

    private fun listObjects(pattern: String): List<String> {
        val req = JSONObject().put("jsonrpc", "2.0").put("id", ++rpcId)
            .put("method", "list").put("params", JSONArray().put(pattern))
        val result = post(req).optJSONObject("result") ?: return emptyList()
        return result.keys().asSequence().toList()
    }

    // ---------- الأجهزة المتصلة ----------
    fun clients(): List<Client> {
        val leases = leases()
        val out = ArrayList<Client>()
        for (iface in listObjects("hostapd.*")) {
            val cl = try {
                call(iface, "get_clients").optJSONObject("clients")
            } catch (e: IOException) { null } ?: continue
            for (mac in cl.keys()) {
                val c = cl.getJSONObject(mac)
                if (!c.optBoolean("assoc", true)) continue
                val b = c.optJSONObject("bytes")
                val m = mac.lowercase()
                out += Client(
                    mac = m,
                    ip = leases[m]?.first,
                    name = leases[m]?.second,
                    iface = iface,
                    signal = if (c.has("signal")) c.optInt("signal") else null,
                    rx = b?.optLong("rx", 0) ?: 0,
                    tx = b?.optLong("tx", 0) ?: 0
                )
            }
        }
        return out
    }

    private fun leases(): Map<String, Pair<String, String?>> {
        val data = try {
            call("file", "read", JSONObject().put("path", "/tmp/dhcp.leases")).optString("data")
        } catch (e: IOException) { "" }
        return data.lineSequence().mapNotNull { line ->
            val p = line.trim().split(" ")
            if (p.size >= 4) p[1].lowercase() to (p[2] to p[3].takeIf { it != "*" }) else null
        }.toMap()
    }

    // ---------- الواي فاي ----------
    fun wifiSections(): List<WifiSection> {
        val values = call("uci", "get", JSONObject().put("config", "wireless"))
            .optJSONObject("values") ?: return emptyList()
        return values.keys().asSequence().mapNotNull { name ->
            val s = values.getJSONObject(name)
            if (s.optString(".type") != "wifi-iface" || s.optString("mode", "ap") != "ap") null
            else WifiSection(
                name = name,
                ssid = s.optString("ssid"),
                encryption = s.optString("encryption").ifEmpty { null },
                maclist = readList(s, "maclist"),
                macfilter = s.optString("macfilter").ifEmpty { null }
            )
        }.toList()
    }

    private fun readList(s: JSONObject, key: String): List<String> {
        val a = s.optJSONArray(key)
        if (a != null) return List(a.length()) { a.getString(it).lowercase() }
        return s.optString(key).takeIf { it.isNotEmpty() }?.let { listOf(it.lowercase()) } ?: emptyList()
    }

    private fun uciSet(section: String, values: JSONObject) {
        call("uci", "set", JSONObject().put("config", "wireless").put("section", section).put("values", values))
    }

    private fun uciDelete(section: String, vararg options: String) {
        try {
            call(
                "uci", "delete",
                JSONObject().put("config", "wireless").put("section", section)
                    .put("options", JSONArray(options.toList()))
            )
        } catch (_: IOException) { }
    }

    private fun commit() {
        call("uci", "commit", JSONObject().put("config", "wireless"))
    }

    private fun reloadWifi() {
        try {
            call("file", "exec", JSONObject().put("command", "/sbin/wifi").put("params", JSONArray().put("reload")))
        } catch (_: IOException) { /* قد ينقطع الاتصال أثناء إعادة التشغيل */ }
    }

    /** تغيير اسم الشبكة و/أو كلمة السر لكل الشبكات اللاسلكية. null = بدون تغيير. */
    fun updateWifi(ssid: String?, key: String?) {
        require(key == null || key.length in 8..63) { "كلمة السر يجب أن تكون 8-63 حرفاً" }
        for (s in wifiSections()) {
            val v = JSONObject()
            ssid?.let { v.put("ssid", it) }
            if (key != null) {
                v.put("key", key)
                if (s.encryption == null || s.encryption == "none") v.put("encryption", "psk2")
            }
            uciSet(s.name, v)
        }
        commit()
        reloadWifi()
    }

    /** تحسين الشبكة: قناة تلقائية + رفع أي تحديد لقدرة الإرسال ثم إعادة تحميل الواي فاي. */
    fun optimizeWifi() {
        val values = call("uci", "get", JSONObject().put("config", "wireless"))
            .optJSONObject("values") ?: return
        for (name in values.keys()) {
            if (values.getJSONObject(name).optString(".type") == "wifi-device") {
                uciSet(name, JSONObject().put("channel", "auto"))
                uciDelete(name, "txpower")
            }
        }
        commit()
        reloadWifi()
    }

    // ---------- الحظر والطرد ----------
    fun kick(mac: String) {
        for (iface in listObjects("hostapd.*")) {
            try {
                call(
                    iface, "del_client",
                    JSONObject().put("addr", mac).put("reason", 5).put("deauth", true).put("ban_time", 60000)
                )
            } catch (_: IOException) { }
        }
    }

    fun setBlocked(mac: String, blocked: Boolean) {
        val m = mac.lowercase()
        for (s in wifiSections()) {
            val list = s.maclist.toMutableList()
            if (blocked) { if (m !in list) list.add(m) } else list.remove(m)
            if (list.isEmpty()) {
                uciDelete(s.name, "maclist")
                if (s.macfilter == "deny") uciDelete(s.name, "macfilter")
            } else {
                val v = JSONObject().put("maclist", JSONArray(list))
                if (blocked) v.put("macfilter", "deny")
                uciSet(s.name, v)
            }
        }
        commit()
        reloadWifi()
        if (blocked) kick(m)
    }

    fun reboot() {
        call("system", "reboot")
    }
}
