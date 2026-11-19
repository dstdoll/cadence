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
}
