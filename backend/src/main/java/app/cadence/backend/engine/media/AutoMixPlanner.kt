package app.cadence.backend.engine.media

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

enum class TransitionKind {
    GAPLESS,
    CROSSFADE,
    BEAT_MATCHED,
}

data class TransitionPlan(
    val kind: TransitionKind,
    val fadeOutStartMs: Long,
    val durationMs: Long,
    val fadeInOffsetMs: Long,
    val speedRatio: Float,
    val reason: String,
)

/**
 * Computes beat-, bar-, and loudness-aware transitions between two consecutive tracks.
 * Pure Kotlin / org.json so it runs deterministically in both PlaybackService and JVM unit tests.
 */
object AutoMixPlanner {

    private const val MIN_BPM_RATIO = 0.92
    private const val MAX_BPM_RATIO = 1.08
    private const val LOUDNESS_THRESHOLD_DB = -18.0
    private const val HARD_CUT_DB = -12.0
    private const val MIN_CROSSFADE_MS = 1_000L
    private const val MAX_CROSSFADE_MS = 12_000L
    private const val VOCAL_GUARD_MS = 500L

    fun plan(
        outAnalysis: JSONObject?,
        inAnalysis: JSONObject?,
        outDurationMs: Long,
        inDurationMs: Long,
        sameAlbumConsecutive: Boolean,
        fallbackCrossfadeMs: Long,
    ): TransitionPlan {
        val safeOutMs = outDurationMs.coerceAtLeast(0L)
        val safeInMs = inDurationMs.coerceAtLeast(0L)
        if (sameAlbumConsecutive) {
            return TransitionPlan(
                kind = TransitionKind.GAPLESS,
                fadeOutStartMs = safeOutMs,
                durationMs = 0L,
                fadeInOffsetMs = 0L,
                speedRatio = 1f,
                reason = "same-album",
            )
        }

        val maxAllowedFade = minOf(MAX_CROSSFADE_MS, safeOutMs / 3L, safeInMs / 3L)
        if (maxAllowedFade < MIN_CROSSFADE_MS) {
            return TransitionPlan(
                kind = TransitionKind.GAPLESS,
                fadeOutStartMs = safeOutMs,
                durationMs = 0L,
                fadeInOffsetMs = 0L,
                speedRatio = 1f,
                reason = "track-too-short",
            )
        }

        if (outAnalysis == null || inAnalysis == null) {
            val dur = fallbackCrossfadeMs.coerceIn(0L, maxAllowedFade)
            return if (dur < MIN_CROSSFADE_MS) {
                TransitionPlan(TransitionKind.GAPLESS, safeOutMs, 0L, 0L, 1f, "no-analysis-gapless")
            } else {
                TransitionPlan(
                    kind = TransitionKind.CROSSFADE,
                    fadeOutStartMs = (safeOutMs - dur).coerceAtLeast(0L),
                    durationMs = dur,
                    fadeInOffsetMs = 0L,
                    speedRatio = 1f,
                    reason = "no-analysis",
                )
            }
        }

        if (isHardCut(outAnalysis, safeOutMs, inAnalysis)) {
            return TransitionPlan(
                kind = TransitionKind.GAPLESS,
                fadeOutStartMs = safeOutMs,
                durationMs = 0L,
                fadeInOffsetMs = 0L,
                speedRatio = 1f,
                reason = "hard-cut",
            )
        }

        val baseDuration = fallbackCrossfadeMs.coerceIn(MIN_CROSSFADE_MS, maxAllowedFade)
        val energyExitMs = findExitMs(outAnalysis, safeOutMs, baseDuration)
        var energyEntryMs = findEntryMs(inAnalysis, safeInMs)

        val firstVocalMs = firstVocalStartMs(inAnalysis)
        if (firstVocalMs != null) {
            val vocalLimit = (firstVocalMs - VOCAL_GUARD_MS).coerceAtLeast(0L)
            energyEntryMs = minOf(energyEntryMs, vocalLimit)
        }

        val outTempo = outAnalysis.optDouble("tempo", 0.0)
        val inTempo = inAnalysis.optDouble("tempo", 0.0)
        val matchedRatio = matchTempoRatio(outTempo, inTempo)

        if (matchedRatio != null) {
            val alignedExit = snapToGrid(outAnalysis, energyExitMs, toleranceMs = 2_000L)
            var alignedEntry = snapToGrid(inAnalysis, energyEntryMs, toleranceMs = 2_000L)
            if (firstVocalMs != null) {
                val vocalLimit = (firstVocalMs - VOCAL_GUARD_MS).coerceAtLeast(0L)
                if (alignedEntry > vocalLimit) alignedEntry = energyEntryMs
            }
            val duration = (safeOutMs - alignedExit).coerceIn(MIN_CROSSFADE_MS, maxAllowedFade)
            val fadeOutStart = (safeOutMs - duration).coerceAtLeast(0L)
            val maxEntry = (safeInMs - duration).coerceAtLeast(0L)
            return TransitionPlan(
                kind = TransitionKind.BEAT_MATCHED,
                fadeOutStartMs = fadeOutStart,
                durationMs = duration,
                fadeInOffsetMs = alignedEntry.coerceIn(0L, maxEntry),
                speedRatio = matchedRatio.toFloat(),
                reason = "beat-lock(out=${outTempo.toInt()},in=${inTempo.toInt()},ratio=${"%.3f".format(matchedRatio)})",
            )
        }

        val duration = (safeOutMs - energyExitMs).coerceIn(MIN_CROSSFADE_MS, maxAllowedFade)
        val fadeOutStart = (safeOutMs - duration).coerceAtLeast(0L)
        val maxEntry = (safeInMs - duration).coerceAtLeast(0L)
        return TransitionPlan(
            kind = TransitionKind.CROSSFADE,
            fadeOutStartMs = fadeOutStart,
            durationMs = duration,
            fadeInOffsetMs = energyEntryMs.coerceIn(0L, maxEntry),
            speedRatio = 1f,
            reason = "energy-crossfade",
        )
    }

