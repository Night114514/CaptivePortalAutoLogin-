package de.binarynoise.captiveportalautologin.xposed

import android.net.Network

internal object NetworkConnectedHook {
    fun install(hooks: HookContext, loader: ClassLoader) {
        // These two features remain independent if a vendor removes only one target.
        hooks.optional("NetworkStackNotifier.isVenueInfoNotificationEnabled()", loader) {
            val notifier = loader.targetClass("com.android.networkstack.NetworkStackNotifier")
            hooks.intercept(notifier.targetMethod("isVenueInfoNotificationEnabled", Boolean::class.javaPrimitiveType!!)) {
                false
            }
        }
        hooks.optional("NetworkStackNotifier.updateNotifications(Network)", loader) {
            val notifier = loader.targetClass("com.android.networkstack.NetworkStackNotifier")
            val status = loader.targetClass("com.android.networkstack.NetworkStackNotifier\$TrackedNetworkStatus")
            val networkStatus = notifier.getDeclaredField("mNetworkStatus").apply {
                require(Map::class.java.isAssignableFrom(type)) { "$this is not a Map" }
                isAccessible = true
            }
            val pending = status.targetField("mValidatedNotificationPending", Boolean::class.javaPrimitiveType!!)
            hooks.intercept(notifier.targetMethod("updateNotifications", Void.TYPE, Network::class.java)) { chain ->
                val tracked = (networkStatus.get(chain.thisObject) as Map<*, *>)[chain.getArg(0)]
                if (tracked != null) pending.setBoolean(tracked, false)
                chain.proceed()
            }
        }
    }
}
