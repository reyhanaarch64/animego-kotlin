package app.animego.inc.core.firebase

import android.content.Context
import app.animego.inc.core.database.AnimeDatabase
import com.google.firebase.database.ServerValue
import kotlinx.coroutines.tasks.await

/** Merge-only first-login sync: favorites are unioned; history uses watchedAt last-write-wins. */
class CloudSyncRepository(private val context: Context, private val firebase: FirebaseProvider, private val local: AnimeDatabase) {
    suspend fun syncNow() {
        val user = firebase.auth.currentUser ?: return
        if (user.isAnonymous) return
        val uid = user.uid
        val root = firebase.database.reference
        val cloudFavoritesRef = root.child("users").child(uid).child("favorites")
        val cloudHistoryRef = root.child("users").child(uid).child("history")
        val cloudFavorites = cloudFavoritesRef.get().await()
        val cloudFavoriteTombstones = root.child("users").child(uid).child("favoriteTombstones").get().await()
        val favoriteTombstones = local.getFavoriteTombstones(uid).toMutableMap()
        cloudFavoriteTombstones.children.forEach { row ->
            val key = row.key.orEmpty()
            val value = row.getValue(Long::class.java) ?: 0L
            if (key.isNotBlank() && value > (favoriteTombstones[key] ?: 0L)) favoriteTombstones[key] = value
        }
        local.getFavoriteTombstones(uid).forEach { (slug, deletedAt) ->
            val cloudTime = cloudFavoriteTombstones.child(slug).getValue(Long::class.java) ?: 0L
            if (deletedAt > cloudTime) root.child("users").child(uid).child("favoriteTombstones").child(slug).setValue(deletedAt).await()
        }
        val localFavorites = local.getFavorites(uid)
        val localSlugs = localFavorites.map { it.slug }.toSet()
        localFavorites.forEach { item ->
            val deletedAt = favoriteTombstones[item.slug] ?: 0L
            if (deletedAt >= item.addedAt && deletedAt > 0L) {
                local.removeFavorite(item.slug, uid)
                cloudFavoritesRef.child(item.slug).removeValue().await()
            } else if (!cloudFavorites.child(item.slug).exists()) {
                cloudFavoritesRef.child(item.slug).setValue(mapOf(
                    "slug" to item.slug, "title" to item.title, "cover" to item.cover,
                    "addedAt" to item.addedAt
                )).await()
            }
        }
        cloudFavorites.children.forEach { item ->
            val slug = item.child("slug").getValue(String::class.java) ?: item.key.orEmpty()
            val addedAt = item.child("addedAt").getValue(Long::class.java) ?: 0L
            val deletedAt = favoriteTombstones[slug] ?: 0L
            if (deletedAt > 0L && deletedAt >= addedAt) {
                cloudFavoritesRef.child(slug).removeValue().await()
            } else if (slug.isNotBlank() && slug !in localSlugs) {
                local.saveFavoriteAt(slug,
                    item.child("title").getValue(String::class.java).orEmpty(),
                    item.child("cover").getValue(String::class.java).orEmpty(), addedAt, uid)
            }
        }

        val cloudHistory = cloudHistoryRef.get().await()
        val cloudHistoryTombstones = root.child("users").child(uid).child("historyTombstones").get().await()
        val historyTombstones = local.getHistoryTombstones(uid).toMutableMap()
        cloudHistoryTombstones.children.forEach { row ->
            val key = row.key.orEmpty()
            val value = row.getValue(Long::class.java) ?: 0L
            if (key.isNotBlank() && value > (historyTombstones[key] ?: 0L)) historyTombstones[key] = value
        }
        local.getHistoryTombstones(uid).forEach { (slug, deletedAt) ->
            val cloudTime = cloudHistoryTombstones.child(slug).getValue(Long::class.java) ?: 0L
            if (deletedAt > cloudTime) root.child("users").child(uid).child("historyTombstones").child(slug).setValue(deletedAt).await()
        }
        val cloudHistoryClearedAt = root.child("users").child(uid).child("historyClearedAt").get().await().getValue(Long::class.java) ?: 0L
        val localHistoryClearedAt = local.getHistoryClearedAt(uid)
        if (localHistoryClearedAt > cloudHistoryClearedAt) root.child("users").child(uid).child("historyClearedAt").setValue(localHistoryClearedAt).await()
        val historyClearedAt = maxOf(cloudHistoryClearedAt, localHistoryClearedAt)
        val localHistory = local.getHistory(uid)
        val localHistoryBySlug = localHistory.associateBy { it.slug }
        localHistory.forEach { item ->
            val remote = cloudHistory.child(item.slug)
            val remoteTime = remote.child("watchedAt").getValue(Long::class.java) ?: 0L
            val deletedAt = historyTombstones[item.slug] ?: 0L
            if ((deletedAt >= item.watchedAt && deletedAt > 0L) || (historyClearedAt >= item.watchedAt && historyClearedAt > 0L)) {
                local.removeHistory(item.slug, uid)
                cloudHistoryRef.child(item.slug).removeValue().await()
            } else if (!remote.exists() || item.watchedAt >= remoteTime) {
                cloudHistoryRef.child(item.slug).setValue(mapOf(
                    "slug" to item.slug, "title" to item.title, "ep" to item.ep,
                    "cover" to item.cover, "source" to item.source, "position" to item.position,
                    "duration" to item.duration, "watchedAt" to item.watchedAt
                )).await()
            }
        }
        cloudHistory.children.forEach { item ->
            val slug = item.child("slug").getValue(String::class.java) ?: item.key.orEmpty()
            val remoteTime = item.child("watchedAt").getValue(Long::class.java) ?: 0L
            val deletedAt = historyTombstones[slug] ?: 0L
            val localItem = localHistoryBySlug[slug]
            if ((deletedAt > 0L && deletedAt >= remoteTime) || (historyClearedAt > 0L && historyClearedAt >= remoteTime)) {
                cloudHistoryRef.child(slug).removeValue().await()
            } else if (slug.isNotBlank() && (localItem == null || remoteTime > localItem.watchedAt)) {
                local.saveHistoryAt(slug,
                    item.child("title").getValue(String::class.java).orEmpty(),
                    item.child("ep").getValue(String::class.java).orEmpty(),
                    item.child("cover").getValue(String::class.java).orEmpty(),
                    item.child("source").getValue(String::class.java).orEmpty(),
                    item.child("position").getValue(Double::class.java) ?: 0.0,
                    item.child("duration").getValue(Double::class.java) ?: 0.0, remoteTime, uid)
            }
        }
        root.child("users").child(uid).child("lastSyncAt").setValue(ServerValue.TIMESTAMP).await()
    }

    suspend fun removeFavorite(slug: String) {
        val user = firebase.auth.currentUser
        if (user != null && !user.isAnonymous) local.markFavoriteRemoved(user.uid, slug)
        local.removeFavorite(slug, user?.uid ?: "local")
        CloudSyncScheduler.syncSoon(context)
        runCatching { syncNow() }
    }

    suspend fun removeHistory(slug: String) {
        val user = firebase.auth.currentUser
        if (user != null && !user.isAnonymous) local.markHistoryRemoved(user.uid, slug)
        local.removeHistory(slug, user?.uid ?: "local")
        CloudSyncScheduler.syncSoon(context)
        runCatching { syncNow() }
    }

    suspend fun clearHistory() {
        val user = firebase.auth.currentUser
        local.clearHistory(user?.uid ?: "local")
        CloudSyncScheduler.syncSoon(context)
        runCatching { syncNow() }
    }
}
