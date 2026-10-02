package com.android.networkstack

import android.net.Network

class NetworkStackNotifier {
    class TrackedNetworkStatus {
        @JvmField var mValidatedNotificationPending = true
    }

    @JvmField val mNetworkStatus = hashMapOf<Network?, TrackedNetworkStatus>()
    fun isVenueInfoNotificationEnabled(): Boolean = true
    @Suppress("UNUSED_PARAMETER")
    fun updateNotifications(network: Network?) {}
}
