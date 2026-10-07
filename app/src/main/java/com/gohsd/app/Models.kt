package com.gohsd.app

data class Client(
    val mac: String,
    val ip: String?,
    val name: String?,
    val iface: String,
    val signal: Int?,
    val rx: Long,   // بايتات مرسلة من الجهاز (رفع)
    val tx: Long    // بايتات مرسلة إلى الجهاز (تنزيل)
)

data class WifiSection(
    val name: String,
    val ssid: String,
    val encryption: String?,
    val maclist: List<String>,
    val macfilter: String?
)

data class ClientRow(
    val client: Client,
    val name: String,
    val down: Double,   // -1 = ميزة Pro مقفلة
    val up: Double,
    val trusted: Boolean
)
