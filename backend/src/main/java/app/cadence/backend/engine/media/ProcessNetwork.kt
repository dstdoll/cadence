package app.cadence.backend.engine.media

import android.content.Context
import android.net.ConnectivityManager

/**
 * The DNS servers of the device's active network.
 *
 * The ported StoreServices library carries its own resolver and, before this, pinned it to a fixed
 * public resolver (Cloudflare) over UDP/53. That works on Wi-Fi but fails wherever external UDP/53
 * is dropped - some mobile carriers do - so the FairPlay key lookup hung for 30 s and surfaced as
 * "can't decrypt" even though every Kotlin HTTP call (which resolves over DoH) still worked.
 *
 * Handing the native resolver the active network's own DNS servers fixes it: those are reachable on
 * whatever network is up (the carrier's on cellular, the router's on Wi-Fi). The native side keeps
 * the public resolvers as a fallback for when the OS reports none.
 */
object ProcessNetwork {
    /** Comma-separated DNS server IPs for the active network, or "" when none are available. */
    fun activeDnsServers(context: Context): String = runCatching {
        val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
            ?: return ""
        val network = cm.activeNetwork ?: return ""
        val links = cm.getLinkProperties(network) ?: return ""
        links.dnsServers.mapNotNull { it.hostAddress }.distinct().joinToString(",")
    }.getOrDefault("")
}
