package app.cadence.backend.model

/**
 * The ids the app gives artists. Apple identifies an artist by a numeric catalogue id, but the app
 * grew up identifying them by name (`ar:<name>`), and albums and songs still only carry
 * `artistName`. Two different artists can share a name, so there are two forms:
 *
 * * **base** `ar:<name>`: the artist that owns the name (first bound, see `RealCatalog.bindCatalogId`).
 *   Everything stored before this scheme (follows, favourites, caches) is in this form.
 * * **qualified** `arx:<catalogId>:<name>`: any further artist with the same name. It carries its
 *   catalogue id, so it needs no lookup to be opened. The fixed `arx:` prefix, the digits and the first
 *   `:` make it unambiguous even when the name itself contains `#`, `:` or anything else; a base id
 *   never starts with `arx:` (its third character is `:`).
 */
object ArtistIds {
    private const val BASE = "ar:"
    private val qualified = Regex("^arx:(\\d+):(.*)$", RegexOption.DOT_MATCHES_ALL)

    fun base(name: String): String = "$BASE$name"

    fun qualified(name: String, catalogId: String): String = "arx:$catalogId:$name"

    fun isQualified(displayId: String): Boolean = qualified.matches(displayId)

    /** The catalogue id a qualified id carries; null for a base id (its binding is looked up). */
    fun catalogIdOf(displayId: String): String? = qualified.find(displayId)?.groupValues?.get(1)

    /** The artist's name, for either form (and, defensively, for an id of neither). */
    fun nameOf(displayId: String): String =
        qualified.find(displayId)?.groupValues?.get(2) ?: displayId.removePrefix(BASE)
}
