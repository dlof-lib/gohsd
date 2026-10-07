package com.gohsd.app

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject

/** تخزين مشفّر (Keystore) لبيانات الراوتر والإعدادات. */
class Prefs(ctx: Context) {

    private val sp: SharedPreferences = EncryptedSharedPreferences.create(
        ctx.applicationContext,
        "gohsd_secure",
        MasterKey.Builder(ctx.applicationContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    var routerUrl: String
        get() = sp.getString("url", "192.168.1.1")!!
        set(v) = sp.edit().putString("url", v).apply()
    var username: String
        get() = sp.getString("user", "root")!!
        set(v) = sp.edit().putString("user", v).apply()
    var password: String
        get() = sp.getString("pass", "")!!
        set(v) = sp.edit().putString("pass", v).apply()
    var monitorEnabled: Boolean
        get() = sp.getBoolean("monitor", false)
        set(v) = sp.edit().putBoolean("monitor", v).apply()
    var autoBlock: Boolean
        get() = sp.getBoolean("autoblock", false)
        set(v) = sp.edit().putBoolean("autoblock", v).apply()
    var baselineDone: Boolean
        get() = sp.getBoolean("baseline", false)
        set(v) = sp.edit().putBoolean("baseline", v).apply()
    var knownMacs: Set<String>
        get() = sp.getStringSet("known", emptySet())!!.toSet()
        set(v) = sp.edit().putStringSet("known", v).apply()

    fun nicknames(): Map<String, String> {
        val o = JSONObject(sp.getString("nicks", "{}")!!)
        return o.keys().asSequence().associateWith { o.getString(it) }
    }

    fun setNickname(mac: String, name: String) {
        val o = JSONObject(sp.getString("nicks", "{}")!!)
        if (name.isBlank()) o.remove(mac) else o.put(mac, name)
        sp.edit().putString("nicks", o.toString()).apply()
    }
}
