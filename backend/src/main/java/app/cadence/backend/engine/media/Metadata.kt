package app.cadence.backend.engine.media

import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Cover art. Apple serves artwork from a `.../{w}x{h}bb.jpg` template, so the
 * requested size is chosen in the URL and the CDN does the resizing - this is
 * exactly how amdl's `metadata.artwork.size` works ("5000x5000", "3000x3000",
 * ..., plus "original" for the largest master).
 */
object Artwork {
    /** Applies [AppSettings] size/format to an artwork URL template. [sizeOverride] substitutes a
     *  different `{w}x{h}` size than the one in Settings. */
    fun url(template: String?, settings: AppSettings, sizeOverride: String? = null): String? =
        sizedUrl(template, sizeOverride ?: settings.artworkRequestSize(), settings.artworkFormat)

    /** Builds a concrete artwork URL for one `{w}x{h}` size and one image [format] ("jpg"/"png"). */
    internal fun sizedUrl(template: String?, size: String, format: String): String? {
        var url = template?.takeIf { it.isNotBlank() } ?: return null
        url = when (format) {
            "png" -> url.replace(".jpg", ".png")
            "jpg" -> url.replace(".png", ".jpg")
            else -> url // "original": keep the template's own extension
        }
        return url.replace("{w}x{h}", size)
    }

    /**
     * Fetches the cover, stepping down a descending ladder of sizes (and trying the other image format)
     * until one returns a **valid image**, so a cover is embedded whenever any size is reachable instead
     * of being dropped. Apple's CDN resizes to any requested size, but a given size/format can 404 or
     * time out (a huge "original" master, or a source that only has one format), so we never rely on a
     * single request. Returns the highest-quality image that actually fetched, or null if the source
     * genuinely has none (a clear log line, so a missing cover is never silent).
     */
    fun fetchBest(template: String?, settings: AppSettings): Pair<ByteArray, String>? {
        if (template.isNullOrBlank()) return null
        val ladder = sizeLadder(settings.artworkRequestSize())
        // Prefer the configured format, then the other, so a PNG-only or JPEG-only source still resolves.
        val formats = if (settings.artworkFormat == "png") listOf("png", "jpg") else listOf("jpg", "png")
        for (size in ladder) {
            for (format in formats) {
                sizedUrl(template, size, format)?.let { u -> fetch(u)?.let { return it } }
            }
        }
        AppLog.error("Artwork", "no cover embedded: every size/format failed for template=$template", null)
        return null
    }

    /** Downloads the image and returns its bytes plus mime type, or null when the response is not a
     *  usable JPEG/PNG (a 404, a timeout, an HTML error page, or a truncated/empty body). */
    fun fetch(url: String): Pair<ByteArray, String>? = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "Music/5.7 Android/10")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) { AppLog.log("Artwork", "GET $url -> $code"); return null }
            val bytes = conn.inputStream.use { it.readBytes() }
            val mime = imageMime(bytes)
            if (mime == null) {
                AppLog.log("Artwork", "GET $url -> $code but body is not an image (${bytes.size}B)")
                return null
            }
            AppLog.log("Artwork", "GET $url -> $code ${bytes.size}B $mime")
            bytes to mime
        } finally {
            conn.disconnect()
        }
    }.getOrElse {
        AppLog.error("Artwork", "fetch failed url=$url", it)
        null
    }

    /** The highest-first, distinct ladder of `{w}x{h}` sizes to try: the requested size, then common
     *  smaller sizes down to a small guaranteed one, so the best reachable cover is always found. */
    internal fun sizeLadder(requested: String): List<String> = buildList {
        add(requested)
        addAll(listOf("3000x3000", "2400x2400", "2000x2000", "1600x1600", "1400x1400", "1200x1200", "1000x1000", "600x600", "300x300"))
    }.distinct()

    /** The mime type if [bytes] begin with a real JPEG or PNG signature, else null (error page/empty). */
    internal fun imageMime(bytes: ByteArray): String? = when {
        bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte() -> "image/png"
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() &&
            bytes[2] == 0xFF.toByte() -> "image/jpeg"
        else -> null
    }
}

/** One timed lyric line. */
data class LyricLine(val beginSec: Double, val text: String)

/**
 * Lyrics. Apple exposes word-timed TTML at
 * `/v1/catalog/{sf}/songs/{id}/syllable-lyrics`; the line-timed form is derived
 * locally, which is what amdl does (`syllableTtmlToPlain`). `lyricsFormat`
 * then picks LRC or raw TTML for the `©lyr` tag.
 */
object Lyrics {
    private const val LANG = "en-US"
    private const val SCRIPT = "en-Latn"

