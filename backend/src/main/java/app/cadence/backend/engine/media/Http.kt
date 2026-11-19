package app.cadence.backend.engine.media

import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.json.JSONObject

/**
 * HTTP client for the Apple CDN.
 *
 * Android's HttpURLConnection sat forever on `aod.itunes.apple.com`: the host
 * advertises IPv6, this network has no IPv6 route, and its connect/read
 * timeouts did not fire. This client resolves to IPv4 only and uses an overall
 * call timeout, so a broken route fails (and retries) instead of wedging the
 * download queue. HTTP/2 matches what curl negotiates with the same CDN.
 */
internal object Http {
    private const val UA = "Music/5.7 Android/10"

    /** Used only to resolve names over Cloudflare DoH when the system resolver stalls. */
    private val dohClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    /**
     * A CDN edge that served downloads once (curl resolved it). Edges change, so it is only the last
     * route after whatever the resolver answers, and the answer when resolution fails.
     */
    private val knownEdges: Map<String, InetAddress> = mapOf("aod.itunes.apple.com" to "152.231.110.240")
        .mapNotNull { (host, ip) -> runCatching { InetAddress.getByName(ip) }.getOrNull()?.let { host to it } }.toMap()

    private val dnsExecutor = Executors.newCachedThreadPool { r ->
        Thread(r, "cadence-dns").apply { isDaemon = true }
    }

    private val resolver = CdnResolver(
        system = { hostname ->
            // Android's resolver can hang (not just fail) on some devices, and OkHttp's call timeout
            // cannot interrupt a blocked DNS call, so the lookup itself is bounded; a stall counts as
            // a failure and DoH answers instead.
            val future = dnsExecutor.submit<List<InetAddress>> { InetAddress.getAllByName(hostname).toList() }
            try {
                future.get(4, TimeUnit.SECONDS)
            } catch (e: Exception) {
                future.cancel(true)
                null
            }
        },
        doh = ::dohLookup,
        fallbacks = knownEdges,
    )

    /** Keeps the CDN addresses that worked in [file], so a restart with a failing resolver still finds the CDN. */
    fun rememberEdgesIn(file: java.io.File) = resolver.rememberIn(FileEdgeStore(file))

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> = resolver.lookup(hostname)
            })
            .build()
    }

    private fun dohLookup(hostname: String): List<InetAddress> = runCatching {
        val query = URLEncoder.encode(hostname, "UTF-8")
        val request = Request.Builder()
            .url("https://1.1.1.1/dns-query?name=$query&type=A")
            .header("Accept", "application/dns-json")
            .build()
        dohClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) emptyList() else {
                val body = response.body?.string().orEmpty()
                val answers = JSONObject(body).optJSONArray("Answer") ?: return@use emptyList()
                (0 until answers.length()).mapNotNull { i ->
                    val answer = answers.optJSONObject(i) ?: return@mapNotNull null
                    if (answer.optInt("type") != 1) return@mapNotNull null
                    runCatching { InetAddress.getByName(answer.optString("data")) }.getOrNull()
                }.filterIsInstance<Inet4Address>()
            }
        }
    }.getOrDefault(emptyList())

    /** [retryAfterSeconds] is the `Retry-After` header when the server sent one (throttling). */
    data class Reply(val code: Int, val body: String, val retryAfterSeconds: Int? = null)

    /** API call with the same DNS/fallback path as the media fetches. */
    fun get(url: String, headers: Map<String, String>): Reply {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept", "application/json")
        headers.forEach { (k, v) -> builder.header(k, v) }
        client.newCall(builder.build()).execute().use { response ->
            return Reply(
                response.code,
                response.body?.string().orEmpty(),
                retryAfterSeconds(response.header("Retry-After"), response.headers.getDate("Date"), java.util.Date()),
            )
        }
    }

    /**
     * A write call (PUT/DELETE/POST) with the same client as [get]. [body] is sent as JSON when
     * non-null (DELETE usually has none). Used for subscriber-scoped /v1/me writes like ratings.
     */
    fun send(url: String, method: String, body: String?, headers: Map<String, String>): Reply {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept", "application/json")
        headers.forEach { (k, v) -> builder.header(k, v) }
        builder.method(method, body?.toRequestBody("application/json".toMediaType()))
        client.newCall(builder.build()).execute().use { response ->
            return Reply(
                response.code,
                response.body?.string().orEmpty(),
                retryAfterSeconds(response.header("Retry-After"), response.headers.getDate("Date"), java.util.Date()),
            )
        }
    }

    fun text(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept", "*/*")
            .header("Origin", "https://music.apple.com")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} from ${request.url.host}")
            return response.body?.string() ?: throw IOException("Empty body from ${request.url.host}")
        }
    }

    fun bytes(url: String, offset: Long?, length: Long?): ByteArray {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept", "*/*")
            .header("Origin", "https://music.apple.com")
        if (offset != null && length != null) {
            builder.header("Range", "bytes=$offset-${offset + length - 1}")
        }
        val request = builder.build()
        val call = client.newCall(request)
        // A 10 MB hi-res segment sharing a slow link with other fetches needs longer than the
        // client's 45 s; a stalled transfer is still caught by the 20 s read timeout.
        if (length != null) call.timeout().timeout(rangeTimeoutSeconds(length), TimeUnit.SECONDS)
        call.execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} from ${request.url.host}")
            val body = response.body ?: throw IOException("Empty body from ${request.url.host}")
            if (offset == null || length == null || response.code == 206) return body.bytes()
            // The server ignored Range and sends the whole resource: read only the range instead
            // of buffering the entire file for every segment.
            return readRange(body.source(), offset, length)
        }
    }

    /** The whole-call timeout for a range of [length] bytes: 45 s plus the time to move it at 64 KB/s. */
    internal fun rangeTimeoutSeconds(length: Long): Long = 45 + length / (64 * 1024)

    /** [length] bytes starting at [offset] of [source]; throws [java.io.EOFException] if it is shorter. */
    internal fun readRange(source: okio.BufferedSource, offset: Long, length: Long): ByteArray {
        source.skip(offset)
        return source.readByteArray(length)
    }

    /**
     * `Retry-After` as seconds from now. Servers send delta-seconds (which may not fit an Int) or an
     * HTTP-date; a date is measured against the server's own `Date` header when there is one, so a
     * skewed phone clock neither stretches nor skips the wait. Null when absent or unreadable.
     */
    internal fun retryAfterSeconds(value: String?, serverDate: java.util.Date?, now: java.util.Date): Int? {
        val v = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        v.toLongOrNull()?.let { return it.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt() }
        val at = okhttp3.Headers.headersOf("Retry-After", v).getDate("Retry-After") ?: return null
        val seconds = (at.time - (serverDate ?: now).time + 999) / 1000
        return seconds.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    }
}
