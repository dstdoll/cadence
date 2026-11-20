package app.cadence.backend.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtistIdsTest {
    private val names = listOf("Joey Yung", "容祖兒", "Studio#5", "Yung: The Album", "arx:123:Fake", "x:1:y", "#1 Fan", "", "a\nb", "ar:ar:Nested")

    @Test fun aBaseIdKeepsTodaysFormatAndNeverLooksQualified() {
        for (n in names) {
            val id = ArtistIds.base(n)
            assertEquals("ar:$n", id)
            assertFalse("base id of '$n' must not parse as qualified", ArtistIds.isQualified(id))
            assertNull(ArtistIds.catalogIdOf(id))
            assertEquals(n, ArtistIds.nameOf(id))
        }
    }

    @Test fun aQualifiedIdRoundTripsForAnyName() {
        for (n in names) {
            val id = ArtistIds.qualified(n, "1547065248")
            assertTrue("'$n'", ArtistIds.isQualified(id))
            assertEquals("1547065248", ArtistIds.catalogIdOf(id))
            assertEquals(n, ArtistIds.nameOf(id))
        }
    }

    @Test fun aNameThatLooksLikeAQualifiedIdIsStillJustAName() {
        val tricky = ArtistIds.base("x:123:Fake")          // "ar:x:123:Fake"
        assertFalse(ArtistIds.isQualified(tricky))
        assertEquals("x:123:Fake", ArtistIds.nameOf(tricky))
        val fakePrefix = ArtistIds.base("rx:123:Fake")     // "ar:rx:123:Fake", not "arx:"
        assertFalse(ArtistIds.isQualified(fakePrefix))
        assertEquals("rx:123:Fake", ArtistIds.nameOf(fakePrefix))
    }

    @Test fun twoArtistsWithOneNameGetDistinctIds() {
        val ids = setOf(ArtistIds.base("Joey Yung"), ArtistIds.qualified("Joey Yung", "1547065248"), ArtistIds.qualified("Joey Yung", "1274231511"))
        assertEquals(3, ids.size)
    }

    @Test fun anIdOfNeitherFormIsReturnedWithoutThePrefix() {
        assertEquals("whatever", ArtistIds.nameOf("whatever"))
        assertNull(ArtistIds.catalogIdOf("arx::missing-digits"))
        assertNull(ArtistIds.catalogIdOf("arx:12a:name"))
    }
}
