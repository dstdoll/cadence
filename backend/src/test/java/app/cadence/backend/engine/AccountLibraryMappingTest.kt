package app.cadence.backend.engine

import app.cadence.backend.model.AccountLibrary
import app.cadence.backend.model.Catalog
import kotlinx.serialization.json.Json
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Account-library pages, in the shape the live `/v1/me/library/{songs,albums,artists}` returns. */
class AccountLibraryMappingTest {
    private fun page(rows: String, next: Boolean) =
        JSONObject("""{"data":[$rows]${if (next) ""","next":"/v1/me/library/songs?offset=100"""" else ""},"meta":{"total":3}}""")

    private val matched = """{"id":"i.YJMz","type":"library-songs","attributes":{"name":"Take On Me"},
        "relationships":{"catalog":{"data":[{"id":"653818297","type":"songs","attributes":{"name":"Take On Me","artistName":"a-ha"}}]}}}"""
    private val uploaded = """{"id":"i.UPLD","type":"library-songs","attributes":{"name":"My upload"},
        "relationships":{"catalog":{"data":[]}}}"""
    private val noRelationships = """{"id":"i.NONE","type":"library-songs","attributes":{"name":"Old"}}"""
    private val stub = """{"id":"i.STUB","type":"library-songs","attributes":{"name":"Stub"},
        "relationships":{"catalog":{"data":[{"id":"99","type":"songs"}]}}}"""

    @Test
    fun onlyRowsWithAPlayableCatalogueCounterpartAreKept() {
        val counterparts = Mapping.catalogCounterparts(page("$matched,$uploaded,$noRelationships,$stub", next = true))
        assertEquals(listOf("653818297"), counterparts.map { it.getString("id") })
        assertTrue(Mapping.catalogCounterparts(JSONObject("{}")).isEmpty())
    }

    @Test
    fun theAccountLibrarySurvivesTheDiskCacheAndOldSnapshotsStillLoad() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val doc = Catalog(emptyList(), emptyList(), emptyList(), emptyList(), AccountLibrary(setOf("1"), setOf("2"), setOf("ar:X")))
        assertEquals(doc.account, json.decodeFromString(Catalog.serializer(), json.encodeToString(Catalog.serializer(), doc)).account)
        val old = """{"artists":[],"albums":[],"tracks":[],"playlists":[]}"""
        assertEquals(AccountLibrary.EMPTY, json.decodeFromString(Catalog.serializer(), old).account)
    }
}
