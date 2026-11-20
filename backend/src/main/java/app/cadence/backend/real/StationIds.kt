package app.cadence.backend.real

import org.json.JSONObject

/** Reads station ids out of Apple's JSON:API answers. */
internal object StationIds {
    /** The station ids a resource points to through its `station` relationship (a song's or artist's own station). */
    fun stationIds(root: JSONObject?): List<String> {
        val rel = root?.optJSONArray("data")?.optJSONObject(0)?.optJSONObject("relationships")?.optJSONObject("station")?.optJSONArray("data") ?: return emptyList()
        return (0 until rel.length()).mapNotNull { rel.optJSONObject(it)?.optString("id")?.takeIf { id -> id.isNotBlank() } }
    }
}
