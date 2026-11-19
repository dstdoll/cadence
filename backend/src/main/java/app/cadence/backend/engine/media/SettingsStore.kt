package app.cadence.backend.engine.media

/**
 * App settings. The metadata block mirrors amdl's `metadata.*` config so the
 * same knobs exist on device:
 *
 *   metadata.lyrics.{embed,type,format}
 *   metadata.artwork.{embed,size,format}
 * Tags themselves are always written (title/artist/album/track/disc/year).
 */
data class AppSettings(
    val saveTreeUri: String?,
    val namingTemplate: String,
    val lyricsEmbed: Boolean = true,
    /** "lyrics" (line timed) or "syllable-lyrics" (word timed). */
    val lyricsType: String = "lyrics",
    /** "lrc" or "ttml". */
    val lyricsFormat: String = "lrc",
    val artworkEmbed: Boolean = true,
    /** Preset "5000x5000"/"3000x3000"/"1400x1400"/"1000x1000"/"600x600", or "original", or "custom". */
    val artworkSize: String = "1400x1400",
    val artworkCustomPx: Int = 1400,
    /** "jpg", "png" or "original". */
    val artworkFormat: String = "jpg",
) {
    /** The `{w}x{h}` substitution to request from Apple's artwork CDN. */
    fun artworkRequestSize(): String = when (artworkSize) {
        "original" -> "10000x10000" // Apple clamps to the largest master it has
        "custom" -> "${artworkCustomPx}x${artworkCustomPx}"
        else -> artworkSize
    }

    companion object {
        const val DEFAULT_TEMPLATE = "{artist}/{album}/{track:02d} {title}"
        val ARTWORK_PRESETS = listOf("original", "5000x5000", "3000x3000", "1400x1400", "1000x1000", "600x600", "custom")
        val ARTWORK_FORMATS = listOf("jpg", "png", "original")
        val LYRICS_TYPES = listOf("lyrics", "syllable-lyrics")
        val LYRICS_FORMATS = listOf("lrc", "ttml")
    }
}

/** Expands the naming template into a `/`-separated path for one track. */
fun namingPath(template: String, track: Track, album: Album): String {
    val artist = track.artist.ifBlank { album.artist }
    val filled = template
        .replace("{artist}", artist)
        .replace("{album}", album.title)
        .replace("{playlist}", album.title)
        .replace("{track:02d}", "%02d".format(track.trackNumber))
        .replace("{track}", track.trackNumber.toString())
        .replace("{disc:02d}", "%02d".format(track.discNumber))
        .replace("{disc}", track.discNumber.toString())
        .replace("{title}", track.title)
        .replace("{year}", "")
    return filled.split("/")
        .map { sanitizeSegment(it) }
        .filter { it.isNotBlank() }
        .joinToString("/")
}

private fun sanitizeSegment(segment: String): String =
    segment.replace(Regex("""[\\/:*?"<>|]"""), "_").trim()

fun namingPreview(template: String): String {
    val filled = template
        .replace("{artist}", "Daft Punk")
        .replace("{album}", "Random Access Memories")
        .replace("{playlist}", "My Playlist")
        .replace("{track:02d}", "07")
        .replace("{disc:02d}", "1")
        .replace("{title}", "Touch")
        .replace("{year}", "2013")
    val parts = filled.split("/").joinToString("/") { it.trim() }
    return if (parts.endsWith(".m4a")) parts else "$parts.m4a"
}
