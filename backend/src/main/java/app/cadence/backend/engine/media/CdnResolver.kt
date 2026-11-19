package app.cadence.backend.engine.media

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap

/** Where the resolver keeps the last addresses that worked, across app restarts. */
internal interface EdgeStore {
    fun load(): Map<String, List<InetAddress>>
    fun save(edges: Map<String, List<InetAddress>>)
}

/** [EdgeStore] in a small JSON file in the app's private storage, replaced atomically. */
internal class FileEdgeStore(private val file: File) : EdgeStore {
    override fun load(): Map<String, List<InetAddress>> = runCatching {
        val json = JSONObject(file.readText())
        json.keys().asSequence().associateWith { host ->
            val list = json.getJSONArray(host)
            (0 until list.length()).mapNotNull { runCatching { InetAddress.getByName(list.getString(it)) }.getOrNull() }
        }.filterValues { it.isNotEmpty() }
    }.getOrDefault(emptyMap())

    override fun save(edges: Map<String, List<InetAddress>>) {
        runCatching {
            val json = JSONObject()
            edges.forEach { (host, addresses) -> json.put(host, JSONArray(addresses.mapNotNull { it.hostAddress })) }
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.toString())
            if (!tmp.renameTo(file)) tmp.delete()
        }
    }
}

/**
 * Name resolution for the CDN that keeps working when the CDN changes and when resolution fails:
 *
 * 1. the last good answer for the host, while it is younger than [cacheMs];
 * 2. the system resolver ([system], bounded by its caller, since Android's resolver can hang), which
 *    always names the CDN's current edges;
 * 3. DNS over HTTPS ([doh]) when the system resolver fails or stalls;
 * 4. when both fail, the last answer that worked, however old: from memory, or from [store] after a
 *    restart (it is updated on every answer that changes);
 * 5. a known address for the host ([fallbacks]) as the very last resort.
 *
 * A known address is also appended after any other answer, so when an edge does not connect OkHttp
 * moves on to it instead of failing the request.
 */
internal class CdnResolver(
    private val system: (String) -> List<InetAddress>?,
    private val doh: (String) -> List<InetAddress>,
    private val fallbacks: Map<String, InetAddress>,
    private val cacheMs: Long = 10 * 60_000L,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val cache = ConcurrentHashMap<String, Pair<Long, List<InetAddress>>>()
    private val learned = ConcurrentHashMap<String, List<InetAddress>>()
    @Volatile private var store: EdgeStore? = null

    /** Remembers good answers in [store] and starts from the ones it already holds. */
    fun rememberIn(store: EdgeStore) {
        learned.putAll(store.load().filterKeys { !learned.containsKey(it) })
        this.store = store
    }

    fun lookup(hostname: String): List<InetAddress> {
        val fallback = fallbacks[hostname]
        cache[hostname]?.takeIf { now() - it.first < cacheMs }?.let { return withFallback(it.second, fallback) }
        // null from the system resolver means it failed or stalled; an empty list means no answer.
        val all = system(hostname)
        val resolved = all?.filterIsInstance<Inet4Address>()?.takeIf { it.isNotEmpty() }
            ?: doh(hostname).takeIf { it.isNotEmpty() }
            ?: all?.takeIf { it.isNotEmpty() }
        if (resolved != null) {
            cache[hostname] = now() to resolved
            if (learned[hostname] != resolved) {
                learned[hostname] = resolved
                store?.save(HashMap(learned))
            }
            return withFallback(resolved, fallback)
        }
        val lastGood = cache[hostname]?.second ?: learned[hostname]
        if (lastGood != null) return withFallback(lastGood, fallback)
        return fallback?.let { listOf(it) } ?: throw UnknownHostException("DNS failed for $hostname")
    }

    companion object {
        fun withFallback(resolved: List<InetAddress>, fallback: InetAddress?): List<InetAddress> =
            (resolved + listOfNotNull(fallback)).distinct()
    }
}
