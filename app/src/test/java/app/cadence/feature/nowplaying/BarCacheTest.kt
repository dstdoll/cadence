package app.cadence.feature.nowplaying

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/** The wave bar keeps its column heights and its gradient between frames while nothing changed. */
class BarCacheTest {
    @Test
    fun heightsAreRecomputedOnlyWhenTheWaveformChanges() {
        val cache = BarCache()
        val levels = IntArray(1000) { it % 100 }
        val first = cache.heights(levels, 1, 120)
        assertSame(first, cache.heights(levels, 1, 120))
        assertNotSame(first, cache.heights(levels, 2, 120)) // the hub repainted the array
        val second = cache.heights(levels, 2, 120)
        assertNotSame(second, cache.heights(levels, 2, 90)) // another width
        val narrow = cache.heights(levels, 2, 90)
        assertNotSame(narrow, cache.heights(levels.copyOf(), 2, 90)) // another array
    }

    @Test
    fun theGradientIsReusedWhileAccentAndPlayheadStay() {
        val cache = BarCache()
        val b = cache.playedBrush(Color.Red, 300f)
        assertSame(b, cache.playedBrush(Color.Red, 300f))
        // The playhead is quantised to four pixels: moving inside a step keeps the brush, which is
        // the point -- it moves every frame, and an exact key rebuilt the gradient every frame.
        assertSame(b, cache.playedBrush(Color.Red, 301f))
        assertSame(b, cache.playedBrush(Color.Red, 303.9f))
        val moved = cache.playedBrush(Color.Red, 304f)
        assertNotSame(b, moved)
        assertNotSame(moved, cache.playedBrush(Color.Blue, 304f))
    }

    @Test
    fun aBassHitTooSmallToSeeKeepsTheGradient() {
        val cache = BarCache()
        val accent = Color(0.50f, 0.25f, 0.75f)
        val b = cache.playedBrush(accent, 300f)
        // Under half a 1/32 step per channel: the same colour as far as the bar is concerned.
        assertSame(b, cache.playedBrush(Color(0.51f, 0.26f, 0.76f), 300f))
        // A whole step is a different colour and gets its own gradient.
        assertNotSame(b, cache.playedBrush(Color(0.50f + 1f / 32f, 0.25f, 0.75f), 300f))
    }
}
