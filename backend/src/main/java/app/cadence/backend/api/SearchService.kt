package app.cadence.backend.api

import app.cadence.backend.model.Playlist
import app.cadence.backend.search.SearchHit
import app.cadence.backend.search.SearchResults

/**
 * What to search in. `libraryTrackIds == null` means the whole catalog ("All music");
 * otherwise only the user's library ("Your library").
 */
data class SearchScope(
    val libraryTrackIds: Set<String>?,
    val savedAlbumIds: Set<String> = emptySet(),
    val userPlaylists: List<Playlist> = emptyList(),
)

/** Live suggestions while typing: query completions, then direct entity matches. */
data class Suggestions(val completions: List<String>, val matches: List<SearchHit>)

/**
 * BACKEND: search belongs on the server once the catalog is large. Suggested endpoints:
 *   GET /search?q=&scope=all|library&types=artist,album,song,playlist,lyrics  -> ranked hits per type
 *   GET /search/suggest?q=&scope=                                            -> { completions[], matches[] }
 * Lyrics search needs a full-text index over lyric lines. Library results are ranked locally with SearchEngine.
 */
interface SearchService {
    suspend fun search(query: String, scope: SearchScope): SearchResults
    suspend fun suggest(query: String, scope: SearchScope): Suggestions
}
