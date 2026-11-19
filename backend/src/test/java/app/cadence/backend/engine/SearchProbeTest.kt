package app.cadence.backend.engine

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import java.io.File

/**
 * Host-side shape probe for the amp-api search endpoints. Prints key names and counts only
 * (never tokens), so the parser is written against what Apple really returns.
 * Run: ./gradlew :backend:testDebugUnitTest --tests '*SearchProbeTest*' -i
 */
class SearchProbeTest {

    private val cfg: AmpConfig by lazy {
        TestSecrets.ampConfig()
    }

    private fun keys(o: JSONObject?) = o?.keys()?.asSequence()?.toList()?.sorted().toString()

    private fun describe(tag: String, item: JSONObject?) {
        if (item == null) { println("$tag <null>"); return }
        val attrs = item.optJSONObject("attributes")
        println("$tag type=${item.optString("type")} id=${item.optString("id")} name='${attrs?.optString("name")}'")
        println("$tag   attrs=${keys(attrs)}")
        println("$tag   rels=${keys(item.optJSONObject("relationships"))}")
        attrs?.optString("url")?.takeIf { it.isNotBlank() }?.let { println("$tag   url=$it") }
    }

    private fun arr(o: JSONObject?, k: String): JSONArray? = o?.optJSONObject(k)?.optJSONArray("data")

    @Test
    fun catalogSearchShape() = runBlocking {
        val engine = AmpEngine(cfg)
        val root = engine.get(
            "search?term=radiohead&types=albums,artists,songs,playlists&limit=5&with=topResults" +
                "&include%5Bsongs%5D=albums,artists&extend=extendedAssetUrls,artistUrl"
        )
        println("[search] root=${keys(root)} meta=${root?.optJSONObject("meta")}")
        val results = root?.optJSONObject("results")
        println("[search] results=${keys(results)}")
        for (k in listOf("top", "artists", "albums", "songs", "playlists")) {
            val data = arr(results, k)
            println("[search] $k count=${data?.length() ?: -1}")
            describe("[search] $k[0]", data?.optJSONObject(0))
        }
        val song = arr(results, "songs")?.optJSONObject(0)
        val rel = song?.optJSONObject("relationships")
        println("[search] song.albums=" + rel?.optJSONObject("albums")?.optJSONArray("data")?.optJSONObject(0)?.let {
            it.optString("id") + " attrs=" + (it.optJSONObject("attributes") != null)
        })
        println("[search] song.artists=" + rel?.optJSONObject("artists")?.optJSONArray("data")?.optJSONObject(0)?.optString("id"))
        val top = arr(results, "top")
        println("[search] top types=" + (0 until (top?.length() ?: 0)).map { top!!.optJSONObject(it).optString("type") })
    }

    @Test
    fun suggestionsShape() = runBlocking {
        val engine = AmpEngine(cfg)
        val root = engine.get(
            "search/suggestions?term=radioh&kinds=terms,topResults&types=albums,artists,songs,playlists" +
                "&limit%5Bresults%3Aterms%5D=6&limit%5Bresults%3AtopResults%5D=5"
        )
        println("[suggest] root=${keys(root)}")
        val sug = root?.optJSONObject("results")?.optJSONArray("suggestions")
        println("[suggest] count=${sug?.length() ?: -1}")
        for (i in 0 until (sug?.length() ?: 0)) {
            val s = sug!!.optJSONObject(i)
            val content = s.optJSONObject("content")
            println("[suggest] #$i kind=${s.optString("kind")} keys=${keys(s)} display='${s.optString("displayTerm")}' search='${s.optString("searchTerm")}' content=${content?.optString("type")}:${content?.optJSONObject("attributes")?.optString("name")}")
        }
    }

    @Test
    fun librarySearchShape() = runBlocking {
        val engine = AmpEngine(cfg)
        val root = engine.me("library/search?term=a&types=library-songs,library-albums,library-artists,library-playlists&limit=5")
        println("[library] root=${keys(root)}")
        val results = root?.optJSONObject("results")
        println("[library] results=${keys(results)}")
        for (k in listOf("library-songs", "library-albums", "library-artists", "library-playlists")) {
            val data = arr(results, k)
            println("[library] $k count=${data?.length() ?: -1}")
            describe("[library] $k[0]", data?.optJSONObject(0))
        }
    }
}
