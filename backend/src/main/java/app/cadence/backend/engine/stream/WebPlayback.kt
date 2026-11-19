package app.cadence.backend.engine.stream

import android.content.Context
import app.cadence.backend.engine.AmpConfig
import app.cadence.backend.engine.CadenceLog
import app.cadence.backend.real.SessionStore
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/**
 * Web Playback + Widevine resolver for AAC-only tracks that lack `enhancedHls` in the catalogue.
 * Mirrors Apple's web player (`webPlayback` -> `28:ctrp256` HLS -> `acquireWebPlaybackLicense`),
 * letting ExoPlayer's native MediaDrm handle Widevine decryption.
 */
object WebPlayback {
    const val SCHEME = "cadence-web"
    private const val PREFIX = "$SCHEME://track/"
    private const val WEB_PLAYBACK_URL = "https://play.music.apple.com/WebObjects/MZPlay.woa/wa/webPlayback"
    private const val LICENSE_URL = "https://play.itunes.apple.com/WebObjects/MZPlay.woa/wa/acquireWebPlaybackLicense"
    private const val FLAVOR_AAC_256 = "28:ctrp256"
    private const val MAX_KEY_CACHE = 64
    private const val BROWSER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36"

    private data class KeyMeta(val keyUri: String, val licenseUrl: String)

