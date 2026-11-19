package app.cadence.backend.engine

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Pure-logic regressions from the code review of the audit branch. */
class ReviewRegressionTest {
    @get:Rule val tmp = TemporaryFolder()

    // ---- palette: screens draw it as a gradient, which needs two stops ----

    @Test
    fun themeColorsAlwaysHasTwoDistinctStops() {
        val onlyBg = ArtworkPalette.themeColors(listOf("e02200", "", null, "", ""))
        assertEquals(listOf("#e02200", "#1b1b1f"), onlyBg)
        val textEqualsBg = ArtworkPalette.themeColors(listOf("2ec8ff", "2EC8FF", "#2ec8ff", null, null))
        assertEquals(2, textEqualsBg.size)
        val neutral = ArtworkPalette.themeColors(listOf("1b1b1f"))
        assertEquals(2, neutral.distinct().size)
        for (input in listOf(listOf("ffffff"), listOf("000000", "000000"), listOf("abc"))) {
            assertTrue("$input -> ${ArtworkPalette.themeColors(input)}", ArtworkPalette.themeColors(input).size >= 2)
        }
    }

    // ---- favourites: the shapes are those of the live library/playlists response ----

    private fun playlists(vararg rows: String) = JSONArray(rows.joinToString(",", "[", "]"))

    private fun row(id: String, name: String, canEdit: Boolean?, canDelete: Boolean?) = buildString {
        append("""{"id":"$id","type":"library-playlists","attributes":{"name":"$name","hasCatalog":true,"isPublic":false""")
        if (canEdit != null) append(""","canEdit":$canEdit""")
        if (canDelete != null) append(""","canDelete":$canDelete""")
        append(""","playParams":{"id":"$id","kind":"playlist","isLibrary":true}}}""")
    }

    private val own = row("p.6xAAAA", "Eternal sleep state", canEdit = true, canDelete = true)
    private val favourites = row("p.81BBBB", "Canciones favoritas", canEdit = false, canDelete = false)
    private val editorialInLibrary = row("p.ZOCCCC", "Today's Hits", canEdit = false, canDelete = true)

    @Test
    fun favouritesIsTheOnlyPlaylistThatCanNeitherBeEditedNorDeleted() {
        assertEquals("lp:p.81BBBB", Mapping.favouritesPlaylistId(playlists(own, favourites)))
        // An editorial playlist added to the library is not editable either, but can be removed.
        assertEquals("lp:p.81BBBB", Mapping.favouritesPlaylistId(playlists(editorialInLibrary, own, favourites)))
        assertEquals("lp:p.81BBBB", Mapping.favouritesPlaylistId(playlists(favourites, editorialInLibrary)))
    }

    @Test
    fun favouritesIsNotGuessed() {
        assertNull(Mapping.favouritesPlaylistId(playlists(own, editorialInLibrary)))
        // Two playlists match the marker: which one holds the favourites is unknown.
        val other = row("p.99DDDD", "Otra", canEdit = false, canDelete = false)
        assertNull(Mapping.favouritesPlaylistId(playlists(favourites, other)))
        // A response without canDelete cannot tell favourites from an added catalogue playlist.
        assertNull(Mapping.favouritesPlaylistId(playlists(row("p.81BBBB", "Canciones favoritas", canEdit = false, canDelete = null))))
        assertNull(Mapping.favouritesPlaylistId(JSONArray()))
    }

    // ---- logs: the pre-redaction external logs are removed, the native crash marker is kept ----

    @Test
    fun legacyExternalLogsArePurgedButTheCrashMarkerStays() {
        val dir = tmp.newFolder("logs")
        for (name in listOf("cadence.log", "older.log", ExternalLogs.NATIVE_CRASH, "other.txt")) File(dir, name).writeText("x")
        assertEquals(listOf("cadence.log", "older.log"), ExternalLogs.purgeLegacy(dir))
        assertEquals(setOf(ExternalLogs.NATIVE_CRASH, "other.txt"), dir.list()!!.toSet())
        assertEquals(emptyList<String>(), ExternalLogs.purgeLegacy(dir))
        assertNotEquals(0, File(dir, ExternalLogs.NATIVE_CRASH).length())
    }
}
