package app.cadence.backend.engine.media

import android.content.Context
import android.provider.MediaStore
import app.cadence.backend.engine.CadenceLog

/**
 * Finds files Cadence downloaded before the registry existed (or that the registry lost), by their tags: the files in
 * `Music/Cadence` carry the song's title, artist and album. Used so such a song is reported as "already downloaded"
 * (quality unknown) instead of silently getting a second `(1)` copy.
 */
object LegacyDownloads {
    private fun norm(s: String?) = s.orEmpty().trim().lowercase().replace(Regex("\\s+"), " ")

    /** Whether a stored file's tags describe the wanted song. Artist matches when either contains the other (tags may carry "A & B"). */
    internal fun sameSong(title: String, artist: String, album: String, tTitle: String?, tArtist: String?, tAlbum: String?): Boolean {
        if (norm(title) != norm(tTitle)) return false
        val a = norm(artist); val b = norm(tArtist)
        val artistOk = a.isNotEmpty() && b.isNotEmpty() && (a == b || a in b || b in a)
        val albumOk = norm(album).let { it.isEmpty() || norm(tAlbum).isEmpty() || it == norm(tAlbum) }
        return artistOk && albumOk
    }

    /** The files already in `Music/Cadence` for this song, as records with an unknown quality. */
    fun find(context: Context, trackId: String, title: String, artist: String, album: String): List<DownloadRecord> = runCatching {
        val out = ArrayList<DownloadRecord>()
        val proj = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.DATE_ADDED,
        )
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj,
            "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Audio.Media.TITLE} = ?",
            arrayOf("Music/Cadence%", title), null,
        )?.use { c ->
            while (c.moveToNext()) {
                if (!sameSong(title, artist, album, c.getString(1), c.getString(2), c.getString(3))) continue
                val uri = android.content.ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, c.getLong(0)).toString()
                out += DownloadRecord(trackId, "", uri, c.getString(4).orEmpty(), c.getLong(5) * 1000)
            }
        }
        out
    }.onFailure { CadenceLog.e("download", "legacy download lookup failed", it) }.getOrDefault(emptyList())
}
