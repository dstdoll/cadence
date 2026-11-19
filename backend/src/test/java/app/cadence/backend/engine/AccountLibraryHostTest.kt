package app.cadence.backend.engine

import app.cadence.backend.real.RealCatalog
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Tier 2 (network + provisioned `amp.json`): loads the real account library through the same code
 * the app runs, and checks every listed song can be shown. Prints counts only.
 */
class AccountLibraryHostTest {
    private val cfg: AmpConfig by lazy {
        TestSecrets.ampConfig()
    }

    @Test
    fun theAccountLibraryLoadsAndResolves() = runBlocking {
        val catalog = RealCatalog(AmpEngine(cfg))
        val started = System.currentTimeMillis()
        catalog.loadAccountLibrary()
        val account = catalog.accountLibrary
        println("[library] songs=${account.trackIds.size} albums=${account.albumIds.size} artists=${account.artistIds.size} in ${System.currentTimeMillis() - started}ms")
        assertTrue("no songs loaded", account.trackIds.isNotEmpty())
        val unresolved = account.trackIds.count { catalog.track(it) == null }
        val noAudio = account.trackIds.count { catalog.track(it)?.track?.audio.isNullOrBlank() }
        println("[library] unresolved songs=$unresolved without stream=$noAudio")
        assertTrue("$unresolved library songs cannot be shown", unresolved == 0)
        assertTrue(account.albumIds.all { catalog.album(it) != null })
        assertTrue(account.artistIds.all { catalog.artist(it) != null })
        // Saved songs that are not in memory are looked up in the catalogue.
        val fresh = RealCatalog(AmpEngine(cfg))
        val sample = account.trackIds.take(3)
        fresh.prepareTracks(sample + listOf("t1", "al1"))
        println("[library] prepareTracks resolved=${sample.count { fresh.track(it) != null }}/${sample.size}")
        assertTrue(sample.all { fresh.track(it) != null })
    }
}
