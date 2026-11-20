package app.cadence.backend.real

import android.content.Context
import app.cadence.backend.api.AuthService
import app.cadence.backend.api.BackendServices
import app.cadence.backend.api.MusicRepository
import app.cadence.backend.api.RecommendationService
import app.cadence.backend.api.SearchService
import app.cadence.backend.engine.AmpConfig
import app.cadence.backend.engine.AmpEngine
import kotlinx.coroutines.launch


/**
 * Real catalogue, real search, cached recommendations.
 *
 * Auth is [EngineAuthService]: a non-null engine session built from the provisioned
 * MusicKit tokens, because the UI renders nothing while `session == null`. The real
 * OTP bridge is [RealAuthService].
 */
class RealBackend(context: Context, config: AmpConfig) : BackendServices {
    init {
        app.cadence.backend.engine.CadenceLog.init(context)
        app.cadence.backend.engine.CadenceLog.i(
            "backend",
            "RealBackend storefront=${config.storefront} devToken=${config.devToken.take(12)}... mediaUserToken=${if (config.mediaUserToken.isBlank()) "none" else "present"}",
        )
    }

    private val store = SessionStore(context)

    /** A signed-in account wins over the provisioned token: token + storefront come from it. */
    private val effective: AmpConfig = run {
        // adopt the provisioned token as the session so no sign-in screen is shown
        store.seedIfEmpty(config.mediaUserToken, config.storefront)
        // A rebuilt APK may carry a refreshed provisioned token. Update the
        // engine-seeded session in place; a real (imported) account is preserved.
        val seeded = store.load()
        if (seeded != null && seeded.userId == "engine" && seeded.accessToken != config.mediaUserToken) {
            store.save(
                seeded.copy(accessToken = config.mediaUserToken, refreshToken = config.mediaUserToken),
                config.storefront,
            )
        }
        val saved = store.load()
        if (saved == null) config
        else config.copy(mediaUserToken = saved.accessToken, storefront = store.storefront())
    }

    private val engine = AmpEngine.shared(effective)

    /**
     * A cached catalogue is only valid for the account/storefront that produced
     * it. Asset URLs expire and storefront ids differ, so drop it whenever the
     * fingerprint changes (including the first launch of this version, when the
     * marker does not exist yet).
     */
    private val catalogCache = java.io.File(context.filesDir, "catalog_cache.json")
    private val accountMarker = java.io.File(context.filesDir, "catalog_account.txt")
    private val favoritesDb = FavoritesDb(context)
    init {
        val fingerprint = "${effective.storefront}:${effective.mediaUserToken.hashCode()}"
        val previous = runCatching { accountMarker.readText() }.getOrNull()
        if (previous != fingerprint) {
            catalogCache.delete()
            runCatching { favoritesDb.clear() }
            runCatching { accountMarker.writeText(fingerprint) }
            app.cadence.backend.engine.CadenceLog.i(
                "cache",
                "account/storefront changed, catalogue cache cleared",
            )
        }
    }

    private val catalogImpl = RealCatalog(engine, catalogCache, favoritesDb)

    override val catalog: MusicRepository = this.catalogImpl
    override val search: SearchService = RealSearchService(this.catalogImpl)
    override val recommendations: RecommendationService = RealRecommendationService(this.catalogImpl)
    override val auth: AuthService = RealAuthService(context, store)

    init {
        // Streaming playback resolves a track from the master URL its catalogue row carries.
        app.cadence.backend.engine.stream.StreamPlayback.knownMasterUrl = { id -> catalogImpl.track(id)?.track?.audio }
        // Paint Home instantly from the last on-disk cache (the fingerprint check above already
        // dropped it if the account/storefront changed).
        kotlinx.coroutines.GlobalScope.launch {
            runCatching { catalogImpl.loadCache() }
                .onFailure { app.cadence.backend.engine.CadenceLog.e("browse", "cache restore failed", it) }
        }
        // Load Home/Browse/Radio/Library once signed in, applying the account's media-user-token
        // first, and reload whenever that token changes. Because the native mint runs *before* the
        // session is built, a fresh sign-in already carries the user token here — so the pages load
        // personalised with no generic flash. If the mint ever fails the token is blank and the pages
        // fall back to the dev-token catalogue (generic, but never empty), then upgrade when a token
        // arrives. A blank storefront re-resolves it from the account.
        kotlinx.coroutines.GlobalScope.launch {
            var loadedFor: String? = null
            // A session restored at launch that still has no account token (the request after sign-in can
            // fail) gets one more attempt in the background; a sign-in made in this run does not, so a
            // refusal is not repeated seconds later.
            var retryMissingToken = auth.session.value != null
            auth.session.collect { session ->
                if (session == null) {
                    if (loadedFor != null) {
                        loadedFor = null
                        catalogCache.delete()
                        catalogImpl.clearFavorites()
                    }
                    return@collect
                }
                val token = session.refreshToken.ifBlank { session.accessToken }
                engine.updateAccount(token, null)
                if (retryMissingToken) {
                    retryMissingToken = false
                    if (token.isBlank()) launch {
                        kotlinx.coroutines.delay(5_000)
                        auth.retryMediaToken()
                    }
                }
                if (token == loadedFor) return@collect
                if (loadedFor != null && loadedFor!!.isNotBlank() && token != loadedFor) {
                    catalogCache.delete()
                    catalogImpl.clearFavorites()
                }
                loadedFor = token
                runCatching {
                    catalogImpl.seedBrowse()
                    catalogImpl.loadPersonal()
                    catalogImpl.loadLibrary()
                    // The account library takes ~30 s on a large account: keep what is loaded so far.
                    catalogImpl.saveCache()
                    catalogImpl.loadAccountLibrary()
                    catalogImpl.saveCache()
                    app.cadence.backend.engine.CadenceLog.i(
                        "cache",
                        "catalogue loaded (token=${if (token.isBlank()) "dev-fallback" else "user"})",
                    )
                }.onFailure { app.cadence.backend.engine.CadenceLog.e("browse", "catalogue load failed", it) }
            }
        }
    }
}
