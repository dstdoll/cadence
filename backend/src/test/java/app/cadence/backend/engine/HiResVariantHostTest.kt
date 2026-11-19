package app.cadence.backend.engine

import app.cadence.backend.engine.media.Track
import app.cadence.backend.engine.media.WrapperKeyClient
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Tier 2 (network + `amp.json`): quality selection against real hi-res masters, through the same
 * function downloads and streaming use. "Auto" must take the best variant a track offers.
 */
class HiResVariantHostTest {
    private val cfg: AmpConfig by lazy {
        TestSecrets.ampConfig()
    }

    private fun master(trackId: String): String = runBlocking {
        AmpEngine(cfg).song(trackId)!!.getJSONArray("data").getJSONObject(0).getJSONObject("attributes")
            .getJSONObject("extendedAssetUrls").getString("enhancedHls")
    }

    private fun pick(trackId: String, depth: Int, maxRate: Int, codec: String = "alac") = runBlocking {
        WrapperKeyClient.resolve(Track(trackId, "", "", 0, 1, 0L, master(trackId)), depth, maxRate, codec)
    }

    @Test
    fun autoTakesTheBestVariantAndCapsAreHonoured() {
        for (id in listOf(HOTEL_CALIFORNIA, SO_WHAT)) {
            val auto = pick(id, 24, 0)
            println("[hires] $id auto -> ${auto.codec} ${auto.bitDepth}bit ${auto.sampleRate}Hz")
            assertEquals("auto did not take the 192 kHz variant", 192_000, auto.sampleRate)
            assertEquals(24, auto.bitDepth)
            val capped = pick(id, 24, 96_000)
            println("[hires] $id cap 96k -> ${capped.sampleRate}Hz")
            assertEquals(48_000, capped.sampleRate) // no 96 kHz variant: the best under the cap
            assertEquals("aac", pick(id, 24, 0, "aac").codec)
        }
        // A track with a single lossless variant takes it under Auto.
        val single = pick(SPEAK_TO_ME, 24, 0)
        println("[hires] $SPEAK_TO_ME auto -> ${single.sampleRate}Hz")
        assertEquals(48_000, single.sampleRate)
    }

    @Test
    fun aHigherCapNeverUpsamples() {
        // The cap is a ceiling, not a target: with 192 kHz / 24-bit allowed, a 48 kHz master stays
        // at 48 kHz and a CD-quality one at 16-bit/44.1 kHz (Apple's own rendition, never padded
        // or resampled).
        for ((id, native) in listOf(SPEAK_TO_ME to (48_000 to 24), SHORT_44K to (44_100 to 16))) {
            val plan = pick(id, 24, 192_000)
            println("[hires] $id cap 192k -> ${plan.codec} ${plan.bitDepth}bit ${plan.sampleRate}Hz")
            assertEquals("alac", plan.codec)
            assertEquals(native.first, plan.sampleRate)
            assertEquals(native.second, plan.bitDepth)
        }
    }

    private companion object {
        const val HOTEL_CALIFORNIA = "635770202"
        const val SO_WHAT = "268443097"
        const val SPEAK_TO_ME = "1665303757"
        const val SHORT_44K = "1055140614"
    }
}
