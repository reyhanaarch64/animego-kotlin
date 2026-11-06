package app.animego.inc.core.firebase

import app.animego.inc.core.database.AnimeDatabase
import com.google.firebase.database.ServerValue
import kotlinx.coroutines.tasks.await

/** Merge-only first-login sync: favorites are unioned; history uses watchedAt last-write-wins. */
class CloudSyncRepository(private val firebase: FirebaseProvider, private val local: AnimeDatabase) {
    suspend fun syncNow() {
        val user = firebase.auth.currentUser ?: return
        if (user.isAnonymous) return
        val uid = user.uid
        val root = firebase.database.reference
        val cloudFavoritesRef = root.child("users").child(uid).child("favorites")
        val cloudHistoryRef = root.child("users").child(uid).child("history")
        val cloudFavorites = cloudFavoritesRef.get().await()
        val favoriteTombstones = root.child("users").child(uid).child("favoriteTombstones").get().await()
        val localFavorites = local.getFavorites()
        val localSlugs = localFavorites.map { it.slug }.toSet()
        localFavorites.forEach { item ->
            val deletedAt = favoriteTombstones.child(item.slug).getValue(Long::class.java) ?: 0L
            if (deletedAt >= item.addedAt && deletedAt > 0L) {
                local.removeFavorite(item.slug)
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
            val deletedAt = favoriteTombstones.child(slug).getValue(Long::class.java) ?: 0L
            if (deletedAt > 0L && deletedAt >= addedAt) {
                cloudFavoritesRef.child(slug).removeValue().await()
            } else if (slug.isNotBlank() && slug !in localSlugs) {
                local.saveFavoriteAt(slug,
                    item.child("title").getValue(String::class.java).orEmpty(),
                    item.child("cover").getValue(String::class.java).orEmpty(), addedAt)
            }
        }

        val cloudHistory = cloudHistoryRef.get().await()
        val historyTombstones = root.child("users").child(uid).child("historyTombstones").get().await()
        val historyClearedAt = root.child("users").child(uid).child("historyClearedAt").get().await().getValue(Long::class.java) ?: 0L
        val localHistory = local.getHistory()
        val localHistoryBySlug = localHistory.associateBy { it.slug }
        localHistory.forEach { item ->
            val remote = cloudHistory.child(item.slug)
            val remoteTime = remote.child("watchedAt").getValue(Long::class.java) ?: 0L
            val deletedAt = historyTombstones.child(item.slug).getValue(Long::class.java) ?: 0L
            if ((deletedAt >= item.watchedAt && deletedAt > 0L) || (historyClearedAt >= item.watchedAt && historyClearedAt > 0L)) {
                local.removeHistory(item.slug)
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
            val deletedAt = historyTombstones.child(slug).getValue(Long::class.java) ?: 0L
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
                    item.child("duration").getValue(Double::class.java) ?: 0.0, remoteTime)
            }
        }
        root.child("users").child(uid).child("lastSyncAt").setValue(ServerValue.TIMESTAMP).await()
    }

    suspend fun removeFavorite(slug: String) {
        local.removeFavorite(slug)
        val user = firebase.auth.currentUser
        if (user != null && !user.isAnonymous) {
            val ref = firebase.database.reference.child("users").child(user.uid)
            ref.child("favoriteTombstones").child(slug).setValue(ServerValue.TIMESTAMP).await()
            ref.child("favorites").child(slug).removeValue().await()
        }
    }

    suspend fun removeHistory(slug: String) {
        local.removeHistory(slug)
        val user = firebase.auth.currentUser
        if (user != null && !user.isAnonymous) {
            val ref = firebase.database.reference.child("users").child(user.uid)
            ref.child("historyTombstones").child(slug).setValue(ServerValue.TIMESTAMP).await()
            ref.child("history").child(slug).removeValue().await()
        }
    }

    suspend fun clearHistory() {
        local.clearHistory()
        val user = firebase.auth.currentUser
        if (user != null && !user.isAnonymous) {
            val ref = firebase.database.reference.child("users").child(user.uid)
            ref.child("historyClearedAt").setValue(ServerValue.TIMESTAMP).await()
            ref.child("history").removeValue().await()
        }
    }
}
