package app.cadence.feature.detail

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/** Top Songs columns peek on phones, stay bounded on tablets and fill the row when alone. */
class TopSongsColumnWidthTest {
    @Test
    fun onAPhoneTheNextColumnPeeks() = assertEquals(359.dp, topSongsColumnWidth(411.dp, 4))

    @Test
    fun onATabletColumnsAreCapped() = assertEquals(400.dp, topSongsColumnWidth(900.dp, 4))

    @Test
    fun aSingleColumnFillsTheRow() = assertEquals(379.dp, topSongsColumnWidth(411.dp, 1))

    @Test
    fun theLastColumnCanReachTheSameStartEdge() {
        // start padding + column + end padding = screen: the last column scrolls to x = 16 dp too.
        val screen = 411.dp
        val col = topSongsColumnWidth(screen, 4)
        assertEquals(screen, 16.dp + col + topSongsEndPadding(screen, col))
        assertEquals(16.dp, topSongsEndPadding(screen, topSongsColumnWidth(screen, 1)))
    }
}
