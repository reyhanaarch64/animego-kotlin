package app.animego.inc.core.database

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import app.animego.inc.core.data.DownloadRecord
import app.animego.inc.core.data.FavoriteRecord
import app.animego.inc.core.data.HistoryRecord
import app.animego.inc.core.firebase.ChatMessage
import app.animego.inc.core.firebase.LocalConversation
import org.json.JSONObject
import java.io.File

/** Local-first database. Favorites and history are partitioned by authenticated owner UID. */
class AnimeDatabase(context: Context) : SQLiteOpenHelper(context.applicationContext, NAME, null, VERSION) {
    companion object {
        const val NAME = "animego.db"
        const val VERSION = 8
    }

    override fun onCreate(db: SQLiteDatabase) {
        createBase(db)
        createDownloads(db)
        createSocialCache(db)
    }

    private fun createBase(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS favorites (owner_uid TEXT NOT NULL DEFAULT 'local', slug TEXT NOT NULL, title TEXT NOT NULL, cover TEXT, added_at INTEGER NOT NULL, PRIMARY KEY(owner_uid,slug))")
        db.execSQL("CREATE TABLE IF NOT EXISTS history (owner_uid TEXT NOT NULL DEFAULT 'local', slug TEXT NOT NULL, title TEXT NOT NULL, ep TEXT, cover TEXT, src TEXT, position REAL DEFAULT 0, duration REAL DEFAULT 0, watched_at INTEGER NOT NULL, PRIMARY KEY(owner_uid,slug))")
        db.execSQL("CREATE INDEX IF NOT EXISTS history_watched_at ON history(owner_uid,watched_at DESC)")
    }

