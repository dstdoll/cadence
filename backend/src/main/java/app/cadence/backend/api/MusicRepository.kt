package app.cadence.backend.api

import app.cadence.backend.model.HomeShelf
import app.cadence.backend.model.Album
import app.cadence.backend.model.Artist
import app.cadence.backend.model.Lyrics
import app.cadence.backend.model.Playlist
import app.cadence.backend.model.TrackInfo

/**
 * The music catalog: everything the UI renders about artists, albums, songs and curated playlists.
 *
 * BACKEND: served by [app.cadence.backend.real.RealCatalog] from Apple Music's catalogue. Another
 * implementation would fetch from its own API and cache locally. Suggested endpoints:
 *   GET /catalog/artists            -> List<Artist>
 *   GET /catalog/albums             -> List<Album>
 *   GET /catalog/tracks             -> List<Track>      (joined into TrackInfo client-side)
 *   GET /catalog/playlists          -> List<Playlist>   (editorial / "Made for you")
 *   GET /tracks/{id}/lyrics         -> LRC text (see LrcParser for the accepted dialect)
 *   GET /tracks/{id}/stream         -> signed audio URL (returned by [audioUri])
 */
interface MusicRepository {
    // BACKEND: here go all artists the user can browse.
    val artists: List<Artist>
    // BACKEND: here go all albums (with cover colours / motion cover URL / release date).
    val albums: List<Album>
    // BACKEND: here go all songs, already joined with their album and artist.
    val tracks: List<TrackInfo>
    // BACKEND: here go curated playlists ("Made for you", editorial).
    val playlists: List<Playlist>

    fun track(id: String): TrackInfo?
    fun album(id: String): Album?
    fun artist(id: String): Artist?
    fun tracksOfAlbum(albumId: String): List<TrackInfo>
    /**
     * Ensures the album's tracklist is loaded and returns it. Real backends fetch
     * lazily; the default assumes the cache is already warm.
     */
    suspend fun prepareAlbum(albumId: String): List<TrackInfo> = tracksOfAlbum(albumId)

    /** Loads a playlist's tracklist on open (networked sources fetch it lazily). */
    suspend fun preparePlaylist(playlistId: String, forceRefresh: Boolean = false): List<TrackInfo> =
        playlists.find { it.id == playlistId }?.trackIds?.mapNotNull(::track) ?: emptyList()
    fun albumsOfArtist(artistId: String): List<Album>

    /** Loads the artist's full discography on open; the default assumes it is already cached. */
    suspend fun prepareArtist(artistId: String): List<Album> = albumsOfArtist(artistId)

    /**
     * Home as the service lays it out for the signed-in account, in its order. Empty when the
     * source has no such thing, in which case Home builds its own rails.
     */
    val shelves: List<HomeShelf> get() = emptyList()

    /** The Apple account's own library (songs, albums, artists); empty for sources without one. */
    val accountLibrary: app.cadence.backend.model.AccountLibrary get() = app.cadence.backend.model.AccountLibrary.EMPTY

    /**
     * Makes sure these songs can be shown: any not in memory are fetched from the catalogue.
     * Songs saved in an earlier session may have dropped out of the cache. No-op by default.
     */
    suspend fun prepareTracks(ids: Collection<String>) {}

    /** Increments whenever the collections above change, so screens can re-read them. */
    val revision: kotlinx.coroutines.flow.StateFlow<Int> get() = STATIC_REVISION

    /** The artist's most played songs in catalogue order; falls back to whatever is cached. */
    fun topSongs(artistId: String): List<TrackInfo> = tracks.filter { it.artist.id == artistId }

    /** Loads the album page's related rows; afterwards [otherVersions] and [relatedAlbums] answer. */
    suspend fun prepareAlbumExtras(albumId: String) {}

    /** Other editions of the album (deluxe, remaster...); empty when there are none or not loaded. */
    fun otherVersions(albumId: String): List<Album> = emptyList()

    /** Albums the catalogue relates to this one ("you might also like"). */
    fun relatedAlbums(albumId: String): List<Album> = emptyList()

    /** Artists the catalogue lists as similar; empty when it has none. */
    fun similarArtists(artistId: String): List<Artist> = emptyList()

    /** Editorial playlists featuring the artist. */
    fun artistPlaylists(artistId: String): List<Playlist> = emptyList()

    /** The signed-in account's own playlists (profile), newest state from the last sync. */
    val libraryPlaylists: List<Playlist> get() = emptyList()

    /** Songs the account marked as favourites on the service, by track id. */
    val favoriteTrackIds: Set<String> get() = emptySet()

    /**
     * Pushes a favourite to the account on the service (true = favourite, false = clear), so it
     * reaches the real profile and its "Favourite Songs". Returns whether the service accepted it;
     * the default is a no-op for backends without a signed-in account.
     */
    suspend fun setFavorite(trackId: String, favorite: Boolean): Boolean = false

    /** A playlist by id, including ones reached through search that are not on any rail. */
    fun playlist(id: String): Playlist? = playlists.firstOrNull { it.id == id }

    // BACKEND: here goes the playable URL for a song (https://... or a signed CDN link). ExoPlayer plays it as-is.
    fun audioUri(track: TrackInfo): String

    // BACKEND: here goes the playable URL for an album's looping motion cover video, or null.
    fun motionUri(path: String?): String?

    // BACKEND: here go the song's lyrics (synced LRC preferred, plain text accepted, Lyrics.None if absent).
    suspend fun lyrics(track: TrackInfo): Lyrics

    /**
     * Re-fetches the personalised home feed (recommendations, recently played, mixes). Home calls
     * this on pull-to-refresh. The *sections* are deterministic (identity/order stay put); their
     * *contents* are what change. Default is a no-op for static sources.
     * BACKEND: honour amp-api cache/ETag and the real mix endpoints.
     */
    suspend fun refresh() {}
}

private val STATIC_REVISION = kotlinx.coroutines.flow.MutableStateFlow(0)