    private val keyMetaByTrack = object : LinkedHashMap<String, KeyMeta>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, KeyMeta>): Boolean =
            size > MAX_KEY_CACHE
    }

    fun uriFor(trackId: String): String = PREFIX + trackId

    fun trackIdOf(uri: String): String? =
        uri.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)?.takeIf { it.isNotBlank() }

    /** Resolves the `28:ctrp256` Widevine HLS media playlist for [trackId] and returns its UTF-8 bytes. */
    fun resolvePlaylistBytes(context: Context, trackId: String): ByteArray {
        val (devToken, userToken) = tokens(context)
        val body = JSONObject().put("salableAdamId", trackId).toString()
        val resp = postJson(WEB_PLAYBACK_URL, devToken, userToken, body)
        val songList = resp.optJSONArray("songList")
            ?: throw IOException("webPlayback returned no songList for $trackId")
        if (songList.length() == 0) throw IOException("webPlayback songList empty for $trackId")
        val assets = songList.getJSONObject(0).optJSONArray("assets")
            ?: throw IOException("webPlayback returned no assets for $trackId")

        var playlistUrl: String? = null
        var licenseUrl = LICENSE_URL
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val flavor = a.optString("flavor")
            val url = a.optString("URL").takeIf { it.isNotBlank() } ?: continue
            if (flavor == FLAVOR_AAC_256 || playlistUrl == null) {
                playlistUrl = url
                a.optString("license-url").takeIf { it.isNotBlank() }?.let { licenseUrl = it }
                if (flavor == FLAVOR_AAC_256) break
            }
        }
        val m3u8Url = playlistUrl ?: throw IOException("No 28:ctrp256 asset for $trackId")
        CadenceLog.i("webplay", "track=$trackId url=$m3u8Url")

        val rawM3u8 = getText(m3u8Url)
        val keyUri = extractWidevineKeyUri(rawM3u8)
        if (keyUri != null) {
            synchronized(keyMetaByTrack) { keyMetaByTrack[trackId] = KeyMeta(keyUri, licenseUrl) }
        }
        val resolved = absolutizePlaylist(rawM3u8, URL(m3u8Url))
        CadenceLog.i("webplay", "track=$trackId playlist=${resolved.length}B key=${keyUri != null}")
        return resolved.toByteArray(Charsets.UTF_8)
    }

    /** Exchanges a Widevine [challenge] for [trackId] with Apple's web playback license endpoint. */
    fun exchangeLicense(context: Context, trackId: String, challenge: ByteArray): ByteArray {
        val (devToken, userToken) = tokens(context)
        val meta = synchronized(keyMetaByTrack) { keyMetaByTrack[trackId] }
            ?: throw IOException("Missing Widevine key URI for $trackId")
        val reqJson = JSONObject()
            .put("challenge", Base64.getEncoder().encodeToString(challenge))
            .put("key-system", "com.widevine.alpha")
            .put("uri", meta.keyUri)
            .put("adamId", trackId)
            .put("isLibrary", false)
            .put("user-initiated", true)
            .toString()
        val resp = postJson(meta.licenseUrl, devToken, userToken, reqJson)
        val errorCode = resp.optInt("errorCode", 0)
        val status = resp.optInt("status", 0)
        if (errorCode != 0 || status != 0) {
            throw IOException("Widevine license rejected: errorCode=$errorCode status=$status")
        }
        val licenseB64 = resp.optString("license")
        if (licenseB64.isBlank()) throw IOException("Empty Widevine license for $trackId")
        CadenceLog.i("webplay", "track=$trackId Widevine license ok")
        return Base64.getDecoder().decode(licenseB64)
    }

    private fun tokens(context: Context): Pair<String, String> {
        val amp = AmpConfig.load(context) ?: throw IOException("No MusicKit developer token")
        val userToken = SessionStore(context).load()?.accessToken?.takeIf { it.isNotBlank() }
            ?: amp.mediaUserToken
        if (userToken.isBlank()) throw IOException("No media-user-token for web playback")
        return amp.devToken to userToken
    }

    private fun postJson(url: String, devToken: String, userToken: String, payload: String): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Authorization", "Bearer $devToken")
            setRequestProperty("media-user-token", userToken)
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Origin", "https://music.apple.com")
            setRequestProperty("Referer", "https://music.apple.com/")
            setRequestProperty("User-Agent", BROWSER_UA)
        }
        try {
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IOException("POST $url -> HTTP $code")
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    private fun getText(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Origin", "https://music.apple.com")
            setRequestProperty("Referer", "https://music.apple.com/")
            setRequestProperty("User-Agent", BROWSER_UA)
        }
        try {
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IOException("GET $url -> HTTP $code")
            return text
        } finally {
            conn.disconnect()
        }
    }

    /** Builds a WidevineCencHeader protobuf (`algorithm = AESCTR`, `key_id = keyId`) for ExoPlayer's HLS parser. */
    internal fun buildWidevineCencPssh(keyId: ByteArray): ByteArray =
        byteArrayOf(0x08, 0x01, 0x12, keyId.size.toByte()) + keyId

    internal fun absolutizePlaylist(m3u8: String, baseUrl: URL): String {
        val uriRegex = Regex("""URI="([^"]+)"""")
        return m3u8.lineSequence().joinToString("\n") { line ->
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> line
                trimmed.startsWith("#EXT-X-MAP:") -> uriRegex.replace(line) { m ->
                    val abs = URL(baseUrl, m.groupValues[1]).toString()
                    """URI="$abs""""
                }
                trimmed.startsWith("#EXT-X-KEY:") && trimmed.contains("METHOD=ISO-23001-7") -> {
                    var rewritten = trimmed.replace(
                        "METHOD=ISO-23001-7",
                        """METHOD=SAMPLE-AES-CENC,KEYFORMAT="urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"""",
                    )
                    val m = uriRegex.find(rewritten)
                    val b64 = m?.groupValues?.get(1).orEmpty().substringAfter("base64,", "")
                    if (b64.isNotBlank() && m != null) {
                        val keyId = Base64.getDecoder().decode(b64)
                        val protoB64 = Base64.getEncoder().encodeToString(buildWidevineCencPssh(keyId))
                        rewritten = rewritten.replaceRange(m.range, """URI="data:text/plain;base64,$protoB64"""")
                    }
                    rewritten
                }
                trimmed.startsWith("#") -> line
                else -> URL(baseUrl, trimmed).toString()
            }
        }
    }

    internal fun extractWidevineKeyUri(m3u8: String): String? {
        val uriRegex = Regex("""URI="([^"]+)"""")
        return m3u8.lineSequence()
            .filter { it.startsWith("#EXT-X-KEY:") }
            .mapNotNull { uriRegex.find(it)?.groupValues?.get(1) }
            .firstOrNull()
    }

    @Volatile private var aacDiskCache: AacCache? = null
    private val aacCacheLock = Any()

    /**
     * Process-wide decrypted `.m4a` cache for AAC-only tracks. Cold misses still stream immediately
     * via Widevine HLS; this cache fills in the background for instant replay and queue transitions.
     */
    fun aacCache(context: Context): AacCache = aacDiskCache ?: synchronized(aacCacheLock) {
        val app = context.applicationContext
        aacDiskCache ?: AacCache(
            root = java.io.File(app.cacheDir, "aac-stream"),
            fetcher = AacCache.TrackFetcher { trackId, outFile -> fetchClearM4a(app, trackId, outFile) },
            log = { CadenceLog.i("aac-cache", it) },
        ).also { aacDiskCache = it }
    }

    private fun fetchClearM4a(context: Context, trackId: String, outFile: java.io.File): Boolean {
        if (!app.cadence.backend.engine.media.EngineDownloader.initNative(context)) return false
        val asset = app.cadence.backend.engine.media.NativeWrapper.m4pAsset(trackId) ?: return false
        val m4pTmp = java.io.File(context.cacheDir, "aac_tmp_${trackId}_${System.nanoTime()}.m4p")
        return try {
            val conn = (URL(asset.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
            }
            try {
                if (conn.responseCode !in 200..299) return false
                conn.inputStream.use { input ->
                    m4pTmp.outputStream().use { output -> input.copyTo(output) }
                }
            } finally {
                conn.disconnect()
            }
            val rc = app.cadence.backend.engine.media.NativeDrm.decryptM4pWithMeta(
                asset.decryptorCtx,
                m4pTmp.absolutePath,
                outFile.absolutePath,
                "{}",
                null,
                null,
            )
            rc == 0
        } finally {
            m4pTmp.delete()
            app.cadence.backend.engine.media.NativeWrapper.releaseM4pContext(asset.decryptorCtx)
        }
    }
}
