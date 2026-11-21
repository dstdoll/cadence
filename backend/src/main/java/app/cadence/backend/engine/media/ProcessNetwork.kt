package app.cadence.backend.engine.media

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The DNS servers of the device's active network, and keeping the native resolver on them.
 *
 * The ported StoreServices library carries its own resolver and, before this, pinned it to a fixed
 * public resolver (Cloudflare) over UDP/53. That works on Wi-Fi but fails wherever external UDP/53
 * is dropped - some mobile carriers do - so the FairPlay key lookup hung for 30 s and surfaced as
 * "can't decrypt" even though every Kotlin HTTP call (which resolves over DoH) still worked.
 *
 * Handing the native resolver the active network's own DNS servers fixes it: those are reachable on
 * whatever network is up (the carrier's on cellular, the router's on Wi-Fi). They are, however, only
 * reachable on *that* network: the list taken when the engine started stops answering as soon as the
 * device moves to another one (a home router's address means nothing on mobile data), and with it
 * every StoreServices request, the sign-in included, fails to resolve while the rest of the app works.
 * [follow] therefore hands the native side the new list on every change of the default network, and
 * [refresh] does it once more right before a sign-in. The native side puts a public resolver after the
 * network's own as a last resort, and uses public ones alone when the OS reports none.
 */
object ProcessNetwork {
    /** Comma-separated DNS server IPs for the active network, or "" when none are available. */
    fun activeDnsServers(context: Context): String = runCatching {
        val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
            ?: return ""
        val network = cm.activeNetwork ?: return ""
        val links = cm.getLinkProperties(network) ?: return ""
        join(links.dnsServers)
    }.getOrDefault("")

    /** The comma-separated form of [servers] the native side takes. */
    internal fun join(servers: List<InetAddress>): String =
        servers.mapNotNull { it.hostAddress }.distinct().joinToString(",")

    /** A log-safe summary: how many servers of each family, never the addresses (they say where the device is). */
    internal fun describe(servers: String): String {
        val list = servers.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (list.isEmpty()) return "none (native fallback)"
        val v6 = list.count { ':' in it }
        return "${list.size} (ipv4=${list.size - v6} ipv6=$v6)"
    }

    /**
     * Whether [next] should replace the [installed] list. A network with no servers (between two
     * networks, or a captive one) keeps the last list rather than leaving only the public fallback.
     */
    internal fun shouldReplace(installed: String?, next: String): Boolean =
        next.isNotEmpty() && next != installed

    @Volatile private var installed: String? = null
    private val following = AtomicBoolean(false)

    /**
     * Keeps the native resolver on the default network's servers from now on. Call after every
     * successful [NativeWrapper.init] with the list it was given (each init installs its own list, so the
     * latest one is what the native side holds). Registers the callback once per process.
     */
    fun follow(context: Context, initial: String) {
        synchronized(this) { installed = initial }
        if (!following.compareAndSet(false, true)) return
        val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java) ?: return
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                // Called for a new default network (after onAvailable) and whenever its servers change.
                override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                    install(join(linkProperties.dnsServers), "network change")
                }
            })
        }.onFailure {
            following.set(false)
            AppLog.error("Network", "cannot follow the default network; native DNS stays as set at start", it)
        }
    }

    /** Hands the native resolver the active network's servers now, if they changed. Only after a successful init. */
    fun refresh(context: Context, reason: String) = install(activeDnsServers(context), reason)

    /** Hands the native resolver [next] (comma-separated server IPs) unless it already has it. Only after a successful init. */
    @Synchronized
    fun install(next: String, reason: String) {
        if (!shouldReplace(installed, next)) return
        runCatching { NativeWrapper.setDnsServers(next) }
            .onSuccess {
                installed = next
                AppLog.log("Network", "native dns updated ($reason): ${describe(next)}")
            }
            .onFailure { AppLog.error("Network", "native dns update failed ($reason)", it) }
    }
}
