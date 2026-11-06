package app.animego.inc.core.database

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import app.animego.inc.core.data.DownloadRecord
import app.animego.inc.core.data.FavoriteRecord
import app.animego.inc.core.data.HistoryRecord
import org.json.JSONObject
import java.io.File

/**
 * Kotlin rewrite of the old DatabaseHelper. The DB filename, version and SQL schema remain
 * byte-for-byte compatible in shape with the shipped SQLite database so existing local data
 * and downloaded-file paths are retained.
 */
class AnimeDatabase(context: Context) : SQLiteOpenHelper(context.applicationContext, NAME, null, VERSION) {
    companion object {
        const val NAME = "animego.db"
        const val VERSION = 3
    }

    override fun onCreate(db: SQLiteDatabase) {
        createBase(db)
        createDownloads(db)
    }

    private fun createBase(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS favorites (slug TEXT PRIMARY KEY, title TEXT NOT NULL, cover TEXT, added_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS history (slug TEXT PRIMARY KEY, title TEXT NOT NULL, ep TEXT, cover TEXT, src TEXT, position REAL DEFAULT 0, duration REAL DEFAULT 0, watched_at INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS history_watched_at ON history(watched_at DESC)")
    }

    private fun createDownloads(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS downloads (id INTEGER PRIMARY KEY AUTOINCREMENT, anime_slug TEXT, anime_id INTEGER, title TEXT NOT NULL, ep TEXT, url TEXT NOT NULL, path TEXT NOT NULL UNIQUE, filename TEXT NOT NULL, status TEXT NOT NULL, progress INTEGER DEFAULT 0, total INTEGER DEFAULT 0, error TEXT, headers TEXT, added_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS downloads_updated ON downloads(updated_at DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 1) createBase(db)
        if (oldVersion < 2) createDownloads(db)
        if (oldVersion < 3) {
            try { db.execSQL("ALTER TABLE downloads ADD COLUMN headers TEXT") } catch (_: Exception) { }
        }
    }

    @Synchronized fun saveFavorite(slug: String, title: String, cover: String) {
        if (slug.isBlank()) return
        val values = ContentValues().apply {
            put("slug", slug); put("title", title); put("cover", cover); put("added_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("favorites", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized fun saveFavoriteAt(slug: String, title: String, cover: String, addedAt: Long) {
        if (slug.isBlank()) return
        val values = ContentValues().apply {
            put("slug", slug); put("title", title); put("cover", cover); put("added_at", addedAt)
        }
        writableDatabase.insertWithOnConflict("favorites", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized fun removeFavorite(slug: String) {
        writableDatabase.delete("favorites", "slug=?", arrayOf(slug))
    }

    @Synchronized fun isFavorite(slug: String): Boolean = readableDatabase.query(
        "favorites", arrayOf("slug"), "slug=?", arrayOf(slug), null, null, null, "1"
    ).use { it.moveToFirst() }

    @Synchronized fun getFavorites(): List<FavoriteRecord> = readableDatabase.query(
        "favorites", null, null, null, null, null, "added_at DESC"
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toFavorite()) } }

    @Synchronized fun saveHistory(slug: String, title: String, ep: String, cover: String, source: String, position: Double, duration: Double) {
        if (slug.isBlank()) return
        val values = historyValues(title, ep, cover, source, position, duration)
        values.put("slug", slug)
        values.put("watched_at", System.currentTimeMillis())
        writableDatabase.insertWithOnConflict("history", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized fun saveHistoryAt(slug: String, title: String, ep: String, cover: String, source: String, position: Double, duration: Double, watchedAt: Long) {
        if (slug.isBlank()) return
        val values = historyValues(title, ep, cover, source, position, duration)
        values.put("slug", slug)
        values.put("watched_at", watchedAt)
        writableDatabase.insertWithOnConflict("history", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized fun updateProgress(slug: String, title: String, ep: String, cover: String, source: String, position: Double, duration: Double) {
        if (slug.isBlank()) return
        val values = historyValues(title, ep, cover, source, position, duration)
        values.put("watched_at", System.currentTimeMillis())
        val count = writableDatabase.update("history", values, "slug=?", arrayOf(slug))
        if (count == 0) saveHistory(slug, title, ep, cover, source, position, duration)
    }

    private fun historyValues(title: String, ep: String, cover: String, source: String, position: Double, duration: Double) = ContentValues().apply {
        put("title", title); put("ep", ep); put("cover", cover); put("src", source)
        put("position", position); put("duration", duration)
    }

    @Synchronized fun removeHistory(slug: String) { writableDatabase.delete("history", "slug=?", arrayOf(slug)) }
    @Synchronized fun clearHistory() { writableDatabase.delete("history", null, null) }

    @Synchronized fun getHistory(): List<HistoryRecord> = readableDatabase.query(
        "history", null, null, null, null, null, "watched_at DESC", "40"
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toHistory()) } }

    @Synchronized fun addDownload(
        slug: String, animeId: Int, title: String, ep: String, url: String,
        path: String, filename: String, headers: String
    ): Long {
        val now = System.currentTimeMillis()
        val existing = findDownloadByPath(path)
        if (existing != null) {
            val values = ContentValues().apply {
                put("anime_slug", slug); put("anime_id", animeId); put("title", title); put("ep", ep)
                put("url", url); put("filename", filename); put("headers", headers)
                put("status", "queued"); put("progress", 0); put("total", 0); put("error", ""); put("updated_at", now)
            }
            writableDatabase.update("downloads", values, "id=?", arrayOf(existing.id.toString()))
            return existing.id
        }
        val values = ContentValues().apply {
            put("anime_slug", slug); put("anime_id", animeId); put("title", title); put("ep", ep)
            put("url", url); put("path", path); put("filename", filename); put("status", "queued")
            put("progress", 0); put("total", 0); put("error", ""); put("headers", headers)
            put("added_at", now); put("updated_at", now)
        }
        return writableDatabase.insertWithOnConflict("downloads", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized fun findDownloadByPath(path: String): DownloadRecord? = readableDatabase.query(
        "downloads", null, "path=?", arrayOf(path), null, null, null, "1"
    ).use { if (it.moveToFirst()) it.toDownload() else null }

    @Synchronized fun getDownload(id: Long): DownloadRecord? = readableDatabase.query(
        "downloads", null, "id=?", arrayOf(id.toString()), null, null, null, "1"
    ).use { if (it.moveToFirst()) it.toDownload() else null }

    @Synchronized fun getDownloads(): List<DownloadRecord> = readableDatabase.query(
        "downloads", null, null, null, null, null, "updated_at DESC"
    ).use { cursor -> buildList {
        while (cursor.moveToNext()) {
            val row = cursor.toDownload()
            add(if (row.status == "completed" && !File(row.path).isFile) row.copy(status = "missing") else row)
        }
    } }

    @Synchronized fun getPendingDownloads(): List<DownloadRecord> = readableDatabase.query(
        "downloads", null, "status IN (?,?)", arrayOf("queued", "downloading"), null, null, "updated_at ASC"
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toDownload()) } }

    @Synchronized fun retryDownload(id: Long) { updateDownload(id, "queued", 0, 0, "") }

    @Synchronized fun updateDownloadRequest(id: Long, title: String, ep: String, url: String, headers: String) {
        val values = ContentValues().apply {
            put("title", title); put("ep", ep); put("url", url); put("headers", headers); put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.update("downloads", values, "id=?", arrayOf(id.toString()))
    }

    @Synchronized fun updateDownload(id: Long, status: String, progress: Long, total: Long, error: String) {
        val values = ContentValues().apply {
            put("status", status); put("progress", progress); put("total", total); put("error", error)
            put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.update("downloads", values, "id=?", arrayOf(id.toString()))
    }

    @Synchronized fun removeDownload(id: Long) { writableDatabase.delete("downloads", "id=?", arrayOf(id.toString())) }

    private fun Cursor.toFavorite() = FavoriteRecord(
        getString(getColumnIndexOrThrow("slug")), getString(getColumnIndexOrThrow("title")),
        getString(getColumnIndexOrThrow("cover")).orEmpty(), getLong(getColumnIndexOrThrow("added_at"))
    )

    private fun Cursor.toHistory() = HistoryRecord(
        getString(getColumnIndexOrThrow("slug")), getString(getColumnIndexOrThrow("title")),
        getString(getColumnIndexOrThrow("ep")).orEmpty(), getString(getColumnIndexOrThrow("cover")).orEmpty(),
        getString(getColumnIndexOrThrow("src")).orEmpty(), getDouble(getColumnIndexOrThrow("position")),
        getDouble(getColumnIndexOrThrow("duration")), getLong(getColumnIndexOrThrow("watched_at"))
    )

    private fun Cursor.toDownload(): DownloadRecord = DownloadRecord(
        id = getLong(getColumnIndexOrThrow("id")), slug = getString(getColumnIndexOrThrow("anime_slug")).orEmpty(),
        animeId = getInt(getColumnIndexOrThrow("anime_id")), title = getString(getColumnIndexOrThrow("title")),
        ep = getString(getColumnIndexOrThrow("ep")).orEmpty(), url = getString(getColumnIndexOrThrow("url")),
        path = getString(getColumnIndexOrThrow("path")), filename = getString(getColumnIndexOrThrow("filename")),
        status = getString(getColumnIndexOrThrow("status")), progress = getLong(getColumnIndexOrThrow("progress")),
        total = getLong(getColumnIndexOrThrow("total")), error = getString(getColumnIndexOrThrow("error")).orEmpty(),
        headers = if (getColumnIndex("headers") >= 0) getString(getColumnIndexOrThrow("headers")).orEmpty() else "{}",
        addedAt = getLong(getColumnIndexOrThrow("added_at")), updatedAt = getLong(getColumnIndexOrThrow("updated_at"))
    )
}
