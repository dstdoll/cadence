package app.cadence.backend.real

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import app.cadence.backend.model.Album
import app.cadence.backend.model.Artist
import app.cadence.backend.model.Track
import app.cadence.backend.model.TrackInfo

/**
 * Persistence contract for account favourites and their minimal [TrackInfo] snapshots so cold
 * boots, offline toggles, and searches keep the Favourites list consistent.
 */
interface FavoritesStore {
    fun loadAll(): Pair<Set<String>, List<TrackInfo>>
    fun upsertLocal(track: TrackInfo?, trackId: String, favorite: Boolean)
    fun markSynced(trackId: String, favorite: Boolean)
    fun pendingOps(): List<Pair<String, Boolean>>
    fun reconcileRemote(remoteTracks: List<TrackInfo>)
    fun clear()
}

/**
 * SQLite-backed [FavoritesStore] (`favorites.db`) using WAL mode, `WITHOUT ROWID`, and a covering
 * index on `(sync_state, added_at DESC)`.
 */
class FavoritesDb(context: Context) :
    SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION),
    FavoritesStore {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.enableWriteAheadLogging()
        db.rawQuery("PRAGMA synchronous = NORMAL", null).use { it.moveToFirst() }
        db.rawQuery("PRAGMA temp_store = MEMORY", null).use { it.moveToFirst() }
        db.rawQuery("PRAGMA cache_size = -1024", null).use { it.moveToFirst() }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE (
                track_id    TEXT PRIMARY KEY,
                added_at    INTEGER NOT NULL,
                sync_state  INTEGER NOT NULL,
                title       TEXT NOT NULL,
                artist      TEXT NOT NULL,
                album       TEXT NOT NULL,
                album_id    TEXT NOT NULL,
                artist_id   TEXT NOT NULL,
                duration_ms INTEGER NOT NULL,
                cover_url   TEXT NOT NULL
            ) WITHOUT ROWID
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX idx_favorites_state_added ON $TABLE(sync_state, added_at DESC)",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE")
        onCreate(db)
    }

    @Synchronized
    override fun loadAll(): Pair<Set<String>, List<TrackInfo>> {
        val ids = LinkedHashSet<String>()
        val tracks = ArrayList<TrackInfo>()
        readableDatabase.rawQuery(
            """
            SELECT track_id, title, artist, album, album_id, artist_id, duration_ms, cover_url
            FROM $TABLE
            WHERE sync_state != $STATE_PENDING_REMOVE
            ORDER BY added_at DESC
            """.trimIndent(),
            null,
        ).use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0)
                ids += id
                val title = c.getString(1)
                val artistName = c.getString(2)
                val albumTitle = c.getString(3)
                val albumId = c.getString(4)
                val artistId = c.getString(5)
                val durationMs = c.getLong(6)
                val coverUrl = c.getString(7)
                if (title.isNotEmpty() && albumId.isNotEmpty()) {
                    val artist = Artist(
                        id = artistId.ifEmpty { "ar:$artistName" },
                        name = artistName,
                        bio = "",
                        color = "#888888",
                    )
                    val album = Album(
                        id = albumId,
                        title = albumTitle,
                        artistId = artist.id,
                        year = 0,
                        genre = "",
                        colors = listOf("#2B2B30", "#18181B"),
                        cover = coverUrl,
                    )
                    val track = Track(
                        id = id,
                        albumId = album.id,
                        artistId = artist.id,
                        title = title,
                        number = 1,
                        durationMs = durationMs,
                        audio = "",
                    )
                    tracks += TrackInfo(track, album, artist)
                }
            }
        }
        return ids to tracks
    }

    @Synchronized
    override fun upsertLocal(track: TrackInfo?, trackId: String, favorite: Boolean) {
        val db = writableDatabase
        val state = if (favorite) STATE_PENDING_ADD else STATE_PENDING_REMOVE
        val now = System.currentTimeMillis()
        val stmt = db.compileStatement(
            """
            INSERT INTO $TABLE(
                track_id, added_at, sync_state, title, artist, album, album_id, artist_id, duration_ms, cover_url
            ) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(track_id) DO UPDATE SET
                sync_state  = excluded.sync_state,
                added_at    = CASE WHEN excluded.sync_state = $STATE_PENDING_ADD THEN excluded.added_at ELSE $TABLE.added_at END,
                title       = CASE WHEN excluded.title != '' THEN excluded.title ELSE $TABLE.title END,
                artist      = CASE WHEN excluded.artist != '' THEN excluded.artist ELSE $TABLE.artist END,
                album       = CASE WHEN excluded.album != '' THEN excluded.album ELSE $TABLE.album END,
                album_id    = CASE WHEN excluded.album_id != '' THEN excluded.album_id ELSE $TABLE.album_id END,
                artist_id   = CASE WHEN excluded.artist_id != '' THEN excluded.artist_id ELSE $TABLE.artist_id END,
                duration_ms = CASE WHEN excluded.duration_ms > 0 THEN excluded.duration_ms ELSE $TABLE.duration_ms END,
                cover_url   = CASE WHEN excluded.cover_url != '' THEN excluded.cover_url ELSE $TABLE.cover_url END
            """.trimIndent(),
        )
        stmt.use {
            it.bindString(1, trackId)
            it.bindLong(2, now)
            it.bindLong(3, state.toLong())
            it.bindString(4, track?.track?.title.orEmpty())
            it.bindString(5, track?.artist?.name.orEmpty())
            it.bindString(6, track?.album?.title.orEmpty())
            it.bindString(7, track?.album?.id.orEmpty())
            it.bindString(8, track?.artist?.id.orEmpty())
            it.bindLong(9, track?.track?.durationMs ?: 0L)
            it.bindString(10, track?.album?.cover.orEmpty())
            it.executeInsert()
        }
    }

    @Synchronized
    override fun markSynced(trackId: String, favorite: Boolean) {
        val db = writableDatabase
        if (favorite) {
            db.compileStatement(
                "UPDATE $TABLE SET sync_state = $STATE_SYNCED WHERE track_id = ? AND sync_state = $STATE_PENDING_ADD",
            ).use {
                it.bindString(1, trackId)
                it.executeUpdateDelete()
            }
        } else {
            db.compileStatement(
                "DELETE FROM $TABLE WHERE track_id = ? AND sync_state = $STATE_PENDING_REMOVE",
            ).use {
                it.bindString(1, trackId)
                it.executeUpdateDelete()
            }
        }
    }

    @Synchronized
    override fun pendingOps(): List<Pair<String, Boolean>> {
        val out = ArrayList<Pair<String, Boolean>>()
        readableDatabase.rawQuery(
            "SELECT track_id, sync_state FROM $TABLE WHERE sync_state != $STATE_SYNCED ORDER BY added_at ASC",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                out += c.getString(0) to (c.getInt(1) == STATE_PENDING_ADD)
            }
        }
        return out
    }

    @Synchronized
    override fun reconcileRemote(remoteTracks: List<TrackInfo>) {
        val db = writableDatabase
        val remoteIds = HashSet<String>(remoteTracks.size * 2)
        for (t in remoteTracks) remoteIds += t.id

        db.beginTransactionNonExclusive()
        try {
            val existingSynced = ArrayList<String>()
            db.rawQuery("SELECT track_id FROM $TABLE WHERE sync_state = $STATE_SYNCED", null).use { c ->
                while (c.moveToNext()) existingSynced += c.getString(0)
            }

            val delStmt = db.compileStatement(
                "DELETE FROM $TABLE WHERE track_id = ? AND sync_state = $STATE_SYNCED",
            )
            delStmt.use { s ->
                for (id in existingSynced) {
                    if (id !in remoteIds) {
                        s.bindString(1, id)
                        s.executeUpdateDelete()
                    }
                }
            }

            val now = System.currentTimeMillis()
            val upsertStmt = db.compileStatement(
                """
                INSERT INTO $TABLE(
                    track_id, added_at, sync_state, title, artist, album, album_id, artist_id, duration_ms, cover_url
                ) VALUES(?, ?, $STATE_SYNCED, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(track_id) DO UPDATE SET
                    title       = CASE WHEN excluded.title != '' THEN excluded.title ELSE $TABLE.title END,
                    artist      = CASE WHEN excluded.artist != '' THEN excluded.artist ELSE $TABLE.artist END,
                    album       = CASE WHEN excluded.album != '' THEN excluded.album ELSE $TABLE.album END,
                    album_id    = CASE WHEN excluded.album_id != '' THEN excluded.album_id ELSE $TABLE.album_id END,
                    artist_id   = CASE WHEN excluded.artist_id != '' THEN excluded.artist_id ELSE $TABLE.artist_id END,
                    duration_ms = CASE WHEN excluded.duration_ms > 0 THEN excluded.duration_ms ELSE $TABLE.duration_ms END,
                    cover_url   = CASE WHEN excluded.cover_url != '' THEN excluded.cover_url ELSE $TABLE.cover_url END
                """.trimIndent(),
            )
            upsertStmt.use { s ->
                remoteTracks.forEachIndexed { idx, info ->
                    s.bindString(1, info.id)
                    // Preserve remote order when assigning initial timestamps.
                    s.bindLong(2, now - idx)
                    s.bindString(3, info.track.title)
                    s.bindString(4, info.artist.name)
                    s.bindString(5, info.album.title)
                    s.bindString(6, info.album.id)
                    s.bindString(7, info.artist.id)
                    s.bindLong(8, info.track.durationMs)
                    s.bindString(9, info.album.cover.orEmpty())
                    s.executeInsert()
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    override fun clear() {
        writableDatabase.execSQL("DELETE FROM $TABLE")
    }

    private companion object {
        const val DB_NAME = "favorites.db"
        const val DB_VERSION = 1
        const val TABLE = "favorites"
        const val STATE_SYNCED = 0
        const val STATE_PENDING_ADD = 1
        const val STATE_PENDING_REMOVE = 2
    }
}
