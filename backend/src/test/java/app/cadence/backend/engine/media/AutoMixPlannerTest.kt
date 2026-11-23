package app.cadence.backend.engine.media

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoMixPlannerTest {

    @Test
    fun sameAlbumConsecutiveIsGapless() {
        val plan = AutoMixPlanner.plan(
            outAnalysis = analysis(tempo = 120.0),
            inAnalysis = analysis(tempo = 120.0),
            outDurationMs = 180_000L,
            inDurationMs = 180_000L,
            sameAlbumConsecutive = true,
            fallbackCrossfadeMs = 5_000L,
        )
        assertEquals(TransitionKind.GAPLESS, plan.kind)
        assertEquals(0L, plan.durationMs)
    }

    @Test
    fun missingAnalysisFallsBackToCrossfade() {
        val plan = AutoMixPlanner.plan(
            outAnalysis = null,
            inAnalysis = null,
            outDurationMs = 180_000L,
            inDurationMs = 180_000L,
            sameAlbumConsecutive = false,
            fallbackCrossfadeMs = 5_000L,
        )
        assertEquals(TransitionKind.CROSSFADE, plan.kind)
        assertEquals(5_000L, plan.durationMs)
        assertEquals(175_000L, plan.fadeOutStartMs)
        assertEquals(1f, plan.speedRatio, 0.0001f)
    }

    @Test
    fun hardCutSegueStaysGapless() {
        val outJson = analysis(
            tempo = 124.0,
            loudness = listOf(178.6 to -8.0, 179.5 to -7.5),
        )
        val inJson = analysis(
            tempo = 124.0,
            loudness = listOf(0.0 to -8.5, 0.8 to -7.0),
        )
        val plan = AutoMixPlanner.plan(
            outAnalysis = outJson,
            inAnalysis = inJson,
            outDurationMs = 180_000L,
            inDurationMs = 180_000L,
            sameAlbumConsecutive = false,
            fallbackCrossfadeMs = 5_000L,
        )
        assertEquals(TransitionKind.GAPLESS, plan.kind)
        assertEquals("hard-cut", plan.reason)
    }

    @Test
    fun beatMatchedWithinEightPercentAndSnappedToBars() {
        val outJson = analysis(
            tempo = 126.0,
            bars = listOf(172.0, 174.0, 176.0, 178.0),
            loudness = listOf(173.0 to -12.0, 174.2 to -20.0, 179.0 to -30.0),
        )
        val inJson = analysis(
            tempo = 120.0,
            bars = listOf(0.0, 2.0, 4.0, 6.0),
            loudness = listOf(0.0 to -32.0, 1.8 to -14.0),
            vocals = listOf(8.0),
        )
        val plan = AutoMixPlanner.plan(
            outAnalysis = outJson,
            inAnalysis = inJson,
            outDurationMs = 180_000L,
            inDurationMs = 180_000L,
            sameAlbumConsecutive = false,
            fallbackCrossfadeMs = 5_000L,
        )
        assertEquals(TransitionKind.BEAT_MATCHED, plan.kind)
        assertEquals(1.05f, plan.speedRatio, 0.001f)
        assertEquals(174_000L, plan.fadeOutStartMs)
        assertEquals(2_000L, plan.fadeInOffsetMs)
    }

    @Test
    fun halfTimeTempoMatches() {
        val outJson = analysis(
            tempo = 128.0,
            loudness = listOf(175.0 to -22.0),
        )
        val inJson = analysis(
            tempo = 64.0,
            loudness = listOf(0.0 to -25.0, 1.0 to -14.0),
        )
        val plan = AutoMixPlanner.plan(
            outAnalysis = outJson,
            inAnalysis = inJson,
            outDurationMs = 180_000L,
            inDurationMs = 180_000L,
            sameAlbumConsecutive = false,
            fallbackCrossfadeMs = 5_000L,
        )
        assertEquals(TransitionKind.BEAT_MATCHED, plan.kind)
        assertEquals(1.0f, plan.speedRatio, 0.001f)
    }

    @Test
    fun outOfRangeTempoFallsBackToEnergyCrossfade() {
        val outJson = analysis(
            tempo = 128.0,
            loudness = listOf(175.0 to -22.0),
        )
        val inJson = analysis(
            tempo = 95.0,
            loudness = listOf(0.0 to -25.0, 1.0 to -14.0),
        )
        val plan = AutoMixPlanner.plan(
            outAnalysis = outJson,
            inAnalysis = inJson,
            outDurationMs = 180_000L,
            inDurationMs = 180_000L,
            sameAlbumConsecutive = false,
            fallbackCrossfadeMs = 5_000L,
        )
        assertEquals(TransitionKind.CROSSFADE, plan.kind)
        assertEquals(1.0f, plan.speedRatio, 0.0001f)
    }

    @Test
    fun vocalGuardPreventsSkippingIntoVocals() {
        val outJson = analysis(
            tempo = 120.0,
            loudness = listOf(175.0 to -22.0),
        )
        val inJson = analysis(
            tempo = 120.0,
            bars = listOf(0.0, 4.0),
            loudness = listOf(0.0 to -30.0, 3.5 to -14.0),
            vocals = listOf(2.0),
        )
        val plan = AutoMixPlanner.plan(
            outAnalysis = outJson,
            inAnalysis = inJson,
            outDurationMs = 180_000L,
            inDurationMs = 180_000L,
            sameAlbumConsecutive = false,
            fallbackCrossfadeMs = 5_000L,
        )
        assertTrue(plan.fadeInOffsetMs <= 1_500L)
    }

    private fun analysis(
        tempo: Double,
        bars: List<Double> = emptyList(),
        loudness: List<Pair<Double, Double>> = emptyList(),
        vocals: List<Double> = emptyList(),
    ): JSONObject = JSONObject().apply {
        put("tempo", tempo)
        if (bars.isNotEmpty()) {
            put("bars", JSONArray().apply { bars.forEach { put(it) } })
        }
        if (loudness.isNotEmpty()) {
            put(
                "loudness",
                JSONArray().apply {
                    loudness.forEach { (t, v) ->
                        put(JSONObject().put("time", t).put("value", v))
                    }
                },
            )
        }
        if (vocals.isNotEmpty()) {
            put(
                "vocals",
                JSONArray().apply {
                    vocals.forEach { s -> put(JSONObject().put("start", s)) }
                },
            )
        }
    }
}
