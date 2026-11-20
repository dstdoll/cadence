package app.cadence.backend.search

import app.cadence.backend.model.Album
import app.cadence.backend.model.Artist
import app.cadence.backend.model.Playlist
import app.cadence.backend.model.Track
import app.cadence.backend.model.TrackInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SearchEngineTest {
    private val nova = Artist("ar1", "Nova Corazón")
    private val tide = Artist("ar2", "Low Tide")
    private val alA = Album("al1", "Golden Rooms", "ar1", 2025, "Pop", listOf("#000"))
    private val alB = Album("al2", "Tide Pools", "ar2", 2024, "Indie", listOf("#000"))
    private fun t(id: String, title: String, ar: Artist, al: Album) = TrackInfo(Track(id, title, ar.id, al.id, 1, 1000, ""), al, ar)
    private val tracks = listOf(
        t("t1", "Golden Hour", nova, alA), t("t2", "Low Light", nova, alA),
        t("t3", "Salt", tide, alB), t("t4", "Undertow", tide, alB),
    )
    private val corpus = SearchCorpus(
        artists = listOf(nova, tide), albums = listOf(alA, alB), tracks = tracks,
        playlists = listOf(Playlist("p1", "Golden Mix", trackIds = listOf("t1"))),
        lyrics = mapOf("t3" to listOf("the salt in the golden air")),
        artistName = { id -> listOf(nova, tide).first { it.id == id }.name },
    )

    @Test fun scoringTiers() {
        assertEquals(100, SearchEngine.score("Salt", "salt"))
        assertEquals(80, SearchEngine.score("Golden Hour", "gol"))
        assertEquals(60, SearchEngine.score("Golden Hour", "hou"))
        assertEquals(40, SearchEngine.score("Undertow", "dert"))
        assertEquals(0, SearchEngine.score("Salt", "xyz"))
    }

    @Test fun accentInsensitive() {
        assertTrue(SearchEngine.score("Nova Corazón", "corazon") > 0)
    }

    @Test fun exactArtistOutranksSongContainingName() {
        val r = SearchEngine.search(corpus, "low tide")
        assertEquals(HitType.Artist, r.top.first().type)
    }

    @Test fun groupsByTypeAndFindsLyrics() {
        val r = SearchEngine.search(corpus, "golden")
        assertEquals(setOf(HitType.Album, HitType.Song, HitType.Playlist, HitType.Lyric), r.byType.keys)
        assertEquals("t3", (r.byType.getValue(HitType.Lyric).single() as SearchHit.LyricHit).track.id)
    }

    @Test fun shortQueriesSkipLyrics() {
        assertTrue(HitType.Lyric !in SearchEngine.search(corpus, "go").byType)
    }

    @Test fun completionsPreferPrefixAndShort() {
        val c = SearchEngine.completions(listOf("Golden Hour", "Golden Mix", "Golden Rooms", "Low Light", "Golden"), "gold")
        assertEquals("golden", c.first())
        assertTrue(c.all { it.startsWith("gold") })
        assertTrue(SearchEngine.completions(listOf("Golden"), "golden").isEmpty())
    }

    @Test fun recommenderRanksAndExcludes() {
        val picks = Recommender.similar(tracks[0], tracks, exclude = setOf("t2"), count = 3, random = Random(1))
        assertTrue(picks.none { it.id == "t1" || it.id == "t2" })
        assertEquals(2, picks.size)
    }

    @Test fun recommenderPrefersSameArtist() {
        val picks = Recommender.similar(tracks[2], tracks, exclude = emptySet(), count = 1, random = Random(7))
        assertEquals("t4", picks.single().id)
    }

    @Test fun discoverySpreadsAcrossArtistsAndExcludes() {
        val picks = Recommender.discovery(tracks, exclude = emptySet(), count = 2, random = Random(3))
        assertEquals(2, picks.size)
        assertEquals(2, picks.map { it.artist.id }.distinct().size) // one per artist, not two of one
        val onlyTide = Recommender.discovery(tracks, exclude = setOf("t1", "t2"), count = 4, random = Random(3))
        assertTrue(onlyTide.all { it.artist.id == "ar2" })
    }

    @Test fun heavyRotationLeadsWithUserSongsThenFills() {
        val picks = Recommender.heavyRotation(tracks, seedIds = setOf("t1"), exclude = emptySet(), count = 2, random = Random(5))
        assertEquals("t1", picks.first().id)      // the user's own song up front
        assertEquals(2, picks.size)
        assertTrue(picks.none { picks.count { p -> p.id == it.id } > 1 }) // no duplicates
    }

    @Test fun heavyRotationFallsBackToDiscoveryWithoutHistory() {
        val picks = Recommender.heavyRotation(tracks, seedIds = emptySet(), exclude = emptySet(), count = 2, random = Random(5))
        assertEquals(2, picks.size)
    }

    @Test fun aCorpusThatRepeatsAnEntryNeverYieldsTwoRowsWithOneKey() {
        val repeated = corpus.copy(artists = listOf(nova, nova, tide), tracks = tracks + tracks.first())
        val res = SearchEngine.search(repeated, "golden")
        for ((type, hits) in res.byType) assertEquals("duplicate keys in $type: ${hits.map { it.key }}", hits.map { it.key }.distinct(), hits.map { it.key })
        assertEquals(res.top.map { it.key }.distinct(), res.top.map { it.key })
        assertEquals(1, SearchEngine.search(repeated, "nova").byType.getValue(HitType.Artist).size)
    }

    @Test fun uniqueByKeyKeepsTheFirstOfEachAndTheOrder() {
        val a1 = SearchHit.ArtistHit(nova, 90)
        val a2 = SearchHit.ArtistHit(nova.copy(name = "Nova Again"), 80)
        val b = SearchHit.ArtistHit(tide, 70)
        assertEquals(listOf(a1, b), listOf(a1, a2, b).uniqueByKey())
        assertEquals(emptyList<SearchHit>(), emptyList<SearchHit>().uniqueByKey())
    }
}
