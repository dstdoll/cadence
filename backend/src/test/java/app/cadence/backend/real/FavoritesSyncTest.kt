package app.cadence.backend.real

import app.cadence.backend.engine.AmpConfig
import app.cadence.backend.engine.AmpEngine
import app.cadence.backend.model.Album
import app.cadence.backend.model.Artist
import app.cadence.backend.model.Track
import app.cadence.backend.model.TrackInfo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FavoritesSyncTest {

    private class FakeFavoritesStore(initial: List<TrackInfo>) : FavoritesStore {
        private val map = LinkedHashMap<String, TrackInfo>().apply {
            initial.forEach { put(it.id, it) }
        }
        var reconcileCalls = 0
            private set

        override fun loadAll(): Pair<Set<String>, List<TrackInfo>> =
            map.keys.toSet() to map.values.toList()

        override fun upsertLocal(track: TrackInfo?, trackId: String, favorite: Boolean) {
            if (favorite && track != null) map[trackId] = track else if (!favorite) map.remove(trackId)
        }

        override fun markSynced(trackId: String, favorite: Boolean) {}
        override fun pendingOps(): List<Pair<String, Boolean>> = emptyList()

        override fun reconcileRemote(remoteTracks: List<TrackInfo>) {
            reconcileCalls++
            map.clear()
            remoteTracks.forEach { map[it.id] = it }
        }

        override fun clear() {
            map.clear()
        }
    }

    @Test
    fun failedRemoteLoad_preservesCachedFavoritesWithoutWipingStore() = runBlocking {
        val artist = Artist(id = "ar1", name = "Artist")
        val album = Album(id = "al1", title = "Album", artistId = "ar1", year = 2020, genre = "Pop", colors = listOf("#000000"))
        val cached = (1..420).map { i ->
            TrackInfo(
                Track(id = "t$i", title = "Song $i", artistId = "ar1", albumId = "al1", number = i, durationMs = 180_000, audio = ""),
                album,
                artist,
            )
        }
        val store = FakeFavoritesStore(cached)
        val catalog = RealCatalog(AmpEngine(AmpConfig("", "", "us", "en-US")), favoritesStore = store)
        catalog.hydrateFavoritesFromStore()
        assertEquals(420, catalog.favoriteTrackIds.size)

        // When network/token is absent, loadLibrary() cannot fetch remote playlists and must NOT wipe cached favourites.
        catalog.loadLibrary()
        assertEquals(0, store.reconcileCalls)
        assertEquals(420, catalog.favoriteTrackIds.size)
        assertTrue("t420" in catalog.favoriteTrackIds)
    }
}
