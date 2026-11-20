package app.cadence.backend.real

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class StationIdsTest {
    private fun json(s: String) = JSONObject(s)

    @Test fun aSongsOwnStationIsReadFromItsRelationship() {
        val song = json("""{"data":[{"id":"9","relationships":{"station":{"data":[{"id":"ra.9","type":"stations"}]}}}]}""")
        assertEquals(listOf("ra.9"), StationIds.stationIds(song))
        assertEquals(emptyList<String>(), StationIds.stationIds(json("""{"data":[{"id":"9","relationships":{}}]}""")))
        assertEquals(emptyList<String>(), StationIds.stationIds(null))
    }
}
