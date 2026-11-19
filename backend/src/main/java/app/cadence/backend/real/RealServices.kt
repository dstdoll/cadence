package app.cadence.backend.real

import app.cadence.backend.api.SearchScope
import app.cadence.backend.api.SearchService
import app.cadence.backend.api.Suggestions
import app.cadence.backend.api.RecommendationService
import app.cadence.backend.model.TrackInfo
import app.cadence.backend.search.SearchCorpus
import app.cadence.backend.search.SearchEngine
import app.cadence.backend.search.SearchResults
import app.cadence.backend.engine.CadenceLog

/**
 * Search for the real backend. "All music" asks amp-api and keeps Apple's ranking; "Your
 * library" ranks the user's own saved items locally, since that set lives on the device.
 */
class RealSearchService(private val catalog: RealCatalog) : SearchService {

    private fun libraryCorpus(scope: SearchScope, ids: Set<String>): SearchCorpus {
        val tracks = ids.mapNotNull(catalog::track)
        val albums = (tracks.map { it.album } + scope.savedAlbumIds.mapNotNull(catalog::album)).distinctBy { it.id }
        return SearchCorpus(
            artists = tracks.map { it.artist }.distinctBy { it.id },
            albums = albums,
            tracks = tracks,
            playlists = scope.userPlaylists,
            artistName = { id -> catalog.artist(id)?.name ?: id.removePrefix("ar:") },
        )
    }

    override suspend fun search(query: String, scope: SearchScope): SearchResults {
        if (query.isBlank()) return SearchResults(emptyList(), emptyMap())
        val ids = scope.libraryTrackIds
        CadenceLog.i("search", "query='$query' scope=${if (ids == null) "catalog" else "library(${ids.size})"}")
        return if (ids == null) catalog.search(query) else SearchEngine.search(libraryCorpus(scope, ids), query)
    }

    override suspend fun suggest(query: String, scope: SearchScope): Suggestions {
        if (query.isBlank()) return Suggestions(emptyList(), emptyList())
        val ids = scope.libraryTrackIds ?: return catalog.suggest(query)
        val corpus = libraryCorpus(scope, ids)
        val names = corpus.tracks.map { it.track.title } + corpus.artists.map { it.name } +
            corpus.albums.map { it.title } + corpus.playlists.map { it.title }
        return Suggestions(SearchEngine.completions(names, query), SearchEngine.search(corpus, query).top.take(6))
    }
}

/** "More like this" and stations from available cached tracks via [Recommender]. */
class RealRecommendationService(private val catalog: RealCatalog) : RecommendationService {
    private fun pool() = catalog.tracks.filter { it.track.available }

    override suspend fun similar(seed: TrackInfo, exclude: Set<String>, count: Int): List<TrackInfo> =
        app.cadence.backend.search.Recommender.similar(seed, pool(), exclude, count)

    override suspend fun discovery(exclude: Set<String>, count: Int): List<TrackInfo> =
        app.cadence.backend.search.Recommender.discovery(pool(), exclude, count)

    override suspend fun heavyRotation(seedIds: Set<String>, exclude: Set<String>, count: Int): List<TrackInfo> =
        app.cadence.backend.search.Recommender.heavyRotation(pool(), seedIds, exclude, count)
}
