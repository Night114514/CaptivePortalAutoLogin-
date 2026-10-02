package com.android.server

open class SystemService

class SystemServiceManager {
    @Suppress("UNUSED_PARAMETER")
    fun <T : SystemService> startService(serviceClass: Class<T>): T = error("Intercepted by the test")
}
