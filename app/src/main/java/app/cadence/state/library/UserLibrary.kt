package app.cadence.state.library

import androidx.annotation.StringRes
import app.cadence.R
import app.cadence.backend.model.Playlist
import kotlinx.serialization.Serializable

/** Library sections the user can show, hide and reorder (Library -> Edit). */
enum class LibrarySection(val title: String, @StringRes val labelRes: Int) {
    Pinned("Pinned", R.string.library_pinned), Playlists("Playlists", R.string.kind_playlists),
    Artists("Artists", R.string.kind_artists), Albums("Albums", R.string.kind_albums), Songs("Songs", R.string.kind_songs),
    Favorites("Favorites", R.string.kind_favorites), MadeForYou("Made For You", R.string.kind_made_for_you),
    Genres("Genres", R.string.kind_genres), RecentlyAdded("Recently Added", R.string.library_recently_added),
}

@Serializable
data class SectionPref(val section: LibrarySection, val visible: Boolean)

@Serializable
data class PlaylistFolder(
    val id: String,
    val name: String,
    val parentId: String? = null,
)

/**
 * Everything the user owns locally. "Library" is what they've chosen to keep (a subset of the
 * catalog), which is what makes the All music / Your library search scopes meaningful.
 */
@Serializable
data class UserLibrary(
    val seeded: Boolean = false,
    val savedTracks: Set<String> = emptySet(),
    val savedAlbums: Set<String> = emptySet(),
    val favorites: Set<String> = emptySet(),
    /** Ids prefixed by kind: "t:", "al:", "p:". Shown first in Library. */
    val pinned: List<String> = emptyList(),
    val suggestLess: Set<String> = emptySet(),
    /** Artist ids the user follows (from the artist page Follow button). Local-only for now. */
    val followedArtists: Set<String> = emptySet(),
    val playlists: List<Playlist> = emptyList(),
    val folders: List<PlaylistFolder> = emptyList(),
    val playlistFolders: Map<String, String> = emptyMap(),
    val recentTracks: List<String> = emptyList(),
    val recentSearches: List<String> = emptyList(),
    val sections: List<SectionPref> = DEFAULT_SECTIONS,
    val lastQueue: List<String> = emptyList(),
    val lastIndex: Int = 0,
    val lastPositionMs: Long = 0,
    /**
     * Songs favourited on the Apple account that the user un-favourited in the app. Apple is not
     * told (there is no write endpoint for this token); the app stops showing them as favourites.
     */
    val unfavorited: Set<String> = emptySet(),
    /**
     * Songs of the Apple account's library that the user removed in the app. Apple is not told;
     * the app stops listing them.
     */
    val hiddenFromLibrary: Set<String> = emptySet(),
) {
    /** Saved here, or in the account's library ([account]) and not removed here. */
    fun inLibrary(trackId: String, account: Set<String> = emptySet()) =
        trackId in savedTracks || (trackId in account && trackId !in hiddenFromLibrary)

    /** A favourite here, or on the account ([account]) and not un-favourited here. */
    fun isFavorite(id: String, account: Set<String> = emptySet()) =
        id in favorites || (id in account && id !in unfavorited)

    /** Every favourite id: the local ones plus the account's, minus those un-favourited here. */
    fun favoriteIds(account: Set<String> = emptySet()): Set<String> = favorites + (account - unfavorited)

    fun addToLibrary(trackIds: Collection<String>, albumId: String? = null) =
        copy(
            savedTracks = savedTracks + trackIds, savedAlbums = albumId?.let { savedAlbums + it } ?: savedAlbums,
            hiddenFromLibrary = hiddenFromLibrary - trackIds.toSet(),
        )

    fun removeFromLibrary(trackId: String, account: Set<String> = emptySet()) =
        copy(
            savedTracks = savedTracks - trackId, favorites = favorites - trackId, pinned = pinned - "t:$trackId",
            hiddenFromLibrary = if (trackId in account) hiddenFromLibrary + trackId else hiddenFromLibrary,
        )

    /**
     * Flips what [isFavorite] reports. Un-favouriting an account favourite records it in
     * [unfavorited]; favouriting it again clears that record.
     */
    fun toggleFavorite(id: String, addToLibrary: Boolean = true, account: Set<String> = emptySet()) =
        if (isFavorite(id, account)) copy(favorites = favorites - id, unfavorited = if (id in account) unfavorited + id else unfavorited)
        else copy(
            favorites = favorites + id, unfavorited = unfavorited - id,
            savedTracks = if (addToLibrary) savedTracks + id else savedTracks, suggestLess = suggestLess - id,
        )

    fun toggleSuggestLess(id: String) =
        copy(suggestLess = if (id in suggestLess) suggestLess - id else suggestLess + id, favorites = favorites - id)

    fun togglePin(key: String) = copy(pinned = if (key in pinned) pinned - key else listOf(key) + pinned)

    // BACKEND: a real follow would also POST to the social-profile follow endpoint.
    fun toggleFollow(artistId: String) =
        copy(followedArtists = if (artistId in followedArtists) followedArtists - artistId else followedArtists + artistId)

    fun played(id: String) = copy(recentTracks = (listOf(id) + recentTracks.filterNot { it == id }).take(50))

    fun clearHistory() = copy(recentTracks = emptyList())

    fun searched(q: String) =
        if (q.isBlank()) this else copy(recentSearches = (listOf(q) + recentSearches.filterNot { it.equals(q, true) }).take(10))

    fun createPlaylist(title: String, trackIds: List<String> = emptyList(), addSongsToLibrary: Boolean = true): Pair<UserLibrary, Playlist> {
        val p = Playlist(
            id = "u" + System.currentTimeMillis(),
            title = title.ifBlank { "New Playlist" },
            trackIds = trackIds,
            colors = PALETTES[playlists.size % PALETTES.size],
            userCreated = true,
        )
        val lib = copy(playlists = playlists + p)
        return (if (addSongsToLibrary) lib.addToLibrary(trackIds) else lib) to p
    }

    fun editPlaylist(id: String, edit: (Playlist) -> Playlist) =
        copy(playlists = playlists.map { if (it.id == id) edit(it) else it })

    fun duplicatePlaylist(source: Playlist, copyLabel: String = "Copy"): Pair<UserLibrary, Playlist> =
        createPlaylist("${source.title} ($copyLabel)", source.trackIds, addSongsToLibrary = false)
            .let { (lib, created) ->
                lib.editPlaylist(created.id) { it.copy(description = source.description) } to created.copy(description = source.description)
            }

    fun deletePlaylist(id: String) = copy(
        playlists = playlists.filterNot { it.id == id },
        pinned = pinned - "p:$id",
        playlistFolders = playlistFolders - id,
    )

    fun createFolder(name: String, parentId: String? = null): Pair<UserLibrary, PlaylistFolder> {
        val validParent = parentId?.takeIf { p -> folders.any { it.id == p } }
        val f = PlaylistFolder(id = "f" + System.currentTimeMillis(), name = name.trim().ifBlank { "Folder" }, parentId = validParent)
        return copy(folders = folders + f) to f
    }

    fun movePlaylistToFolder(playlistId: String, folderId: String?): UserLibrary {
        val next = if (folderId != null && folders.any { it.id == folderId }) {
            playlistFolders + (playlistId to folderId)
        } else {
            playlistFolders - playlistId
        }
        return copy(playlistFolders = next)
    }

    fun deleteFolder(folderId: String): UserLibrary {
        val remaining = folders.filterNot { it.id == folderId }.map {
            if (it.parentId == folderId) it.copy(parentId = null) else it
        }
        val cleanedMap = playlistFolders.filterValues { it != folderId }
        return copy(folders = remaining, playlistFolders = cleanedMap)
    }

    /** Older saves may lack newer sections; append any missing ones (hidden) so Edit can show them. */
    fun normalizedSections(): List<SectionPref> {
        val have = sections.map { it.section }.toSet()
        return sections + LibrarySection.entries.filterNot { it in have }.map { SectionPref(it, false) }
    }

    companion object {
        val DEFAULT_SECTIONS = listOf(
            SectionPref(LibrarySection.Pinned, true),
            SectionPref(LibrarySection.Playlists, true),
            SectionPref(LibrarySection.Artists, true),
            SectionPref(LibrarySection.Albums, true),
            SectionPref(LibrarySection.Songs, true),
            SectionPref(LibrarySection.Favorites, true),
            SectionPref(LibrarySection.MadeForYou, false),
            SectionPref(LibrarySection.Genres, false),
            SectionPref(LibrarySection.RecentlyAdded, true),
        )
        private val PALETTES = listOf(
            listOf("#EF476F", "#FFD166", "#06D6A0"),
            listOf("#5F0F40", "#9A031E", "#FB8B24"),
            listOf("#03045E", "#00B4D8", "#CAF0F8"),
            listOf("#2D6A4F", "#95D5B2", "#D8F3DC"),
        )
    }
}
