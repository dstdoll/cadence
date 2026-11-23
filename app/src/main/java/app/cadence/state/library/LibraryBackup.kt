package app.cadence.state.library

import app.cadence.backend.model.Playlist
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class LibraryBackupDoc(
    val version: Int = 1,
    val savedTracks: Set<String> = emptySet(),
    val savedAlbums: Set<String> = emptySet(),
    val favorites: Set<String> = emptySet(),
    val pinned: List<String> = emptyList(),
    val followedArtists: Set<String> = emptySet(),
    val playlists: List<Playlist> = emptyList(),
    val folders: List<PlaylistFolder> = emptyList(),
    val playlistFolders: Map<String, String> = emptyMap(),
    val unfavorited: Set<String> = emptySet(),
    val hiddenFromLibrary: Set<String> = emptySet(),
)

private val backupJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = true
}

private val validIdPattern = Regex("""^[A-Za-z0-9._:-]{1,128}$""")

private fun String.isSafeId(): Boolean = validIdPattern.matches(this)

fun UserLibrary.toBackupJson(): String = backupJson.encodeToString(
    LibraryBackupDoc(
        version = 1,
        savedTracks = savedTracks,
        savedAlbums = savedAlbums,
        favorites = favorites,
        pinned = pinned,
        followedArtists = followedArtists,
        playlists = playlists,
        folders = folders,
        playlistFolders = playlistFolders,
        unfavorited = unfavorited,
        hiddenFromLibrary = hiddenFromLibrary,
    ),
)

fun UserLibrary.restoreFromBackupJson(raw: String, merge: Boolean): Result<UserLibrary> = runCatching {
    val doc = backupJson.decodeFromString<LibraryBackupDoc>(raw)
    require(doc.version == 1) { "Unsupported backup version: ${doc.version}" }

    val cleanTracks = doc.savedTracks.filterTo(LinkedHashSet()) { it.isSafeId() }
    val cleanAlbums = doc.savedAlbums.filterTo(LinkedHashSet()) { it.isSafeId() }
    val cleanFavs = doc.favorites.filterTo(LinkedHashSet()) { it.isSafeId() }
    val cleanPinned = doc.pinned.filter { it.isSafeId() }.distinct()
    val cleanArtists = doc.followedArtists.filterTo(LinkedHashSet()) { it.isSafeId() }
    val cleanUnfav = doc.unfavorited.filterTo(LinkedHashSet()) { it.isSafeId() }
    val cleanHidden = doc.hiddenFromLibrary.filterTo(LinkedHashSet()) { it.isSafeId() }
    val cleanPlaylists = doc.playlists.mapNotNull { p ->
        if (!p.id.isSafeId() || p.title.isBlank()) return@mapNotNull null
        p.copy(
            title = p.title.trim().take(120),
            description = p.description.trim().take(500),
            trackIds = p.trackIds.filter { it.isSafeId() },
            cover = p.cover?.takeIf { it.startsWith("https://") },
            motion = null,
        )
    }

    val cleanFolders = doc.folders.mapNotNull { f ->
        if (!f.id.isSafeId() || f.name.isBlank()) null
        else f.copy(name = f.name.trim().take(80), parentId = f.parentId?.takeIf { it != f.id && it.isSafeId() })
    }
    val folderIdSet = cleanFolders.mapTo(HashSet()) { it.id }
    val cleanPlaylistFolders = doc.playlistFolders.filter { (k, v) -> k.isSafeId() && v in folderIdSet }

    if (merge) {
        val mergedById = LinkedHashMap<String, Playlist>()
        playlists.forEach { mergedById[it.id] = it }
        cleanPlaylists.forEach { mergedById[it.id] = it }
        val mergedFolders = LinkedHashMap<String, PlaylistFolder>()
        folders.forEach { mergedFolders[it.id] = it }
        cleanFolders.forEach { mergedFolders[it.id] = it }
        copy(
            savedTracks = savedTracks + cleanTracks,
            savedAlbums = savedAlbums + cleanAlbums,
            favorites = favorites + cleanFavs,
            pinned = (pinned + cleanPinned).distinct(),
            followedArtists = followedArtists + cleanArtists,
            playlists = mergedById.values.toList(),
            folders = mergedFolders.values.toList(),
            playlistFolders = playlistFolders + cleanPlaylistFolders,
            unfavorited = unfavorited + cleanUnfav,
            hiddenFromLibrary = hiddenFromLibrary + cleanHidden,
        )
    } else {
        copy(
            savedTracks = cleanTracks,
            savedAlbums = cleanAlbums,
            favorites = cleanFavs,
            pinned = cleanPinned,
            followedArtists = cleanArtists,
            playlists = cleanPlaylists,
            folders = cleanFolders,
            playlistFolders = cleanPlaylistFolders,
            unfavorited = cleanUnfav,
            hiddenFromLibrary = cleanHidden,
        )
    }
}
