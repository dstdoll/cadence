package app.cadence.feature.library

import app.cadence.backend.model.Album
import app.cadence.backend.model.Artist
import app.cadence.backend.model.Track
import app.cadence.backend.model.TrackInfo
import app.cadence.core.design.LibraryKind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Library sort keys and the reverse toggle. Verifies each key orders by the right field (case-
 * insensitively for text), that reversing is the exact inverse, and that albums only offer the keys
 * that apply to them.
 */
class LibrarySortTest {
    private fun info(title: String, artist: String, album: String, year: Int, durationMs: Long): TrackInfo {
        val ar = Artist(id = "ar-$artist", name = artist)
        val al = Album(id = "al-$album", title = album, artistId = ar.id, year = year, genre = "Pop", colors = listOf("#000000"))
        val tr = Track(id = "tr-$title", title = title, artistId = ar.id, albumId = al.id, number = 1, durationMs = durationMs, audio = "")
        return TrackInfo(tr, al, ar)
    }

    private val tracks = listOf(
        info("banana", "Zebra", "Milk", 2001, 300_000),
        info("Apple", "alpha", "Nectar", 1999, 120_000),
        info("cherry", "Mango", "Zest", 2010, 200_000),
    )

    private fun titlesSortedBy(sort: Sort, reversed: Boolean = false): List<String> {
        val cmp = trackComparator(sort).let { if (reversed) it.reversed() else it }
        return tracks.sortedWith(cmp).map { it.track.title }
    }

    @Test
    fun titleIsCaseInsensitiveAscending() {
        assertEquals(listOf("Apple", "banana", "cherry"), titlesSortedBy(Sort.Title))
    }

    @Test
    fun artistIsCaseInsensitive() {
        // alpha, Mango, Zebra — not Mango, Zebra, alpha (which a case-sensitive sort would give).
        assertEquals(listOf("Apple", "cherry", "banana"), titlesSortedBy(Sort.Artist))
    }

    @Test
    fun albumSortsByAlbumTitle() {
        // Milk, Nectar, Zest -> banana, Apple, cherry
        assertEquals(listOf("banana", "Apple", "cherry"), titlesSortedBy(Sort.Album))
    }

    @Test
    fun yearSortsAscendingByReleaseYear() {
        assertEquals(listOf("Apple", "banana", "cherry"), titlesSortedBy(Sort.Year)) // 1999, 2001, 2010
    }

    @Test
    fun durationSortsAscendingByLength() {
        assertEquals(listOf("Apple", "cherry", "banana"), titlesSortedBy(Sort.Duration)) // 120k, 200k, 300k
    }

    @Test
    fun reverseIsTheExactInverse() {
        for (s in Sort.entries) {
            assertEquals("$s reversed", titlesSortedBy(s).reversed(), titlesSortedBy(s, reversed = true))
        }
    }

    @Test
    fun albumsOfferOnlyApplicableKeys() {
        assertEquals(listOf(Sort.Title, Sort.Artist, Sort.Year), sortsFor(LibraryKind.Albums))
        // Song lists get the full set including Album and Duration.
        assertEquals(
            listOf(Sort.Title, Sort.Artist, Sort.Album, Sort.Year, Sort.Duration),
            sortsFor(LibraryKind.Songs),
        )
    }

    @Test
    fun artistTieBreaksByAlbumThenTrackNumberThenTitle() {
        val t1 = TrackInfo(
            Track("1", "Z", "ar1", "al-b", 2, 1000, ""),
            Album("al-b", "Beta", "ar1", 2020, "Pop", emptyList()),
            Artist("ar1", "Same"),
        )
        val t2 = TrackInfo(
            Track("2", "A", "ar1", "al-a", 3, 1000, ""),
            Album("al-a", "Alpha", "ar1", 2020, "Pop", emptyList()),
            Artist("ar1", "Same"),
        )
        val t3 = TrackInfo(
            Track("3", "B", "ar1", "al-a", 1, 1000, ""),
            Album("al-a", "Alpha", "ar1", 2020, "Pop", emptyList()),
            Artist("ar1", "Same"),
        )
        val sorted = listOf(t1, t2, t3).sortedWith(trackComparator(Sort.Artist)).map { it.id }
        assertEquals(listOf("3", "2", "1"), sorted)
    }

    @Test
    fun albumComparatorOrdersByArtistYearAndTitle() {
        val a1 = Album("1", "Zeta", "ar-b", 2020, "Pop", emptyList())
        val a2 = Album("2", "Beta", "ar-a", 2021, "Pop", emptyList())
        val a3 = Album("3", "Alpha", "ar-a", 2019, "Pop", emptyList())
        val artists = mapOf("ar-a" to "Alpha", "ar-b" to "Beta")
        val sorted = listOf(a1, a2, a3).sortedWith(albumComparator(Sort.Artist) { artists[it].orEmpty() }).map { it.id }
        assertEquals(listOf("3", "2", "1"), sorted)
    }
}
