package app.cadence.feature.library

import app.cadence.backend.api.MusicRepository
import app.cadence.backend.model.Album
import app.cadence.backend.model.Artist
import app.cadence.backend.model.Lyrics
import app.cadence.backend.model.Playlist
import app.cadence.backend.model.Track
import app.cadence.backend.model.TrackInfo
import app.cadence.core.design.LibraryKind
import app.cadence.state.library.UserLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Library section counts. The headline rule - "an album is in your library because one of its songs
 * was favourited/added" - is a pure derivation over the saved set, so it is tested here against a
 * tiny fixed catalogue instead of on a device.
 */
class LibraryCountTest {
    private val ar1 = Artist("ar1", "A1")
    private val ar2 = Artist("ar2", "A2")
    private val al1 = Album("al1", "Al1", "ar1", 2020, "Pop", listOf("#ffffff"))
    private val al2 = Album("al2", "Al2", "ar2", 2021, "Rock", listOf("#000000"))
    private fun tr(id: String, al: Album, ar: Artist) =
        TrackInfo(Track(id, id, ar.id, al.id, 1, 1000, "u"), al, ar)
    private val t1 = tr("t1", al1, ar1)
    private val t2 = tr("t2", al1, ar1)
    private val t3 = tr("t3", al2, ar2)

    private val repo = object : MusicRepository {
        override val artists = listOf(ar1, ar2)
        override val albums = listOf(al1, al2)
        override val tracks = listOf(t1, t2, t3)
        override val playlists = emptyList<Playlist>()
        override fun track(id: String) = tracks.firstOrNull { it.id == id }
        override fun album(id: String) = albums.firstOrNull { it.id == id }
        override fun artist(id: String) = artists.firstOrNull { it.id == id }
        override fun tracksOfAlbum(albumId: String) = tracks.filter { it.album.id == albumId }
        override fun albumsOfArtist(artistId: String) = albums.filter { it.artistId == artistId }
        override fun audioUri(track: TrackInfo) = track.track.audio
        override fun motionUri(path: String?) = path
        override suspend fun lyrics(track: TrackInfo) = Lyrics.None
    }

    @Test fun favoriting_a_song_pulls_in_its_album_and_artist() {
        val lib = UserLibrary().toggleFavorite("t1", addToLibrary = true)
        assertEquals(1, libraryCount(LibraryKind.Albums, repo, lib)) // al1, implied by t1
        assertEquals(1, libraryCount(LibraryKind.Artists, repo, lib)) // ar1
        assertEquals(1, libraryCount(LibraryKind.Songs, repo, lib))
        assertEquals(1, libraryCount(LibraryKind.Favorites, repo, lib))
    }

    @Test fun two_songs_on_one_album_count_as_one_album() {
        val lib = UserLibrary(savedTracks = setOf("t1", "t2"))
        assertEquals(1, libraryCount(LibraryKind.Albums, repo, lib))
        assertEquals(setOf("al1"), libraryAlbums(repo, lib).map { it.id }.toSet())
        assertEquals(2, libraryCount(LibraryKind.Songs, repo, lib))
    }

    @Test fun saved_album_is_counted_even_without_a_saved_song() {
        val lib = UserLibrary(savedAlbums = setOf("al2"))
        assertEquals(setOf("al2"), libraryAlbums(repo, lib).map { it.id }.toSet())
    }

    @Test fun count_per_kind_feeds_the_label() {
        // The label's wording comes from plural resources (English and Spanish); the count is the logic.
        val lib = UserLibrary(savedTracks = setOf("t1", "t2", "t3"))
        assertEquals(2, libraryCount(LibraryKind.Albums, repo, lib))
        assertEquals(3, libraryCount(LibraryKind.Songs, repo, lib))
        assertEquals(2, libraryCount(LibraryKind.Artists, repo, lib))
    }

    @Test fun every_counted_word_has_plurals_in_both_languages() {
        val words = listOf("songs", "albums", "artists", "genres", "playlists", "hours", "minutes")
        for (dir in listOf("values", "values-es")) {
            val xml = java.io.File("src/main/res/$dir/strings.xml").readText()
            for (w in words) {
                val block = Regex("""<plurals name="$w">(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)
                assertTrue("$dir has plurals $w", block != null && "quantity=\"one\"" in block && "quantity=\"other\"" in block)
            }
        }
    }

    @Test fun genres_count_distinct_catalogue_genres() {
        assertEquals(2, libraryCount(LibraryKind.Genres, repo, UserLibrary())) // Pop, Rock
    }
}
