package app.cadence.backend.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** The developer token is read from Apple's web player script; these checks use made-up tokens. */
class DevTokenTest {
    private fun b64(s: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray())

    private fun jwt(kid: String, exp: Long, iss: String = "ISSUER"): String =
        b64("""{"typ":"JWT","alg":"ES256","kid":"$kid"}""") + "." + b64("""{"iss":"$iss","exp":$exp}""") + "." + "A".repeat(40)

    @Test
    fun pagePointsAtThePlayersMainScript() {
        val html = """<script src="/includes/js-cdn/musickit/v3/x.js"></script>
            <script type="module" crossorigin src="/assets/index~AbC123.js"></script>"""
        assertEquals("/assets/index~AbC123.js", DevToken.scriptPath(html))
        assertNull(DevToken.scriptPath("<html>no scripts</html>"))
    }

    @Test
    fun picksTheWebPlayersTokenAmongTheOnesTheScriptEmbeds() {
        val other = jwt("OtherKid", 2_000_000_000)
        val web = jwt("WebPlayKid", 2_000_000_000, iss = "AMPWebPlay")
        val script = """const a="$other",b="$web",c="not-a-token";"""
        assertEquals(web, DevToken.webPlayerToken(script))
    }

    @Test
    fun noWebPlayerTokenMeansNull() {
        assertNull(DevToken.webPlayerToken("""const a="${jwt("OtherKid", 2_000_000_000)}";"""))
        assertNull(DevToken.webPlayerToken("nothing here"))
    }

    @Test
    fun aTokenIsRenewedAFullDayBeforeItExpires() {
        val now = 1_000_000L
        val day = 24 * 60 * 60L
        assertTrue(DevToken.isFresh(jwt("WebPlayKid", now + 2 * day), now))
        assertFalse(DevToken.isFresh(jwt("WebPlayKid", now + day), now))
        assertFalse(DevToken.isFresh(jwt("WebPlayKid", now - 1), now))
    }

    @Test
    fun anUnreadableTokenIsNeverFresh() {
        assertFalse(DevToken.isFresh("garbage", 0))
        assertFalse(DevToken.isFresh("a.b.c", 0))
    }
}
