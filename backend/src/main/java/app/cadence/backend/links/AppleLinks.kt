package app.cadence.backend.links

/** What an Apple Music link points at. Ids are validated here, before they can ever reach an API path. */
sealed interface AppleLink {
    /** An album page; [songId] set means "this song of the album" (`?i=`). */
    data class Album(val id: String, val songId: String? = null, val storefront: String? = null) : AppleLink
    data class Song(val id: String, val storefront: String? = null) : AppleLink
    data class Playlist(val token: String, val storefront: String? = null) : AppleLink
    data class Artist(val id: String, val storefront: String? = null) : AppleLink

    /** A real Apple Music link of a kind Cadence cannot open (music video, station, curator, library, ...). */
    data class Unsupported(val kind: String) : AppleLink
}

/**
 * Apple Music links, both ways: [song]/[album]/[artist]/[playlist] build the link that is shared or copied, and [parse]
 * finds the first link Cadence can open in any text (a share text with extra words, a pasted URL, two URLs, legacy
 * `itunes.apple.com` links, `music://` links). Pure Kotlin: no Android, no network.
 *
 * The text comes from other apps, so it is untrusted input: only allow-listed Apple hosts, ids must match
 * `\d{3,15}` (playlists `pl.<token>`), the text is capped, and nothing here fetches or opens anything.
 */
object AppleLinks {
    private const val MAX_TEXT = 4096
    private val hosts = setOf("music.apple.com", "itunes.apple.com", "geo.music.apple.com", "embed.music.apple.com", "beta.music.apple.com", "classical.music.apple.com")
    private val url = Regex("""(?i)\b(?:https?|music)://[^\s<>"'`)\]]+""")
    private val numeric = Regex("""\d{3,15}""")
    private val playlistToken = Regex("""pl\.[A-Za-z0-9._-]{1,80}""")
    private val storefrontCode = Regex("""[a-z]{2}""")
    private val unsupportedKinds = setOf(
        "music-video", "station", "curator", "genre", "room", "browse", "radio", "listen-now", "library", "new", "search", "show", "episode", "post", "tv-show", "movie",
    )

    // ---- building ---------------------------------------------------------------------------------------------------

    /** Apple's site redirects these short forms to the canonical page (checked: 301 to the slugged URL). */
    fun song(storefront: String, songId: String): String? = build(storefront, "song", songId)
    fun album(storefront: String, albumId: String): String? = build(storefront, "album", albumId)
    fun artist(storefront: String, artistId: String): String? = build(storefront, "artist", artistId)
    fun playlist(storefront: String, token: String): String? =
        if (playlistToken.matches(token)) "https://music.apple.com/${sf(storefront)}/playlist/$token" else null

    private fun build(storefront: String, kind: String, id: String): String? =
        if (numeric.matches(id)) "https://music.apple.com/${sf(storefront)}/$kind/$id" else null

    /** A usable two-letter storefront, else "us" (Apple redirects to the viewer's own storefront anyway). */
    private fun sf(storefront: String) = storefront.lowercase().takeIf { storefrontCode.matches(it) } ?: "us"

    // ---- parsing ----------------------------------------------------------------------------------------------------

    /** The first link in [text] Cadence can open; else the first real Apple link of an unsupported kind; else null. */
    fun parse(text: String?): AppleLink? {
        if (text.isNullOrBlank()) return null
        var firstUnsupported: AppleLink.Unsupported? = null
        for (m in url.findAll(text.take(MAX_TEXT))) {
            when (val link = parseOne(m.value)) {
                null -> {}
                is AppleLink.Unsupported -> if (firstUnsupported == null) firstUnsupported = link
                else -> return link
            }
        }
        return firstUnsupported
    }

    /** Whether the whole of [text] (a pasted line, whitespace aside) is one Apple Music link, supported or not. */
    fun isLinkText(text: String?): Boolean {
        val t = text?.trim().orEmpty()
        return t.isNotEmpty() && !t.any { it.isWhitespace() } && parseOne(t) != null
    }

    private fun parseOne(raw: String): AppleLink? {
        val schemeEnd = raw.indexOf("://")
        if (schemeEnd < 0) return null
        val rest = raw.substring(schemeEnd + 3)
        val hostEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) rest.length else it }
        val host = rest.substring(0, hostEnd).substringBefore(':').lowercase()
        if (host !in hosts) return null
        val afterHost = rest.substring(hostEnd).substringBefore('#')
        val path = afterHost.substringBefore('?')
        val query = if (afterHost.contains('?')) afterHost.substringAfter('?') else ""
        val segs = path.split('/').filter { it.isNotEmpty() }.toMutableList()
        // A path that walks ("..", ".", or their percent-encoded forms) is malformed or hostile, never a real link.
        if (segs.any { it == "." || it == ".." || it.contains("%2e", ignoreCase = true) || it.contains("%2f", ignoreCase = true) }) return null
        if (host == "classical.music.apple.com") return AppleLink.Unsupported("classical")
        // `{sf}/kind/...`: a two-letter storefront in front of a known kind (or no storefront at all)
        var storefront: String? = null
        if (segs.size >= 2 && storefrontCode.matches(segs[0].lowercase()) && isKind(segs[1].lowercase())) storefront = segs.removeAt(0).lowercase()
        if (segs.isEmpty()) return null
        val kind = segs[0].lowercase()
        val params = query.split('&').mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].lowercase() to it[1] } }.toMap()
        // the id is the LAST path segment (`album/slug/123`, `album/123`, legacy `album/slug/id123`)
        val last = segs.last().removePrefix("id")
        return when (kind) {
            "album" -> {
                val id = last.takeIf { segs.size >= 2 && numeric.matches(it) } ?: return AppleLink.Unsupported("album")
                val song = params["i"]?.takeIf { numeric.matches(it) }
                AppleLink.Album(id, song, storefront)
            }
            "song" -> last.takeIf { segs.size >= 2 && numeric.matches(it) }?.let { AppleLink.Song(it, storefront) } ?: AppleLink.Unsupported("song")
            "artist" -> last.takeIf { segs.size >= 2 && numeric.matches(it) }?.let { AppleLink.Artist(it, storefront) } ?: AppleLink.Unsupported("artist")
            "playlist" -> segs.last().takeIf { segs.size >= 2 && playlistToken.matches(it) }?.let { AppleLink.Playlist(it, storefront) } ?: AppleLink.Unsupported("playlist")
            in unsupportedKinds -> AppleLink.Unsupported(kind)
            else -> if (segs.size == 1 && storefrontCode.matches(kind)) null else AppleLink.Unsupported(kind)
        }
    }

    private fun isKind(s: String) = s in setOf("album", "song", "artist", "playlist") || s in unsupportedKinds
}
