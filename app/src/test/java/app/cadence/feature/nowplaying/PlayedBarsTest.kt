package app.cadence.feature.nowplaying

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

/** The blurred layer, redrawn per played bar, marks exactly the bars the exact playhead marks. */
class PlayedBarsTest {
    private fun played(c: Int, progress: Float, width: Float, step: Float) = c * step + step / 3f <= progress * width

    @Test
    fun theQuantisedPlayheadClassifiesEveryBarLikeTheExactOne() {
        val rnd = Random(7)
        repeat(20_000) {
            val width = 300f + rnd.nextFloat() * 900f
            val step = 6f + rnd.nextFloat() * 4f
            val p = rnd.nextFloat()
            val q = playedProgress(playedBarCount(p, width, step), width, step)
            val columns = maxOf(1, (width / step).toInt())
            for (c in 0 until columns) assertEquals("bar $c at p=$p w=$width s=$step", played(c, p, width, step), played(c, q, width, step))
        }
    }

    @Test
    fun edges() {
        assertEquals(0, playedBarCount(0f, 600f, 9f))
        assertEquals(66, playedBarCount(1f, 600f, 9f))
        assertEquals(0, playedBarCount(0.5f, 0f, 9f))
        assertEquals(0f, playedProgress(0, 600f, 9f))
    }
}
