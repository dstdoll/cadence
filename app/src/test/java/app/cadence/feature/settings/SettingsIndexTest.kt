package app.cadence.feature.settings

import app.cadence.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Every option in Settings can be found from the search box, and search finds what people type, in English and Spanish. */
class SettingsIndexTest {
    private fun file(path: String): String =
        listOf("src/main/$path", "app/src/main/$path").map(::File).first { it.isFile }.readText()

    private fun source(name: String) = file("java/app/cadence/feature/settings/$name")

    /** Resource id -> name, from the generated R class. */
    private val names: Map<Int, String> = R.string::class.java.fields.associate { it.getInt(null) to it.name }

    /** A resolver reading the strings of one resource folder ("values" or "values-es"). */
    private fun resolver(dir: String): (Int) -> String {
        val strings = Regex("""<string name="(\w+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(file("res/$dir/strings.xml"))
            .associate { m ->
                m.groupValues[1] to m.groupValues[2].replace("\\'", "'").replace("\\\"", "\"").replace("&lt;", "<").replace("&amp;", "&")
            }
        return { id -> strings.getValue(names.getValue(id)) }
    }

    private val english = resolver("values")
    private val spanish = resolver("values-es")

    /** Option titles in the panels, as resource names: rows, labels (with or without a value), folder and template rows. */
    private fun titlesInPanels(): Set<String> {
        val text = source("SettingsPanels.kt") + source("DownloadSettings.kt")
        val res = """stringResource\(R\.string\.(\w+)\)"""
        val patterns = listOf(
            Regex("""(?:SwitchRow|ChevronRow|FolderRow|TemplateRow)\(\s*$res"""),
            Regex("""Label\($res"""),
            Regex("""Label\("\$\{$res\}"""),
            Regex("""title = $res"""),
            Regex("""content = \{ Text\($res\) \}"""),
            Regex("""TitleInfo\($res"""),
        )
        return patterns.flatMap { p -> p.findAll(text).map { it.groupValues[1] } }.toSet()
    }

    @Test
    fun everyOptionInThePanelsIsInTheSearchIndex() {
        val indexed = SETTINGS_INDEX.map { names.getValue(it.title) }.toSet()
        val missing = titlesInPanels() - indexed
        assertTrue("options not findable from search: $missing", missing.isEmpty())
        assertTrue("the panels were not read", titlesInPanels().size > 40)
    }

    @Test
    fun searchIgnoresCaseAndAccentsAndUsesSynonyms() {
        fun first(q: String) = searchSettings(q, english).firstOrNull()?.let { english(it.title) }
        fun all(q: String) = searchSettings(q, english).map { english(it.title) }
        assertEquals("Bit-perfect USB (Android 14+)", first("bit perfect"))
        assertEquals("Bit-perfect USB (Android 14+)", first("DAC"))
        assertEquals("Theme", first("dark"))
        assertEquals("Pure black", first("oled"))
        assertEquals("Equalizer", first("eq"))
        assertEquals("Hi-res output", first("hí-res"))
        assertTrue(all("cache").containsAll(listOf("Streaming cache", "Clear streaming cache")))
        assertTrue(all("session").containsAll(listOf("Export session", "Import session")))
        assertTrue(searchSettings("", english).isEmpty())
        assertTrue(searchSettings("zzzz", english).isEmpty())
    }

    @Test
    fun searchWorksInSpanishWithTheSameTechnicalKeywords() {
        fun first(q: String) = searchSettings(q, spanish).firstOrNull()?.title
        assertEquals(R.string.set_equalizer, first("ecualizador"))
        assertEquals(R.string.set_pure_black, first("negro puro"))
        assertEquals(R.string.set_listening_history, first("historial"))
        assertEquals(R.string.set_bit_perfect, first("DAC")) // keywords stay English: technical words
        assertEquals(R.string.settings_language, first("idioma"))
    }

    @Test
    fun titleMatchesComeBeforeKeywordMatches() {
        val hits = searchSettings("lyrics", english).map { english(it.title) }
        assertTrue(hits.indexOf("Embed lyrics") < hits.indexOf("Text size").let { if (it < 0) Int.MAX_VALUE else it })
    }
}
