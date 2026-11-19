package app.cadence.backend.model

import kotlinx.serialization.Serializable

/*
 * Catalog models. These are exactly the shapes the frontend renders, and the JSON your backend
 * should return (field names are the JSON keys). Every field is marked with what goes in it.
 */

@Serializable
data class Artist(
    // BACKEND: here goes the artist's stable id (any string, unique across artists).
    val id: String,
    // BACKEND: here goes the artist name, as shown on screen ("Mira Okafor").
    val name: String,
    // BACKEND: here goes the short biography shown under "About" on the artist page.
    val bio: String = "",
    // BACKEND: here goes the artist's accent colour (#RRGGBB) used for their avatar. Optional.
    val color: String = "#888888",
    // BACKEND: here goes the artist photo URL, or null to draw the tinted placeholder.
    val cover: String? = null,
    // BACKEND: here goes where the artist is from ("Abingdon, Oxfordshire, England"); blank when unknown.
    val origin: String = "",
    // BACKEND: here goes the year or date the artist was born or the group formed ("1985").
    val formed: String = "",
    // BACKEND: here go the artist's genres, most relevant first.
    val genres: List<String> = emptyList(),
    /** True when [bio] is placeholder text because the catalogue has none for this artist. */
)

@Serializable
data class Album(
    // BACKEND: here goes the album's stable id.
    val id: String,
    // BACKEND: here goes the album title.
    val title: String,
    // BACKEND: here goes the id of the album's primary artist (must match an Artist.id).
    val artistId: String,
    // BACKEND: here goes the release year (shown as "Pop · 2025").
    val year: Int,
    // BACKEND: here goes the genre name; it also drives Browse categories and genre pages.
    val genre: String,
    // BACKEND: here go 1-3 colours (#RRGGBB) taken from the cover. The first one seeds album-art theming.
    // With real cover images, also add a cover URL field and load it instead of the procedural art.
    val colors: List<String>,
    // BACKEND: here goes the still cover image URL (Apple artwork template already
    // resolved to a concrete size), or null to fall back to procedural art.
    val cover: String? = null,
    // BACKEND: here goes the URL of a short looping motion-cover video (H.264 MP4, muted), or null.
    val motion: String? = null,
    // BACKEND: here goes the full release date, ISO-8601 ("2025-06-20"). Shown on the Credits page.
    val released: String? = null,
    // BACKEND: here goes the ℗ copyright line shown in the album footer ("℗ 2022 Some Label"), or null.
    val copyright: String? = null,
    // BACKEND: here goes the record label / distributor shown in the album footer, or null.
    val recordLabel: String? = null,
    // BACKEND: here goes the number of songs on the album; used to split a discography into
    // Albums vs Singles & EPs on the artist page (0 = unknown).
    val trackCount: Int = 0,
    // BACKEND: true when this is a single/EP release (Apple's isSingle), else false/unknown.
    val isSingle: Boolean = false,
    // BACKEND: false when the release is not available in the user's storefront.
    val available: Boolean = true,
)

@Serializable
data class Track(
    // BACKEND: here goes the song's stable id.
    val id: String,
    // BACKEND: here goes the title of the song.
    val title: String,
    // BACKEND: here goes the id of the performing artist (Artist.id).
    val artistId: String,
    // BACKEND: here goes the id of the album the song is on (Album.id).
    val albumId: String,
    // BACKEND: here goes the track number on the album (orders album pages).
    val number: Int,
    // BACKEND: here goes the duration in milliseconds.
    val durationMs: Long,
    // BACKEND: here goes the audio location. A streamable URL.
    val audio: String,
    // BACKEND: here goes where to fetch lyrics (path/URL), or null if the song has none.
    val lyrics: String? = null,
    // BACKEND: here goes the song metadata about encoding (codec, bit depth, sample rate...), shown as quality badges.
    val format: AudioFormatInfo? = null,
    // BACKEND: here go the song credits (performers, writers, producers) for the Credits page.
    val credits: Credits? = null,
    // BACKEND: false when the track is not available in the user's storefront.
    val available: Boolean = true,
)

