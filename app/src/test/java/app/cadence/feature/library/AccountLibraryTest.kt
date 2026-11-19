package app.cadence.feature.library

import app.cadence.backend.api.MusicRepository
import app.cadence.backend.model.AccountLibrary
import app.cadence.backend.model.Album
import app.cadence.backend.model.Artist
import app.cadence.backend.model.Lyrics
import app.cadence.backend.model.Playlist
import app.cadence.backend.model.Track
import app.cadence.backend.model.TrackInfo
import app.cadence.core.design.LibraryKind
import app.cadence.state.library.UserLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Library shows the Apple account's own library next to what was saved in the app. Before, it only
 * listed songs saved locally, so a real account (hundreds of songs on Apple Music) showed nothing.
 */
class AccountLibraryTest {
    private val ar1 = Artist("ar:A1", "A1")
    private val ar2 = Artist("ar:A2", "A2")
    private val ar3 = Artist("ar:A3", "A3")
    private val al1 = Album("100", "Al1", ar1.id, 2020, "Pop", listOf("#ffffff", "#000000"))
    private val al2 = Album("200", "Al2", ar2.id, 2021, "Rock", listOf("#000000", "#ffffff"))
    private val al3 = Album("300", "Al3", ar3.id, 2022, "Jazz", listOf("#ff0000", "#000000"))
    private fun tr(id: String, al: Album, ar: Artist) = TrackInfo(Track(id, id, ar.id, al.id, 1, 1000, "u"), al, ar)
    private val t1 = tr("1", al1, ar1)
    private val t2 = tr("2", al2, ar2)
    private val t3 = tr("3", al3, ar3)

    private fun repo(account: AccountLibrary) = object : MusicRepository {
        override val artists = listOf(ar1, ar2, ar3)
        override val albums = listOf(al1, al2, al3)
        override val tracks = listOf(t1, t2, t3)
        override val playlists = emptyList<Playlist>()
        override val accountLibrary = account
        override fun track(id: String) = tracks.firstOrNull { it.id == id }
        override fun album(id: String) = albums.firstOrNull { it.id == id }
        override fun artist(id: String) = artists.firstOrNull { it.id == id }
        override fun tracksOfAlbum(albumId: String) = tracks.filter { it.album.id == albumId }
        override fun albumsOfArtist(artistId: String) = albums.filter { it.artistId == artistId }
        override fun audioUri(track: TrackInfo) = track.track.audio
        override fun motionUri(path: String?) = path
        override suspend fun lyrics(track: TrackInfo) = Lyrics.None
    }

    @Test
    fun anEmptyLocalLibraryStillShowsTheAccountLibrary() {
        val repo = repo(AccountLibrary(trackIds = setOf("1", "2"), albumIds = setOf("300"), artistIds = setOf(ar3.id)))
        val lib = UserLibrary() // nothing saved in the app, as on a fresh install
        assertEquals(listOf("1", "2"), libraryTracks(repo, lib).map { it.id })
        assertEquals(setOf("100", "200", "300"), libraryAlbums(repo, lib).map { it.id }.toSet())
        assertEquals(setOf(ar1.id, ar2.id, ar3.id), libraryArtistIds(repo, lib))
        assertEquals(2, libraryCount(LibraryKind.Songs, repo, lib))
        assertEquals(3, libraryCount(LibraryKind.Albums, repo, lib))
        assertEquals(3, libraryCount(LibraryKind.Artists, repo, lib))
    }

    @Test
    fun localAndAccountEntriesAreMergedWithoutDuplicates() {
        val repo = repo(AccountLibrary(trackIds = setOf("1", "2")))
        val lib = UserLibrary().addToLibrary(listOf("2", "3"))
        assertEquals(listOf("1", "2", "3"), libraryTracks(repo, lib).map { it.id })
        assertEquals(3, libraryCount(LibraryKind.Songs, repo, lib))
    }

    @Test
    fun removingAnAccountSongHidesItAndAddingItBackShowsIt() {
        val account = setOf("1", "2")
        val repo = repo(AccountLibrary(trackIds = account))
        var lib = UserLibrary()
        assertTrue(lib.inLibrary("1", account))

        lib = lib.removeFromLibrary("1", account)
        assertFalse("Remove from Library left the song listed", lib.inLibrary("1", account))
        assertEquals(listOf("2"), libraryTracks(repo, lib).map { it.id })

        lib = lib.addToLibrary(listOf("1"))
        assertTrue(lib.inLibrary("1", account))
        assertTrue(lib.hiddenFromLibrary.isEmpty())
    }

    @Test
    fun aLocalSongIsNotRecordedAsHiddenWhenRemoved() {
        val lib = UserLibrary().addToLibrary(listOf("3")).removeFromLibrary("3", account = setOf("1"))
        assertFalse(lib.inLibrary("3"))
        assertTrue(lib.hiddenFromLibrary.isEmpty())
    }
}
