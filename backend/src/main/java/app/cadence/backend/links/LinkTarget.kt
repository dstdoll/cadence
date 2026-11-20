package app.cadence.backend.links

/** Where a link leads in the app, once it has been looked up. */
sealed interface LinkTarget {
    enum class Kind { Album, Artist, Playlist }

    /** Open the [kind] page with app id [id]; [selectSongId] (an album page only) is the song to show selected, not played. */
    data class Open(val kind: Kind, val id: String, val selectSongId: String? = null) : LinkTarget

    /** Apple has nothing there, it is not available in this account's storefront, or the lookup failed (offline). */
    data object NotFound : LinkTarget

    /** A real Apple link of a kind Cadence cannot open yet. */
    data class Unsupported(val kind: String) : LinkTarget
}
