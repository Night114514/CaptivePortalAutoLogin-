package de.binarynoise.captiveportalautologin

import android.content.Context
import de.binarynoise.captiveportalautologin.ConnectivityChangeListenerService.NetworkState
import de.binarynoise.captiveportalautologin.ConnectivityChangeListenerService.ServiceState

// Keep diagnostic toString() output stable; localize only user-visible status text.
fun ServiceState.displayText(context: Context): String {
    val status = context.getString(if (running) R.string.service_running else R.string.service_stopped)
    return if (restart) context.getString(R.string.service_restarting, status) else status
}

fun NetworkState.displayText(context: Context): String = context.getString(
    R.string.network_status_details,
    network,
    ssid,
    context.getString(if (hasPortal) R.string.network_portal_required else R.string.network_portal_not_required),
    context.getString(when {
        liberating -> R.string.network_liberating
        liberated -> R.string.network_liberated
        else -> R.string.network_not_liberating
    }),
)
