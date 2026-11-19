package app.cadence.backend.search

import app.cadence.backend.model.Album
import app.cadence.backend.model.Artist
import app.cadence.backend.model.Playlist
import app.cadence.backend.model.TrackInfo
import java.text.Normalizer

enum class HitType(val label: String) { Artist("Artist"), Album("Album"), Song("Song"), Playlist("Playlist"), Lyric("Lyrics") }

sealed interface SearchHit {
    val score: Int
    val type: HitType
    val key: String

    data class ArtistHit(val artist: Artist, override val score: Int) : SearchHit {
        override val type = HitType.Artist; override val key = "ar:${artist.id}"
    }
    data class AlbumHit(val album: Album, val artistName: String, override val score: Int) : SearchHit {
        override val type = HitType.Album; override val key = "al:${album.id}"
    }
    data class SongHit(val track: TrackInfo, override val score: Int) : SearchHit {
        override val type = HitType.Song; override val key = "t:${track.id}"
    }
    data class PlaylistHit(val playlist: Playlist, override val score: Int) : SearchHit {
        override val type = HitType.Playlist; override val key = "p:${playlist.id}"
    }
    data class LyricHit(val track: TrackInfo, val line: String, override val score: Int) : SearchHit {
        override val type = HitType.Lyric; override val key = "ly:${track.id}"
    }
}

/** Everything searchable for one scope (whole catalog, or just the user's library). */
data class SearchCorpus(
    val artists: List<Artist>,
    val albums: List<Album>,
    val tracks: List<TrackInfo>,
    val playlists: List<Playlist>,
    val lyrics: Map<String, List<String>> = emptyMap(),
    val artistName: (String) -> String = { "" },
)

data class SearchResults(val top: List<SearchHit>, val byType: Map<HitType, List<SearchHit>>) {
    val isEmpty get() = top.isEmpty()
}

object SearchEngine {
    private val marks = Regex("\\p{Mn}+")
    private val wordSplit = Regex("[\\s\\-&(),.]+")

    /** Case- and accent-insensitive form, so "corazon" finds "Corazón". */
    fun fold(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD).replace(marks, "").lowercase().trim()

    /** 100 exact, 80 prefix, 60 word-prefix, 40 substring, 0 no match. */
    fun score(text: String, query: String): Int {
        val t = fold(text)
        val q = fold(query)
        if (q.isEmpty()) return 0
        return when {
            t == q -> 100
            t.startsWith(q) -> 80
            t.split(wordSplit).any { it.startsWith(q) } -> 60
            t.contains(q) -> 40
            else -> 0
        }
    }

    fun search(c: SearchCorpus, query: String, lyricsMinLength: Int = 3): SearchResults {
        val q = fold(query)
        if (q.isEmpty()) return SearchResults(emptyList(), emptyMap())
        // A small type bias breaks ties the way people expect: an exact artist beats a song sharing the name.
        val artists = c.artists.mapNotNull { a -> score(a.name, query).takeIf { it > 0 }?.let { SearchHit.ArtistHit(a, it + 6) } }
        val albums = c.albums.mapNotNull { a ->
            val s = maxOf(score(a.title, query), score(c.artistName(a.artistId), query) - 25)
            s.takeIf { it > 0 }?.let { SearchHit.AlbumHit(a, c.artistName(a.artistId), it + 3) }
        }
        val songs = c.tracks.mapNotNull { t ->
            val s = maxOf(score(t.track.title, query), score(t.artist.name, query) - 30, score(t.album.title, query) - 30)
            s.takeIf { it > 0 }?.let { SearchHit.SongHit(t, it) }
        }
        val playlists = c.playlists.mapNotNull { p -> score(p.title, query).takeIf { it > 0 }?.let { SearchHit.PlaylistHit(p, it + 2) } }
        val lyrics = if (q.length < lyricsMinLength) emptyList() else c.tracks.mapNotNull { t ->
            c.lyrics[t.id]?.firstOrNull { fold(it).contains(q) }?.let { SearchHit.LyricHit(t, it, 20) }
        }
        val byType = mapOf(
            HitType.Artist to artists.sortedByDescending { it.score },
            HitType.Album to albums.sortedByDescending { it.score },
            HitType.Song to songs.sortedByDescending { it.score },
            HitType.Playlist to playlists.sortedByDescending { it.score },
            HitType.Lyric to lyrics,
        ).filterValues { it.isNotEmpty() }
        val top = (artists + albums + songs + playlists).sortedByDescending { it.score }.take(12) + lyrics.take(3)
        return SearchResults(top, byType)
    }

    /**
     * Query completions: whole titles/names whose start (or a word start) matches what was typed,
     * strongest match first, then shortest, deduplicated case-insensitively.
     */
    fun completions(candidates: Collection<String>, query: String, max: Int = 4): List<String> {
        val q = fold(query)
        if (q.isEmpty()) return emptyList()
        return candidates.asSequence()
            .map { it to score(it, query) }
            .filter { (s, sc) -> sc >= 60 && fold(s) != q }
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first.length })
            .map { it.first.lowercase() }
            .distinct()
            .take(max)
            .toList()
    }
}
