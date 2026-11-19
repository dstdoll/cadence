package app.cadence.backend.real

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A stored session survives a restart whether or not the account's media-user-token was obtained. */
class SessionStoreTest {
    @Test
    fun aSessionWithoutTheAccountTokenIsStillSignedIn() {
        val s = SessionStore.parse("""{"userId":"apple-us","email":"a@b.c","displayName":"A","accessToken":"","refreshToken":""}""")!!
        assertEquals("apple-us", s.userId)
        assertEquals("", s.accessToken)
    }

    @Test
    fun aSessionWithTheTokenKeepsIt() {
        val s = SessionStore.parse("""{"userId":"apple-cl","email":"a@b.c","displayName":"A","accessToken":"tok","refreshToken":"tok"}""")!!
        assertEquals("tok", s.accessToken)
        assertEquals("tok", s.refreshToken)
    }

    @Test
    fun nothingIdentifyingMeansNoSession() {
        assertNull(SessionStore.parse("""{"userId":"","accessToken":"tok"}"""))
        assertNull(SessionStore.parse("""{}"""))
    }
}
