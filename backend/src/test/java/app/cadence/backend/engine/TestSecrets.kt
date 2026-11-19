package app.cadence.backend.engine

import org.json.JSONObject
import org.junit.Assume
import java.io.File

/**
 * Credentials for the live (Tier 2) tests. They are never part of the repository: the config comes
 * from $CADENCE_AMP_JSON or ~/.config/cadence/amp.json (a local build may still carry the asset).
 * Without one, the live tests are skipped instead of failing.
 */
object TestSecrets {
    fun ampFile(): File? = listOfNotNull(
        System.getenv("CADENCE_AMP_JSON"),
        System.getProperty("user.home")?.let { "$it/.config/cadence/amp.json" },
        "src/main/assets/engine/amp.json",
        "backend/src/main/assets/engine/amp.json",
    ).map(::File).firstOrNull { it.isFile }

    fun ampConfig(): AmpConfig {
        val f = ampFile()
        Assume.assumeTrue("no amp.json: set CADENCE_AMP_JSON or create ~/.config/cadence/amp.json", f != null)
        val o = JSONObject(f!!.readText())
        return AmpConfig(o.getString("devToken"), o.optString("mediaUserToken"), o.optString("storefront", "us"), o.optString("language", "en-US"))
    }
}