    /**
     * Fetches TTML for a song. Tries the word-timed endpoint first, then the
     * line-timed one, logging every status so a 404 (no lyrics) is
     * distinguishable from an auth or parameter problem.
     */
    fun fetch(session: Session, songId: String, hasLyrics: Boolean = true): String? {
        if (!hasLyrics) {
            AppLog.log("Lyrics", "skip $songId: hasLyrics=false")
            return null
        }
        val candidates = listOf(
            "songs/$songId/syllable-lyrics?l[lyrics]=$LANG&extend=ttmlLocalizations&l[script]=$SCRIPT",
            "songs/$songId/lyrics?l[lyrics]=$LANG&extend=ttmlLocalizations&l[script]=$SCRIPT",
            "songs/$songId/lyrics?l[lyrics]=$LANG",
        )
        for (path in candidates) {
            val (code, body) = runCatching { AmpApi.getRaw(session, path) }.getOrElse {
                AppLog.log("Lyrics", "GET $path threw ${it.javaClass.simpleName}")
                continue
            }
            AppLog.log("Lyrics", "GET (${session.storefrontCode}) ${path.substringBefore('?')} -> $code ${body.take(100)}")
            if (code !in 200..299) continue
            val ttml = runCatching {
                val root = org.json.JSONObject(body)
                val data = root.optJSONArray("data") ?: return@runCatching null
                if (data.length() == 0) return@runCatching null
                val attrs = data.getJSONObject(0).optJSONObject("attributes")
                    ?: return@runCatching null
                attrs.optString("ttmlLocalizations").takeIf { it.isNotBlank() }
                    ?: attrs.optString("ttml").takeIf { it.isNotBlank() }
            }.getOrNull()
            if (ttml != null) return ttml
        }
        return null
    }

    fun parseLines(ttml: String): List<LyricLine> = runCatching {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }
        val doc = factory.newDocumentBuilder().parse(InputSource(StringReader(ttml)))
        val nodes = doc.getElementsByTagName("p")
        val out = ArrayList<LyricLine>(nodes.length)
        for (i in 0 until nodes.length) {
            val p = nodes.item(i) as? Element ?: continue
            val begin = seconds(p.getAttribute("begin"))
            // Apple's syllable TTML wraps every word in a <span>; joining runs
            // leaves doubled spaces, so normalise before emitting a line.
            val text = buildString { collect(p, this) }
                .replace(Regex("\\s+"), " ")
                .trim()
            if (text.isNotEmpty()) out.add(LyricLine(begin, text))
        }
        out.sortedBy { it.beginSec }
    }.getOrDefault(emptyList())

    /** Line-timed LRC, the usual `©lyr` payload. */
    fun toLrc(lines: List<LyricLine>): String = lines.joinToString("\n") { line ->
        val total = line.beginSec
        val minutes = (total / 60).toInt()
        val seconds = total - minutes * 60
        "[%02d:%05.2f]%s".format(minutes, seconds, line.text)
    }

    /** Minimal line-timed TTML rebuilt from the parsed lines. */
    fun toTtml(lines: List<LyricLine>): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""")
        append("\n<tt xmlns=\"http://www.w3.org/ns/ttml\"><body><div>")
        lines.forEachIndexed { index, line ->
            val end = lines.getOrNull(index + 1)?.beginSec ?: (line.beginSec + 4)
            append("\n  <p begin=\"").append(fmt(line.beginSec)).append("\" end=\"")
                .append(fmt(end)).append("\">")
                .append(line.text.replace("&", "&amp;").replace("<", "&lt;"))
                .append("</p>")
        }
        append("\n</div></body></tt>")
    }

    /** SubRip (.srt) timed lyrics with `HH:MM:SS,mmm --> HH:MM:SS,mmm` cues. */
    fun toSrt(lines: List<LyricLine>): String = buildString {
        lines.forEachIndexed { index, line ->
            val end = lines.getOrNull(index + 1)?.beginSec ?: (line.beginSec + 4.0)
            if (index > 0) append("\n\n")
            append(index + 1).append('\n')
            append(srtTime(line.beginSec)).append(" --> ").append(srtTime(end)).append('\n')
            append(line.text)
        }
    }

    /** Unsynced plain text lyrics (one line per cue, timestamps stripped). */
    fun toPlainText(lines: List<LyricLine>): String = lines.joinToString("\n") { it.text }

    /** Formats raw Apple [ttml] into the requested [format] (`"lrc"`, `"ttml"`, `"srt"`, or `"txt"`). */
    fun format(ttml: String, format: String): String = when (format.lowercase()) {
        "ttml" -> ttml
        "srt" -> toSrt(parseLines(ttml))
        "txt" -> toPlainText(parseLines(ttml))
        else -> toLrc(parseLines(ttml))
    }

    private fun srtTime(sec: Double): String {
        val totalMs = (sec.coerceAtLeast(0.0) * 1000.0).toLong()
        val ms = totalMs % 1000
        val totalSec = totalMs / 1000
        val s = totalSec % 60
        val m = (totalSec / 60) % 60
        val h = totalSec / 3600
        return "%02d:%02d:%02d,%03d".format(java.util.Locale.US, h, m, s, ms)
    }

    private fun fmt(sec: Double) = "%.3f".format(java.util.Locale.US, sec)

    private fun collect(node: Node, out: StringBuilder) {
        var child = node.firstChild
        while (child != null) {
            when (child.nodeType) {
                Node.TEXT_NODE -> out.append(child.nodeValue ?: "")
                Node.ELEMENT_NODE -> {
                    // Apple's syllable TTML splits words into <span> syllables
                    // ("mu"+"sic") and carries the word spacing in the text
                    // nodes, so spans must be concatenated, never padded.
                    if (child.nodeName.equals("br", true)) out.append(' ') else collect(child, out)
                }
            }
            child = child.nextSibling
        }
    }

    private fun seconds(value: String): Double {
        val raw = value.trim().removeSuffix("s")
        if (raw.isEmpty()) return 0.0
        if (!raw.contains(':')) return raw.toDoubleOrNull() ?: 0.0
        val parts = raw.split(':')
        var total = 0.0
        parts.forEach { total = total * 60 + (it.toDoubleOrNull() ?: 0.0) }
        return total
    }
}
