package app.cadence.backend.engine

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmpMappingAvailabilityTest {

    @Test
    fun trackAndAlbumAndPlaylistReflectPlayParamsAvailability() {
        val availableSong = JSONObject("""
            {
              "id": "1568453070",
              "attributes": {
                "name": "the rot",
                "artistName": "Dean Blunt",
                "durationInMillis": 140000,
                "playParams": { "id": "1568453070", "kind": "song" }
              }
            }
        """.trimIndent())
        val unavailableSong = JSONObject("""
            {
              "id": "999999",
              "attributes": {
                "name": "Region Locked Track",
                "artistName": "Artist",
                "durationInMillis": 180000
              }
            }
        """.trimIndent())

        assertTrue(Mapping.track(availableSong, "alb1")!!.available)
        assertFalse(Mapping.track(unavailableSong, "alb1")!!.available)

        val availableAlbum = JSONObject("""
            {
              "id": "alb1",
              "attributes": {
                "name": "BLACK METAL 2",
                "artistName": "Dean Blunt",
                "playParams": { "id": "alb1", "kind": "album" }
              }
            }
        """.trimIndent())
        val unavailableAlbum = JSONObject("""
            {
              "id": "alb2",
              "attributes": {
                "name": "Locked Album",
                "artistName": "Artist"
              }
            }
        """.trimIndent())

        assertTrue(Mapping.album(availableAlbum)!!.available)
        assertFalse(Mapping.album(unavailableAlbum)!!.available)

        val availablePlaylist = JSONObject("""
            {
              "id": "pl.1",
              "attributes": {
                "name": "Available Mix",
                "playParams": { "id": "pl.1", "kind": "playlist" }
              }
            }
        """.trimIndent())
        val unavailablePlaylist = JSONObject("""
            {
              "id": "pl.2",
              "attributes": {
                "name": "Unavailable Mix"
              }
            }
        """.trimIndent())

        assertTrue(Mapping.playlist(availablePlaylist)!!.available)
        assertFalse(Mapping.playlist(unavailablePlaylist)!!.available)
    }
}
