@file:Suppress("DEPRECATION")

package de.binarynoise.captiveportalautologin.xposed

import android.net.wifi.WifiConfiguration
import de.binarynoise.liberator.SSID
import de.binarynoise.liberator.portals.allPortalLiberators

internal object ReEnableSupportedNetworksHook {
    fun observeWifiService(hooks: HookContext, systemLoader: ClassLoader) {
        hooks.optional("SystemServiceManager.startService(Class)", systemLoader) {
            val manager = systemLoader.targetClass("com.android.server.SystemServiceManager")
            val service = systemLoader.targetClass("com.android.server.SystemService")
            val start = manager.targetMethod("startService", service, Class::class.java)
            hooks.intercept(start) { chain ->
                val serviceClass = chain.getArg(0) as Class<*>
                if (serviceClass.name == "com.android.server.wifi.WifiService") {
                    // startServiceFromJar has resolved the Wi-Fi APEX loader, but has not
                    // constructed WifiService/WifiInjector yet. Install before that happens.
                    serviceClass.classLoader?.let { install(hooks, it) }
                }
                chain.proceed()
            }
        }
    }

    private fun install(hooks: HookContext, wifiLoader: ClassLoader) {
        hooks.optional("WifiBlocklistMonitor.shouldEnableNetwork(WifiConfiguration)", wifiLoader) {
            val monitor = wifiLoader.targetClass("com.android.server.wifi.WifiBlocklistMonitor")
            val method = monitor.targetMethod("shouldEnableNetwork", Boolean::class.javaPrimitiveType!!, WifiConfiguration::class.java)
            val supportedSsids = allPortalLiberators.flatMap { liberator ->
                liberator::class.java.annotations.filterIsInstance<SSID>().flatMap { it.ssid.asIterable() }
            }.toSet()
            hooks.intercept(method) { chain ->
                val config = chain.getArg(0) as WifiConfiguration
                if (config.SSID?.trim('"') in supportedSsids) true else chain.proceed()
            }
        }
    }
}
