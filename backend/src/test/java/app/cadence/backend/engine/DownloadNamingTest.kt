package app.cadence.backend.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadNamingTest {

    @Test
    fun stripEpSingleSuffix_removesTrailingSuffixCaseInsensitive() {
        assertEquals("Midnight", DownloadPreferences.stripEpSingleSuffix("Midnight - EP"))
        assertEquals("Across the Sea", DownloadPreferences.stripEpSingleSuffix("Across the Sea - Single"))
        assertEquals("Discovery", DownloadPreferences.stripEpSingleSuffix("Discovery"))
        assertEquals("Single Again", DownloadPreferences.stripEpSingleSuffix("Single Again"))
    }
}
