package app.cadence.backend.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProvisioningTest {
    @Test fun snapshotNamesSizesAndMissingFilesNeverContents() {
        val dir = kotlin.io.path.createTempDirectory("prov").toFile()
        try {
            File(dir, "adi.pb").writeBytes(ByteArray(120))
            File(dir, "fsi.pdat").writeBytes(ByteArray(81))
            File(dir, "secret.retired").writeText("x")
            File(dir, "mpl_db").mkdirs(); File(dir, "mpl_db/a.sqlite").writeText("x")
            val s = Provisioning.snapshot(dir)
            assertTrue(s, s.contains("adi.pb=120B/") && s.contains("fsi.pdat=81B/") && s.contains("s-old"))
            assertTrue(s, s.contains("IC-Info.sids=missing") && s.contains("mpl_db=1files") && s.contains("retired=1"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun subscriptionShowsActiveFlagAndStorefrontOnly() {
        val body = """{"meta":{"subscription":{"active":false,"storefront":"mx","offerId":"abc","nested":{"x":1},"longText":"${"z".repeat(40)}"}}}"""
        val s = Provisioning.describeSubscription(body)
        assertTrue(s, s.contains("active=false") && s.contains("storefront=mx"))
        assertTrue(s, !s.contains("zzzz") && !s.contains("nested"))
        assertEquals("no meta.subscription in the response", Provisioning.describeSubscription("{}"))
        assertEquals("unreadable account response", Provisioning.describeSubscription("not json"))
    }

    @Test fun keyRejectedContextIsLoggedOncePerMinute() {
        Provisioning.dir = null
        Provisioning.resetThrottle()
        assertEquals(listOf(true, false, true), listOf(
            Provisioning.logOnKeyRejected(now = 10_000_000L),
            Provisioning.logOnKeyRejected(now = 10_030_000L),
            Provisioning.logOnKeyRejected(now = 10_070_000L),
        ))
    }
}
