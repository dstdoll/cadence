package app.cadence.backend.api

import android.content.Context
import app.cadence.backend.engine.AmpConfig
import app.cadence.backend.engine.CadenceLog
import app.cadence.backend.engine.SecretFiles
import app.cadence.backend.engine.media.Http
import app.cadence.backend.real.RealBackend

/** Every service the frontend uses. The UI never sees an implementation class. */
interface BackendServices {
    val catalog: MusicRepository
    val search: SearchService
    val recommendations: RecommendationService
    val auth: AuthService
}

/** Builds the backend the whole app runs on: Apple Music's catalogue, search and sign-in. */
object Backend {
    fun create(context: Context): BackendServices {
        val ctx = context.applicationContext
        // Log from the first moment, also before anyone has signed in.
        CadenceLog.init(ctx)
        Http.rememberEdgesIn(java.io.File(ctx.filesDir, "net/edges.json"))
        SecretFiles.sealAll(ctx.filesDir)
        return RealBackend(ctx, AmpConfig.loadOrFetch(ctx))
    }
}
