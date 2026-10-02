package com.android.server.wifi

import android.net.wifi.WifiConfiguration
import com.android.server.SystemService

class WifiService : SystemService()

class WifiBlocklistMonitor {
    @Suppress("DEPRECATION", "UNUSED_PARAMETER")
    fun shouldEnableNetwork(config: WifiConfiguration): Boolean = false
}