    private fun createDownloads(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS downloads (id INTEGER PRIMARY KEY AUTOINCREMENT, anime_slug TEXT, anime_id INTEGER, title TEXT NOT NULL, ep TEXT, url TEXT NOT NULL, path TEXT NOT NULL UNIQUE, filename TEXT NOT NULL, status TEXT NOT NULL, progress INTEGER DEFAULT 0, total INTEGER DEFAULT 0, error TEXT, headers TEXT, added_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS downloads_updated ON downloads(updated_at DESC)")
    }

    private fun createSocialCache(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS conversations (owner_uid TEXT NOT NULL, id TEXT NOT NULL, other_uid TEXT NOT NULL, other_display_name TEXT NOT NULL DEFAULT '', other_username TEXT NOT NULL DEFAULT '', last_message TEXT NOT NULL DEFAULT '', updated_at INTEGER NOT NULL DEFAULT 0, unread_count INTEGER NOT NULL DEFAULT 0, read_sync_pending INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(owner_uid,id))")
        db.execSQL("CREATE INDEX IF NOT EXISTS conversations_updated ON conversations(owner_uid,updated_at DESC)")
        db.execSQL("CREATE TABLE IF NOT EXISTS messages (owner_uid TEXT NOT NULL, id TEXT NOT NULL, conversation_id TEXT NOT NULL, sender_id TEXT NOT NULL, type TEXT NOT NULL, text TEXT NOT NULL DEFAULT '', anime_slug TEXT NOT NULL DEFAULT '', anime_title TEXT NOT NULL DEFAULT '', cover_url TEXT NOT NULL DEFAULT '', episode_id TEXT NOT NULL DEFAULT '', episode_number TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL DEFAULT 0, delivery_state TEXT NOT NULL DEFAULT 'sent', PRIMARY KEY(owner_uid,id))")
        db.execSQL("CREATE INDEX IF NOT EXISTS messages_conversation ON messages(owner_uid,conversation_id,created_at DESC)")
        db.execSQL("CREATE TABLE IF NOT EXISTS avatar_cache (uid TEXT PRIMARY KEY, hash TEXT NOT NULL, mime_type TEXT NOT NULL, updated_at INTEGER NOT NULL, checked_at INTEGER NOT NULL, local_path TEXT NOT NULL)")
        createSyncState(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 1) createBase(db)
        if (oldVersion < 2) createDownloads(db)
        if (oldVersion < 3) {
            try { db.execSQL("ALTER TABLE downloads ADD COLUMN headers TEXT") } catch (_: Exception) { }
        }
        if (oldVersion < 4) createSocialCache(db)
        if (oldVersion < 5) createSyncStateLegacy(db)
        if (oldVersion < 6) {
            db.execSQL("DROP TABLE IF EXISTS favorite_tombstones")
            db.execSQL("DROP TABLE IF EXISTS history_tombstones")
            db.execSQL("DROP TABLE IF EXISTS history_clear_state")
            createSyncState(db)
        }
        if (oldVersion < 7 && !hasColumn(db, "conversations", "read_sync_pending")) {
            db.execSQL("ALTER TABLE conversations ADD COLUMN read_sync_pending INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 8) {
            db.beginTransaction()
            try {
                db.execSQL("ALTER TABLE favorites RENAME TO favorites_v7")
                db.execSQL("ALTER TABLE history RENAME TO history_v7")
                db.execSQL("CREATE TABLE favorites (owner_uid TEXT NOT NULL DEFAULT 'local', slug TEXT NOT NULL, title TEXT NOT NULL, cover TEXT, added_at INTEGER NOT NULL, PRIMARY KEY(owner_uid,slug))")
                db.execSQL("CREATE TABLE history (owner_uid TEXT NOT NULL DEFAULT 'local', slug TEXT NOT NULL, title TEXT NOT NULL, ep TEXT, cover TEXT, src TEXT, position REAL DEFAULT 0, duration REAL DEFAULT 0, watched_at INTEGER NOT NULL, PRIMARY KEY(owner_uid,slug))")
                db.execSQL("INSERT INTO favorites(owner_uid,slug,title,cover,added_at) SELECT 'legacy',slug,title,cover,added_at FROM favorites_v7")
                db.execSQL("INSERT INTO history(owner_uid,slug,title,ep,cover,src,position,duration,watched_at) SELECT 'legacy',slug,title,ep,cover,src,position,duration,watched_at FROM history_v7")
                db.execSQL("DROP TABLE favorites_v7")
                db.execSQL("DROP TABLE history_v7")
                db.execSQL("CREATE INDEX IF NOT EXISTS history_watched_at ON history(owner_uid,watched_at DESC)")
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
    }

    private fun hasColumn(db: SQLiteDatabase, table: String, column: String): Boolean =
        db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            var found = false
            while (cursor.moveToNext()) if (cursor.getString(nameIndex) == column) found = true
            found
        }

    private fun createSyncState(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS favorite_tombstones (owner_uid TEXT NOT NULL, slug TEXT NOT NULL, deleted_at INTEGER NOT NULL, PRIMARY KEY(owner_uid,slug))")
        db.execSQL("CREATE TABLE IF NOT EXISTS history_tombstones (owner_uid TEXT NOT NULL, slug TEXT NOT NULL, deleted_at INTEGER NOT NULL, PRIMARY KEY(owner_uid,slug))")
        db.execSQL("CREATE TABLE IF NOT EXISTS history_clear_state (owner_uid TEXT PRIMARY KEY, cleared_at INTEGER NOT NULL)")
    }

    private fun createSyncStateLegacy(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS favorite_tombstones (slug TEXT PRIMARY KEY, deleted_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS history_tombstones (slug TEXT PRIMARY KEY, deleted_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS history_clear_state (id INTEGER PRIMARY KEY CHECK(id=1), cleared_at INTEGER NOT NULL)")
    }

    @Synchronized fun saveFavorite(slug: String, title: String, cover: String, ownerUid: String = "local") {
        if (slug.isBlank() || ownerUid.isBlank()) return
        val values = ContentValues().apply { put("owner_uid", ownerUid); put("slug", slug); put("title", title); put("cover", cover); put("added_at", System.currentTimeMillis()) }
        writableDatabase.insertWithOnConflict("favorites", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized fun saveFavoriteAt(slug: String, title: String, cover: String, addedAt: Long, ownerUid: String = "local") {
        if (slug.isBlank() || ownerUid.isBlank()) return
        val values = ContentValues().apply { put("owner_uid", ownerUid); put("slug", slug); put("title", title); put("cover", cover); put("added_at", addedAt) }
        writableDatabase.insertWithOnConflict("favorites", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized fun markFavoriteRemoved(ownerUid: String, slug: String, deletedAt: Long = System.currentTimeMillis()) {
        if (ownerUid.isBlank() || slug.isBlank()) return
        val values = ContentValues().apply { put("owner_uid", ownerUid); put("slug", slug); put("deleted_at", deletedAt) }
        writableDatabase.insertWithOnConflict("favorite_tombstones", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized fun getFavoriteTombstones(ownerUid: String): Map<String, Long> = readableDatabase.query(
        "favorite_tombstones", null, "owner_uid=?", arrayOf(ownerUid), null, null, null
    ).use { cursor -> buildMap { while (cursor.moveToNext()) put(cursor.getString(cursor.getColumnIndexOrThrow("slug")), cursor.getLong(cursor.getColumnIndexOrThrow("deleted_at"))) } }

    @Synchronized fun removeFavorite(slug: String, ownerUid: String = "local") { writableDatabase.delete("favorites", "owner_uid=? AND slug=?", arrayOf(ownerUid, slug)) }

    @Synchronized fun isFavorite(slug: String, ownerUid: String = "local"): Boolean = readableDatabase.query(
        "favorites", arrayOf("slug"), "owner_uid=? AND slug=?", arrayOf(ownerUid, slug), null, null, null, "1"
    ).use { it.moveToFirst() }

    @Synchronized fun getFavorites(ownerUid: String = "local"): List<FavoriteRecord> = readableDatabase.query(
        "favorites", null, "owner_uid=?", arrayOf(ownerUid), null, null, "added_at DESC"
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toFavorite()) } }

    @Synchronized fun saveHistory(slug: String, title: String, ep: String, cover: String, source: String, position: Double, duration: Double, ownerUid: String = "local") {
        if (slug.isBlank() || ownerUid.isBlank()) return
        val values = historyValues(title, ep, cover, source, position, duration).apply { put("owner_uid", ownerUid); put("slug", slug); put("watched_at", System.currentTimeMillis()) }
        writableDatabase.insertWithOnConflict("history", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized fun saveHistoryAt(slug: String, title: String, ep: String, cover: String, source: String, position: Double, duration: Double, watchedAt: Long, ownerUid: String = "local") {
        if (slug.isBlank() || ownerUid.isBlank()) return
        val values = historyValues(title, ep, cover, source, position, duration).apply { put("owner_uid", ownerUid); put("slug", slug); put("watched_at", watchedAt) }
        writableDatabase.insertWithOnConflict("history", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized fun updateProgress(slug: String, title: String, ep: String, cover: String, source: String, position: Double, duration: Double, ownerUid: String = "local") {
        if (slug.isBlank() || ownerUid.isBlank()) return
        val values = historyValues(title, ep, cover, source, position, duration).apply { put("watched_at", System.currentTimeMillis()) }
        val count = writableDatabase.update("history", values, "owner_uid=? AND slug=?", arrayOf(ownerUid, slug))
        if (count == 0) saveHistory(slug, title, ep, cover, source, position, duration, ownerUid)
    }

    private fun historyValues(title: String, ep: String, cover: String, source: String, position: Double, duration: Double) = ContentValues().apply {
        put("title", title); put("ep", ep); put("cover", cover); put("src", source); put("position", position); put("duration", duration)
    }

    @Synchronized fun markHistoryRemoved(ownerUid: String, slug: String, deletedAt: Long = System.currentTimeMillis()) {
        if (ownerUid.isBlank() || slug.isBlank()) return
        val values = ContentValues().apply { put("owner_uid", ownerUid); put("slug", slug); put("deleted_at", deletedAt) }
        writableDatabase.insertWithOnConflict("history_tombstones", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized fun getHistoryTombstones(ownerUid: String): Map<String, Long> = readableDatabase.query(
        "history_tombstones", null, "owner_uid=?", arrayOf(ownerUid), null, null, null
    ).use { cursor -> buildMap { while (cursor.moveToNext()) put(cursor.getString(cursor.getColumnIndexOrThrow("slug")), cursor.getLong(cursor.getColumnIndexOrThrow("deleted_at"))) } }

    @Synchronized fun markHistoryCleared(ownerUid: String, clearedAt: Long = System.currentTimeMillis()) {
        if (ownerUid.isBlank()) return
        writableDatabase.insertWithOnConflict("history_clear_state", null, ContentValues().apply { put("owner_uid", ownerUid); put("cleared_at", clearedAt) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized fun getHistoryClearedAt(ownerUid: String): Long = readableDatabase.query(
        "history_clear_state", arrayOf("cleared_at"), "owner_uid=?", arrayOf(ownerUid), null, null, null, "1"
    ).use { if (it.moveToFirst()) it.getLong(0) else 0L }

    @Synchronized fun removeHistory(slug: String, ownerUid: String = "local") { writableDatabase.delete("history", "owner_uid=? AND slug=?", arrayOf(ownerUid, slug)) }
    @Synchronized fun clearHistory(ownerUid: String = "local") {
        writableDatabase.delete("history", "owner_uid=?", arrayOf(ownerUid))
        if (ownerUid.isNotBlank() && ownerUid != "local") markHistoryCleared(ownerUid, System.currentTimeMillis())
    }

    @Synchronized fun getHistory(ownerUid: String = "local"): List<HistoryRecord> = readableDatabase.query(
        "history", null, "owner_uid=?", arrayOf(ownerUid), null, null, "watched_at DESC", "40"
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


    @Synchronized fun upsertConversation(ownerUid: String, conversation: LocalConversation) {
        if (ownerUid.isBlank() || conversation.id.isBlank()) return
        val existing = readableDatabase.query(
            "conversations", arrayOf("last_message", "updated_at", "read_sync_pending"),
            "owner_uid=? AND id=?", arrayOf(ownerUid, conversation.id), null, null, null, "1"
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else Triple(cursor.getString(0), cursor.getLong(1), cursor.getInt(2) == 1)
        }
        val pendingRead = existing?.third == true
        val keepLocalPreview = existing != null && existing.second > conversation.updatedAt
        val values = ContentValues().apply {
            put("owner_uid", ownerUid); put("id", conversation.id); put("other_uid", conversation.otherUid)
            put("other_display_name", conversation.otherDisplayName); put("other_username", conversation.otherUsername)
            put("last_message", if (keepLocalPreview) existing!!.first else conversation.lastMessage)
            put("updated_at", maxOf(existing?.second ?: 0L, conversation.updatedAt))
            put("unread_count", if (pendingRead) 0 else conversation.unreadCount)
            put("read_sync_pending", if (pendingRead) 1 else 0)
        }
        writableDatabase.insertWithOnConflict("conversations", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized fun getConversations(ownerUid: String): List<LocalConversation> = readableDatabase.query(
        "conversations", null, "owner_uid=?", arrayOf(ownerUid), null, null, "updated_at DESC"
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(LocalConversation(
        id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
        otherUid = cursor.getString(cursor.getColumnIndexOrThrow("other_uid")),
        otherDisplayName = cursor.getString(cursor.getColumnIndexOrThrow("other_display_name")),
        otherUsername = cursor.getString(cursor.getColumnIndexOrThrow("other_username")),
        lastMessage = cursor.getString(cursor.getColumnIndexOrThrow("last_message")),
        updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at")),
        unreadCount = cursor.getInt(cursor.getColumnIndexOrThrow("unread_count"))
    )) } }

    /** Marks a conversation as read locally and queues the remote receipt for later delivery. */
    @Synchronized fun markConversationReadPending(ownerUid: String, conversationId: String, force: Boolean = false): Boolean {
        if (ownerUid.isBlank() || conversationId.isBlank()) return false
        val existing = readableDatabase.query(
            "conversations", arrayOf("unread_count", "read_sync_pending"), "owner_uid=? AND id=?",
            arrayOf(ownerUid, conversationId), null, null, null, "1"
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else cursor.getInt(0) to cursor.getInt(1)
        }
        val shouldQueue = force || existing == null || (existing.first > 0 && existing.second == 0)
        if (shouldQueue) {
            val values = ContentValues().apply {
                put("unread_count", 0); put("read_sync_pending", 1)
            }
            if (existing == null) {
                values.put("owner_uid", ownerUid); values.put("id", conversationId); values.put("other_uid", "")
                writableDatabase.insertWithOnConflict("conversations", null, values, SQLiteDatabase.CONFLICT_IGNORE)
            } else {
                writableDatabase.update("conversations", values, "owner_uid=? AND id=?", arrayOf(ownerUid, conversationId))
            }
        }
        return shouldQueue
    }

    @Synchronized fun getPendingConversationReads(ownerUid: String): List<String> = readableDatabase.query(
        "conversations", arrayOf("id"), "owner_uid=? AND read_sync_pending=1", arrayOf(ownerUid),
        null, null, "updated_at DESC"
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }

    @Synchronized fun markConversationReadSynced(ownerUid: String, conversationId: String) {
        writableDatabase.update("conversations", ContentValues().apply {
            put("read_sync_pending", 0)
            put("unread_count", 0)
        }, "owner_uid=? AND id=?", arrayOf(ownerUid, conversationId))
    }

    @Synchronized fun upsertMessages(ownerUid: String, conversationId: String, rows: List<ChatMessage>) {
        if (ownerUid.isBlank() || conversationId.isBlank() || rows.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            rows.forEach { row ->
                if (row.id.isBlank()) return@forEach
                val values = ContentValues().apply {
                    put("owner_uid", ownerUid); put("id", row.id); put("conversation_id", conversationId)
                    put("sender_id", row.senderId); put("type", row.type); put("text", row.text)
                    put("anime_slug", row.animeSlug); put("anime_title", row.animeTitle); put("cover_url", row.coverUrl)
                    put("episode_id", row.episodeId); put("episode_number", row.episodeNumber)
                    put("created_at", row.createdAt); put("delivery_state", "sent")
                }
                db.insertWithOnConflict("messages", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
            val latest = rows.maxByOrNull { it.createdAt }
            if (latest != null) {
                val preview = latest.text.ifBlank { latest.animeTitle.ifBlank { "shared anime" } }
                db.update("conversations", ContentValues().apply {
                    put("last_message", preview)
                    put("updated_at", latest.createdAt)
                }, "owner_uid=? AND id=?", arrayOf(ownerUid, conversationId))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    @Synchronized fun insertPendingMessage(ownerUid: String, conversationId: String, row: ChatMessage) {
        val values = ContentValues().apply {
            put("owner_uid", ownerUid); put("id", row.id); put("conversation_id", conversationId)
            put("sender_id", row.senderId); put("type", row.type); put("text", row.text)
            put("anime_slug", row.animeSlug); put("anime_title", row.animeTitle); put("cover_url", row.coverUrl)
            put("episode_id", row.episodeId); put("episode_number", row.episodeNumber)
            put("created_at", row.createdAt); put("delivery_state", "pending")
        }
        writableDatabase.insertWithOnConflict("messages", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        val db = writableDatabase
        val count = db.update("conversations", ContentValues().apply {
            put("last_message", row.text.ifBlank { row.animeTitle.ifBlank { "shared anime" } })
            put("updated_at", row.createdAt)
        }, "owner_uid=? AND id=?", arrayOf(ownerUid, conversationId))
        if (count == 0) upsertConversation(ownerUid, LocalConversation(
            id = conversationId, lastMessage = row.text.ifBlank { row.animeTitle }, updatedAt = row.createdAt
        ))
    }

    @Synchronized fun getMessages(ownerUid: String, conversationId: String, limit: Int = 100): List<ChatMessage> = readableDatabase.query(
        "messages", null, "owner_uid=? AND conversation_id=?", arrayOf(ownerUid, conversationId), null, null,
        "created_at DESC", limit.coerceIn(1, 500).toString()
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(ChatMessage(
        id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
        senderId = cursor.getString(cursor.getColumnIndexOrThrow("sender_id")),
        type = cursor.getString(cursor.getColumnIndexOrThrow("type")),
        text = cursor.getString(cursor.getColumnIndexOrThrow("text")),
        animeSlug = cursor.getString(cursor.getColumnIndexOrThrow("anime_slug")),
        animeTitle = cursor.getString(cursor.getColumnIndexOrThrow("anime_title")),
        coverUrl = cursor.getString(cursor.getColumnIndexOrThrow("cover_url")),
        episodeId = cursor.getString(cursor.getColumnIndexOrThrow("episode_id")),
        episodeNumber = cursor.getString(cursor.getColumnIndexOrThrow("episode_number")),
        createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
        deliveryState = cursor.getString(cursor.getColumnIndexOrThrow("delivery_state"))
    )) } }.sortedBy { it.createdAt }

    @Synchronized fun getPendingMessages(ownerUid: String): List<Pair<String, ChatMessage>> = readableDatabase.query(
        "messages", null, "owner_uid=? AND delivery_state IN ('pending','failed','sending')", arrayOf(ownerUid), null, null, "created_at ASC"
    ).use { cursor -> buildList { while (cursor.moveToNext()) {
        val id = cursor.getString(cursor.getColumnIndexOrThrow("id"))
        val conversationId = cursor.getString(cursor.getColumnIndexOrThrow("conversation_id"))
        add(conversationId to ChatMessage(
            id = id, senderId = cursor.getString(cursor.getColumnIndexOrThrow("sender_id")),
            type = cursor.getString(cursor.getColumnIndexOrThrow("type")), text = cursor.getString(cursor.getColumnIndexOrThrow("text")),
            animeSlug = cursor.getString(cursor.getColumnIndexOrThrow("anime_slug")), animeTitle = cursor.getString(cursor.getColumnIndexOrThrow("anime_title")),
            coverUrl = cursor.getString(cursor.getColumnIndexOrThrow("cover_url")), episodeId = cursor.getString(cursor.getColumnIndexOrThrow("episode_id")),
            episodeNumber = cursor.getString(cursor.getColumnIndexOrThrow("episode_number")), createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
            deliveryState = cursor.getString(cursor.getColumnIndexOrThrow("delivery_state"))
        ))
    } } }

    @Synchronized fun setMessageState(ownerUid: String, messageId: String, state: String) {
        writableDatabase.execSQL("UPDATE messages SET delivery_state=? WHERE owner_uid=? AND id=?", arrayOf(state, ownerUid, messageId))
    }

    @Synchronized fun putAvatarCache(uid: String, hash: String, mimeType: String, updatedAt: Long, checkedAt: Long, path: String) {
        val values = ContentValues().apply {
            put("uid", uid); put("hash", hash); put("mime_type", mimeType); put("updated_at", updatedAt)
            put("checked_at", checkedAt); put("local_path", path)
        }
        writableDatabase.insertWithOnConflict("avatar_cache", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    data class AvatarCacheRecord(val uid: String, val hash: String, val mimeType: String, val updatedAt: Long, val checkedAt: Long, val path: String)

    @Synchronized fun getAvatarCache(uid: String): AvatarCacheRecord? = readableDatabase.query(
        "avatar_cache", null, "uid=?", arrayOf(uid), null, null, null, "1"
    ).use { cursor -> if (!cursor.moveToFirst()) null else AvatarCacheRecord(
        cursor.getString(cursor.getColumnIndexOrThrow("uid")), cursor.getString(cursor.getColumnIndexOrThrow("hash")),
        cursor.getString(cursor.getColumnIndexOrThrow("mime_type")), cursor.getLong(cursor.getColumnIndexOrThrow("updated_at")),
        cursor.getLong(cursor.getColumnIndexOrThrow("checked_at")), cursor.getString(cursor.getColumnIndexOrThrow("local_path"))
    ) }

    @Synchronized fun clearAvatarCacheRows() { writableDatabase.delete("avatar_cache", null, null) }

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
