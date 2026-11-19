package app.cadence.state.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** AutoEQ's ParametricEQ.txt is read exactly; anything the equalizer cannot reproduce is refused. */
class AutoEqTest {
    private val dt770 = javaClass.getResource("/autoeq/dt770pro80.txt")!!.readText()

    @Test
    fun readsAnAutoEqProfile() {
        val p = AutoEq.parse(dt770, "Beyerdynamic DT 770 Pro (80 Ohm) ParametricEQ.txt")
        assertEquals("Beyerdynamic DT 770 Pro (80 Ohm)", p.name)
        assertEquals(-4.6f, p.preampDb, 1e-6f)
        assertEquals(10, p.filters.size)
        assertEquals(EqBandSetting(105, 8.0f, 0.70f, EqFilterType.LowShelf), p.filters[0])
        assertEquals(EqBandSetting(5956, -4.6f, 0.99f, EqFilterType.Peaking), p.filters[1])
        assertEquals(EqBandSetting(10000, -3.8f, 0.70f, EqFilterType.HighShelf), p.filters[5])
        assertEquals(EqBandSetting(62, -0.5f, 3.78f, EqFilterType.Peaking), p.filters[9])
    }

    @Test
    fun toleratesBomCrlfCommentsOffFiltersAndDecimalCommas() {
        val text = "\uFEFF# exported\r\nPreamp: -3,5 dB\r\nFilter 1: OFF PK Fc 100 Hz Gain 3.0 dB Q 1.00\r\n" +
            "Filter 2: ON PK Fc 1000,4 Hz Gain -2,25 dB Q 1,41\r\nFilter: ON HSC Fc 8000 Hz Gain 1.0 dB Q 0.70\r\n"
        val p = AutoEq.parse(text, null)
        assertEquals("AutoEQ", p.name)
        assertEquals(-3.5f, p.preampDb, 1e-6f)
        assertEquals(listOf(EqBandSetting(1000, -2.25f, 1.41f), EqBandSetting(8000, 1.0f, 0.70f, EqFilterType.HighShelf)), p.filters)
    }

    @Test
    fun refusesWhatTheEqualizerCannotReproduce() {
        fun reason(text: String) = assertThrows(AutoEqFormatException::class.java) { AutoEq.parse(text, "x.txt") }.reason
        assertEquals(AutoEqFormatException.Reason.NoFilters, reason("Preamp: -2 dB\n"))
        assertEquals(AutoEqFormatException.Reason.NoFilters, reason("GraphicEQ: 20 -1.2; 21 -1.3"))
        assertEquals(AutoEqFormatException.Reason.Unsupported, reason("Filter 1: ON LP Fc 20000 Hz"))
        assertEquals(AutoEqFormatException.Reason.Unsupported, reason("Filter 1: ON NO Fc 50 Hz Gain 0 dB Q 1"))
        assertEquals(AutoEqFormatException.Reason.OutOfRange, reason("Filter 1: ON PK Fc 5 Hz Gain 1 dB Q 1"))
        assertEquals(AutoEqFormatException.Reason.OutOfRange, reason("Filter 1: ON PK Fc 500 Hz Gain 40 dB Q 1"))
        assertEquals(AutoEqFormatException.Reason.OutOfRange, reason("Filter 1: ON PK Fc 500 Hz Gain 1 dB Q 50"))
        val many = (1..33).joinToString("\n") { "Filter $it: ON PK Fc ${100 * it} Hz Gain 1 dB Q 1" }
        assertEquals(AutoEqFormatException.Reason.TooMany, reason(many))
    }

    @Test
    fun aPositivePreampIsNeverUsedAsABoost() {
        assertEquals(0f, AutoEq.parse("Preamp: 3 dB\nFilter 1: ON PK Fc 100 Hz Gain -3 dB Q 1", null).preampDb, 0f)
    }

    @Test
    fun namesComeFromTheFileName() {
        assertEquals("Sennheiser HD 650", AutoEq.nameFrom("Sennheiser HD 650 ParametricEQ.txt"))
        assertEquals("my eq", AutoEq.nameFrom("/storage/Download/my eq.txt"))
        assertEquals("AutoEQ", AutoEq.nameFrom(" ParametricEQ.txt"))
    }
}
