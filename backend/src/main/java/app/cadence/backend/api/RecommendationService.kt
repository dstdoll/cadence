package app.cadence.backend.api

import app.cadence.backend.model.TrackInfo

/**
 * "More like this", used by Create Station and Autoplay.
 *
 * BACKEND: GET /recommendations?seed={trackId}&exclude={ids}&count={n} -> List<trackId>
 * The server should honour `exclude` (already queued + songs the user marked "Suggest less").
 */
interface RecommendationService {
    suspend fun similar(seed: TrackInfo, exclude: Set<String>, count: Int): List<TrackInfo>

    /** An exploration mix for the Discovery station (variety over familiarity), fresh each call. */
    suspend fun discovery(exclude: Set<String>, count: Int): List<TrackInfo>

    /** A personalized "heavy rotation" mix for the user's own station; [seedIds] = favourites + saved + recents. */
    suspend fun heavyRotation(seedIds: Set<String>, exclude: Set<String>, count: Int): List<TrackInfo>
}
