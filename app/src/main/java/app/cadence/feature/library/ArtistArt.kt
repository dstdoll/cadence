package app.cadence.feature.library

import app.cadence.backend.model.Album
import app.cadence.backend.model.Artist

/**
 * The image for an artist row: the artist's own photo, otherwise the cover of their newest album
 * that has one, so a real picture shows before the artist page (which fetches the photo) is opened.
 * Null draws the tinted placeholder.
 */
internal fun artistArt(artist: Artist, albums: List<Album>): String? =
    artist.cover ?: albums.filter { it.artistId == artist.id && it.cover != null }.maxByOrNull { it.year }?.cover
