package app.cadence.backend.search

import app.cadence.backend.model.TrackInfo
import kotlin.random.Random

/** Picks "more like this" songs for stations and autoplay. Pure and deterministic given [random]. */
object Recommender {
    fun similarity(a: TrackInfo, b: TrackInfo): Int =
        (if (a.artist.id == b.artist.id) 3 else 0) +
            (if (a.album.genre == b.album.genre) 2 else 0) +
            (if (a.album.id == b.album.id) 1 else 0)

    /**
     * Ranks [all] by similarity to [seed], skipping [exclude] (already queued, "suggest less", the seed itself).
     * Equal scores are shuffled so stations don't always play in catalog order. Unrelated songs still
     * qualify (score 0), so autoplay never just stops while anything is left.
     */
    fun similar(seed: TrackInfo, all: List<TrackInfo>, exclude: Set<String>, count: Int, random: Random = Random): List<TrackInfo> =
        all.filter { it.id != seed.id && it.id !in exclude }
            .shuffled(random)
            .sortedByDescending { similarity(seed, it) }
            .take(count)

    /**
     * Exploration mix for a Discovery station: variety over familiarity. Spreads across artists (one
     * each first, so no single artist dominates) and is reshuffled every call, so the station is
     * different each time it is started.
     */
    fun discovery(all: List<TrackInfo>, exclude: Set<String>, count: Int, random: Random = Random): List<TrackInfo> {
        val pool = all.filter { it.id !in exclude }.shuffled(random)
        val picked = LinkedHashSet<TrackInfo>()
        val artists = HashSet<String>()
        for (t in pool) { if (picked.size >= count) break; if (artists.add(t.artist.id)) picked += t }
        for (t in pool) { if (picked.size >= count) break; picked += t }
        return picked.take(count)
    }

    /**
     * Personalized "heavy rotation" for a user station: the user's own songs ([seedIds] = favourites
     * + saved + recents) up front, then more like them. Falls back to [discovery] when the user has
     * no history yet. Reshuffled every call.
     */
    fun heavyRotation(all: List<TrackInfo>, seedIds: Set<String>, exclude: Set<String>, count: Int, random: Random = Random): List<TrackInfo> {
        val seeds = all.filter { it.id in seedIds && it.id !in exclude }.shuffled(random)
        if (seeds.isEmpty()) return discovery(all, exclude, count, random)
        val picked = LinkedHashSet<TrackInfo>().apply { addAll(seeds.take(count)) }
        var i = 0
        while (picked.size < count && i <= count * 4) {
            val more = similar(seeds[i % seeds.size], all, exclude + picked.map { it.id }.toSet(), 1, random).firstOrNull()
            if (more == null) break
            picked += more
            i++
        }
        return picked.take(count)
    }
}
