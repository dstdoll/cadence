package app.cadence.backend.engine.media

data class Session(
    val devToken: String,
    val mediaUserToken: String,
    val storefront: String,
    val storefrontCode: String,
)
