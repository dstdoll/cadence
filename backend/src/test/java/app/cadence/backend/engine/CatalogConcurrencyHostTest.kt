package app.cadence.backend.engine

import app.cadence.backend.real.RealCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * The app reads the catalogue from the main thread (Home, album pages) while the start-up seed,
 * searches and artist/playlist loads write to it from background threads. This drives all of
 * that at once against the live API and fails on any exception a reader or writer throws.
 * Run: ./gradlew :backend:testDebugUnitTest --tests '*CatalogConcurrencyHostTest*' -i
 */
class CatalogConcurrencyHostTest {

    private val cfg: AmpConfig by lazy {
        TestSecrets.ampConfig()
    }

    @Test
    fun readersSurviveConcurrentLoads() = runBlocking {
        val catalog = RealCatalog(AmpEngine(cfg))
        val writing = AtomicBoolean(true)
        val reads = AtomicLong()

        withContext(Dispatchers.Default) {
            coroutineScope {
                // What composition does, over and over, from another thread.
                val readers = List(4) {
                    async {
                        while (writing.get()) {
                            val albums = catalog.albums
                            catalog.artists.size
                            catalog.tracks.forEach { it.id.length }
                            catalog.playlists.forEach { it.trackIds.size }
                            albums.take(5).forEach { a ->
                                catalog.tracksOfAlbum(a.id).size
                                catalog.albumsOfArtist(a.artistId).size
                            }
                            reads.incrementAndGet()
                            yield()
                        }
                    }
                }
                val writers = listOf(
                    async { catalog.seedBrowse(); catalog.loadPersonal() },
                    async { catalog.search("radiohead") },
                    async { catalog.search("daft punk") },
                    async { catalog.suggest("bjor") },
                    async { catalog.search("boards of canada") },
                    async { catalog.search("aphex twin").also { catalog.prepareArtist("ar:Aphex Twin") } },
                )
                launch {
                    runCatching { writers.awaitAll() }
                    writing.set(false)
                }
                writers.awaitAll()
                readers.awaitAll()
            }
        }
        println("[concurrency] reads=${reads.get()} albums=${catalog.albums.size} artists=${catalog.artists.size} " +
            "tracks=${catalog.tracks.size} playlists=${catalog.playlists.size}")
        assertTrue("catalogue ended up empty", catalog.albums.isNotEmpty() && catalog.tracks.isNotEmpty())
        assertTrue("readers barely ran; the test proved nothing", reads.get() > 100)
    }
}
