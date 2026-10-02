package com.android.wifitrackerlib

class WifiEntry {
    interface ConnectCallback
}

@Suppress("UNUSED_PARAMETER")
class StandardWifiEntry {
    @JvmField var mShouldAutoOpenCaptivePortal = true
    fun connect(callback: WifiEntry.ConnectCallback?) {}
    fun connect(callback: WifiEntry.ConnectCallback?, flag: Boolean) {}
    fun connect(callback: WifiEntry.ConnectCallback?, flag: Boolean, network: String) {}
    fun connect(unrelated: Int) {}
}