@Serializable
data class Credit(
    // BACKEND: here goes the person's or group's name.
    val name: String,
    // BACKEND: here go their roles on this song, comma-separated ("Vocals, Guitar").
    val roles: String,
)

@Serializable
data class Credits(
    // BACKEND: here go the people performing on the recording.
    val performers: List<Credit> = emptyList(),
    // BACKEND: here go composers and lyricists.
    val writing: List<Credit> = emptyList(),
    // BACKEND: here go producers, mixing and mastering engineers.
    val production: List<Credit> = emptyList(),
)

@Serializable
data class Playlist(
    // BACKEND: here goes the playlist id.
    val id: String,
    // BACKEND: here goes the playlist title.
    val title: String,
    // BACKEND: here goes the one-line description shown under the title.
    val description: String = "",
    // BACKEND: here go the song ids, in play order (each must match a Track.id).
    val trackIds: List<String>,
    // BACKEND: here go 1-3 cover colours (#RRGGBB).
    val colors: List<String> = listOf("#6750A4", "#21005D"),
    // BACKEND: here goes the still cover image URL, or null for procedural art.
    val cover: String? = null,
    // BACKEND: here goes who curated the playlist ("Apple Music Alternativa"); blank when unknown.
    val curator: String = "",
    /** Set by the app for playlists the user made; editorial playlists from the backend leave it false. */
    val userCreated: Boolean = false,
    // BACKEND: here goes an optional motion-cover video URL.
    val motion: String? = null,
    // BACKEND: false when the playlist/station is not available in the user's storefront.
    val available: Boolean = true,
)

/** The whole catalog in one document: what the real catalogue caches on disk. */
@Serializable
data class Catalog(
    val artists: List<Artist>,
    val albums: List<Album>,
    val tracks: List<Track>,
    val playlists: List<Playlist>,
    /** The account's own library at the time of the snapshot; empty in older snapshots. */
    val account: AccountLibrary = AccountLibrary(),
)

/**
 * The Apple account's own library, as catalogue ids, which is what can be played. Songs the user
 * uploaded or that Apple could not match have no catalogue id and are not included.
 */
@Serializable
data class AccountLibrary(
    val trackIds: Set<String> = emptySet(),
    val albumIds: Set<String> = emptySet(),
    val artistIds: Set<String> = emptySet(),
) {
    companion object {
        val EMPTY = AccountLibrary()
    }
}

/** A track joined with its album and artist, the shape almost every screen wants. Built client-side. */
data class TrackInfo(val track: Track, val album: Album, val artist: Artist) {
    val id get() = track.id
}

// ---- Lyrics (parsed from LRC by LrcParser; the backend sends text, not these objects) ----

enum class Voice { Primary, Secondary, Both }

data class LyricWord(val startMs: Long, val endMs: Long, val text: String)

data class LyricLine(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val words: List<LyricWord> = emptyList(),
    val voice: Voice = Voice.Primary,
    val background: List<LyricWord> = emptyList(),
)

sealed interface Lyrics {
    data class Synced(val lines: List<LyricLine>) : Lyrics
    data class Plain(val text: String) : Lyrics
    data object None : Lyrics
}


// ---- Home (not serialized: rebuilt from the account on every launch) ----

/** One thing on a Home shelf. Radio stations travel as playlists (their ids start with `st:`). */
sealed interface ShelfItem {
    val key: String
    data class AlbumItem(val album: Album) : ShelfItem { override val key get() = "al:" + album.id }
    data class PlaylistItem(val playlist: Playlist) : ShelfItem { override val key get() = "p:" + playlist.id }
}

/**
 * A row of Home as the service curates it for this account: its own title, its own order.
 * [hero] marks the shelf the service presents as the large, leading one.
 */
data class HomeShelf(val id: String, val title: String, val hero: Boolean, val items: List<ShelfItem>)
