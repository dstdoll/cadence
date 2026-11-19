package app.cadence.backend.engine

/**
 * Values a download template can interpolate. Kept as plain strings/ints so the
 * same engine serves albums (track + album), playlists (also a name) and singles.
 */
data class TemplateValues(
    val artist: String = "",
    val album: String = "",
    val albumArtist: String = "",
    val title: String = "",
    val track: Int = 0,
    val disc: Int = 0,
    /** Full ISO release date ("2013-05-17") or empty. */
    val date: String = "",
    val genre: String = "",
    val playlist: String = "",
    /** The resolved rendition's codec ("alac", "aac", or "atmos"/"spatial" for the Atmos mixes), PCM bit depth, and sample rate in Hz. */
    val codec: String = "",
    val bitDepth: Int = 0,
    val sampleRate: Int = 0,
) {
    /** `{year}` is the leading four characters of [date]. */
    val year: String get() = date.take(4)

    /** `{codec}` -> "ALAC", "AAC", "ATMOS" or "SPATIAL"; empty when unknown. */
    val codecLabel: String get() = codec.uppercase()

    /** `{bitdepth}` -> "24B"; empty when unknown. */
    val bitDepthLabel: String get() = if (bitDepth > 0) "${bitDepth}B" else ""

    /** `{samplerate}` -> "96kHz" / "44.1kHz"; empty when unknown. */
    val sampleRateLabel: String
        get() = if (sampleRate <= 0) "" else {
            val k = sampleRate / 1000.0
            (if (k % 1.0 == 0.0) k.toInt().toString() else k.toString()) + "kHz"
        }

    /** `{quality}` -> Apple-style "[ALAC] [24B-96kHz]", dropping any unknown part. */
    val qualityLabel: String
        get() {
            val depthRate = listOf(bitDepthLabel, sampleRateLabel).filter { it.isNotBlank() }.joinToString("-")
            return listOf(codecLabel, depthRate).filter { it.isNotBlank() }.joinToString(" ") { "[$it]" }
        }
}

/**
 * amdl-style path templates, adapted to Android's SAF/MediaStore constraints.
 *
 * Tokens: `{artist} {album} {albumartist} {title} {track} {track:02d} {disc}
 * {disc:02d} {year} {date} {genre} {playlist}`. Unknown braces are left alone so
 * a typo is visible instead of silently disappearing.
 */
object PathTemplate {
    val TOKENS = listOf(
        "{artist}", "{album}", "{albumartist}", "{title}",
        "{track:02d}", "{track}", "{disc:02d}", "{disc}",
        "{year}", "{date}", "{genre}", "{playlist}",
        "{codec}", "{bitdepth}", "{samplerate}", "{quality}",
    )

    /** Tokens that only make sense on a single track (a file name), never a folder for many tracks. */
    val TRACK_ONLY_TOKENS = setOf("{title}", "{track:02d}", "{track}", "{disc:02d}", "{disc}")

    /** Token only meaningful when downloading as part of a playlist. */
    val PLAYLIST_ONLY_TOKENS = setOf("{playlist}")

    fun expand(template: String, v: TemplateValues): String {
        var out = template
        // Padded forms first: `{track:02d}` must not be eaten by `{track}`.
        out = out.replace("{track:02d}", if (v.track > 0) "%02d".format(v.track) else "")
        out = out.replace("{disc:02d}", if (v.disc > 0) "%02d".format(v.disc) else "")
        out = out.replace("{track}", if (v.track > 0) v.track.toString() else "")
        out = out.replace("{disc}", if (v.disc > 0) v.disc.toString() else "")
        out = out.replace("{artist}", v.artist)
        out = out.replace("{albumartist}", v.albumArtist.ifBlank { v.artist })
        out = out.replace("{album}", v.album)
        out = out.replace("{title}", v.title)
        out = out.replace("{year}", v.year)
        out = out.replace("{date}", v.date)
        out = out.replace("{genre}", v.genre)
        out = out.replace("{playlist}", v.playlist)
        // Quality: combined form first so "{quality}" isn't partially eaten by the single tokens.
        out = out.replace("{quality}", v.qualityLabel)
        out = out.replace("{codec}", v.codecLabel)
        out = out.replace("{bitdepth}", v.bitDepthLabel)
        out = out.replace("{samplerate}", v.sampleRateLabel)
        return out
    }

    /**
     * Folder path: the *template* is split, then each segment is expanded and
     * sanitised. Splitting after expansion would let an album/title containing
     * "/" (or "..") inject extra path segments.
     */
    fun folderPath(template: String, v: TemplateValues): String =
        template.split('/')
            .map { sanitizeSegment(expand(it, v)) }
            .filter { it.isNotBlank() }
            .joinToString("/")

    /** One file name: separators become dashes so a template cannot escape its folder. */
    fun fileName(template: String, v: TemplateValues): String {
        val expanded = expand(template, v).replace('/', '-').replace('\\', '-')
        return sanitizeSegment(expanded).ifBlank { v.title.ifBlank { "track" } }
    }

    /** `folder/file`, the form the engine's HLS writer already understands. */
    fun path(folderTemplate: String, fileTemplate: String, v: TemplateValues): String {
        val folder = folderPath(folderTemplate, v)
        val file = fileName(fileTemplate, v)
        return if (folder.isBlank()) file else "$folder/$file"
    }

    /**
     * Rejects `/ \ : * ? " < > |` and NUL, collapses whitespace, trims trailing
     * dots/spaces, and neutralises "." / ".." / leading dots so a template (or a
     * title) can never create a traversal or a hidden path segment.
     */
    fun sanitizeSegment(raw: String): String {
        var s = raw
            .replace(Regex("""[\\/:*?"<>|\u0000]"""), "_")
            .replace(Regex("""\s+"""), " ")
            .replace(Regex("""_{2,}"""), "_")
            .trim()
            .trimEnd('.', ' ')
        if (s == "." || s == "..") s = "_"
        if (s.startsWith('.')) s = "_$s"
        return s
    }

    /** Sample used by the Settings previews, so the tree is visible before a download. */
    val SAMPLE = TemplateValues(
        artist = "Daft Punk",
        album = "Random Access Memories",
        albumArtist = "Daft Punk",
        title = "Touch",
        track = 7,
        disc = 1,
        date = "2013-05-17",
        genre = "Electronic",
        playlist = "Late Night Drives",
        codec = "ALAC",
        bitDepth = 24,
        sampleRate = 96000,
    )
}
