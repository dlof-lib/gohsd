package com.gohsd.app

object Format {
    fun speed(bps: Double): String = when {
        bps >= 1_048_576 -> "%.1f MB/s".format(bps / 1_048_576)
        bps >= 1024 -> "%.0f KB/s".format(bps / 1024)
        else -> "%.0f B/s".format(bps)
    }
}