    private fun isHardCut(outAnalysis: JSONObject, outDurationMs: Long, inAnalysis: JSONObject): Boolean {
        if (outAnalysis.optBoolean("hardCut") || inAnalysis.optBoolean("hardCut")) return true
        val outTailDb = tailLoudnessDb(outAnalysis, outDurationMs) ?: return false
        val inHeadDb = headLoudnessDb(inAnalysis) ?: return false
        return outTailDb > HARD_CUT_DB && inHeadDb > HARD_CUT_DB
    }

    private fun tailLoudnessDb(analysis: JSONObject, durationMs: Long): Double? {
        val arr = analysis.optJSONArray("loudness") ?: return null
        if (arr.length() == 0) return null
        val windowStartMs = (durationMs - 1_500L).coerceAtLeast(0L)
        var sum = 0.0
        var count = 0
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val tMs = (item.optDouble("time", -1.0) * 1000.0).toLong()
            if (tMs >= windowStartMs) {
                sum += item.optDouble("value", -60.0)
                count++
            }
        }
        return if (count > 0) sum / count else arr.optJSONObject(arr.length() - 1)?.optDouble("value")
    }

    private fun headLoudnessDb(analysis: JSONObject): Double? {
        val arr = analysis.optJSONArray("loudness") ?: return null
        if (arr.length() == 0) return null
        var sum = 0.0
        var count = 0
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val tMs = (item.optDouble("time", -1.0) * 1000.0).toLong()
            if (tMs in 0L..1_000L) {
                sum += item.optDouble("value", -60.0)
                count++
            }
        }
        return if (count > 0) sum / count else arr.optJSONObject(0)?.optDouble("value")
    }

    private fun findExitMs(analysis: JSONObject, durationMs: Long, fallbackDurationMs: Long): Long {
        val defaultExit = (durationMs - fallbackDurationMs).coerceAtLeast(0L)
        val arr = analysis.optJSONArray("loudness") ?: return defaultExit
        val searchStartMs = (durationMs - MAX_CROSSFADE_MS).coerceAtLeast(0L)
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val tMs = (item.optDouble("time", -1.0) * 1000.0).toLong()
            val db = item.optDouble("value", 0.0)
            if (tMs >= searchStartMs && db <= LOUDNESS_THRESHOLD_DB) {
                return tMs.coerceIn(searchStartMs, (durationMs - MIN_CROSSFADE_MS).coerceAtLeast(0L))
            }
        }
        return defaultExit
    }

    private fun findEntryMs(analysis: JSONObject, durationMs: Long): Long {
        val arr = analysis.optJSONArray("loudness") ?: return 0L
        val searchEndMs = minOf(8_000L, durationMs / 4L)
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val tMs = (item.optDouble("time", -1.0) * 1000.0).toLong()
            val db = item.optDouble("value", -60.0)
            if (tMs in 0L..searchEndMs && db > LOUDNESS_THRESHOLD_DB) {
                return tMs
            }
        }
        return 0L
    }

    private fun firstVocalStartMs(analysis: JSONObject): Long? {
        val vocals = analysis.optJSONArray("vocals") ?: return null
        if (vocals.length() == 0) return null
        val first = vocals.optJSONObject(0) ?: return null
        val sec = when {
            first.has("start") -> first.optDouble("start", -1.0)
            first.has("time") -> first.optDouble("time", -1.0)
            else -> -1.0
        }
        return if (sec >= 0.0) (sec * 1000.0).toLong() else null
    }

    private fun matchTempoRatio(outTempo: Double, inTempo: Double): Double? {
        if (outTempo <= 0.0 || inTempo <= 0.0) return null
        val candidates = doubleArrayOf(inTempo, inTempo * 2.0, inTempo * 0.5)
        var bestRatio: Double? = null
        var bestDist = Double.MAX_VALUE
        for (candidate in candidates) {
            val ratio = outTempo / candidate
            if (ratio in MIN_BPM_RATIO..MAX_BPM_RATIO) {
                val dist = abs(1.0 - ratio)
                if (dist < bestDist) {
                    bestDist = dist
                    bestRatio = ratio
                }
            }
        }
        return bestRatio
    }

    private fun snapToGrid(analysis: JSONObject, targetMs: Long, toleranceMs: Long): Long {
        val bars = analysis.optJSONArray("bars")
        val beats = analysis.optJSONArray("beats")
        return closestTimestampMs(bars, targetMs, toleranceMs)
            ?: closestTimestampMs(beats, targetMs, toleranceMs)
            ?: targetMs
    }

    private fun closestTimestampMs(array: JSONArray?, targetMs: Long, toleranceMs: Long): Long? {
        if (array == null || array.length() == 0) return null
        var best: Long? = null
        var bestDelta = Long.MAX_VALUE
        for (i in 0 until array.length()) {
            val sec = when (val item = array.opt(i)) {
                is Number -> item.toDouble()
                is JSONObject -> item.optDouble("time", item.optDouble("start", -1.0))
                else -> -1.0
            }
            if (sec < 0.0) continue
            val ms = (sec * 1000.0).toLong()
            val delta = abs(ms - targetMs)
            if (delta <= toleranceMs && delta < bestDelta) {
                bestDelta = delta
                best = ms
            }
        }
        return best
    }
}
