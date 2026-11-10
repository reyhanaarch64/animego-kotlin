package app.animego.inc.core.firebase

import android.content.Context
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.Query
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import app.animego.inc.core.database.AnimeDatabase
import java.util.UUID
import kotlinx.coroutines.tasks.await

class SocialRepository(
    private val context: Context,
    private val firebase: FirebaseProvider,
    private val local: AnimeDatabase
) {
    private val root get() = firebase.database.reference
    private fun uid(): String = firebase.auth.currentUser?.uid ?: error("login diperlukan")
    private fun requireMember() {
        check(firebase.auth.currentUser != null && firebase.auth.currentUser?.isAnonymous == false) { "fitur ini memerlukan akun" }
    }

    suspend fun updateProfile(username: String, displayName: String, bio: String) {
        requireMember()
        val clean = username.trim().lowercase()
        require(clean.matches(Regex("[a-z0-9_]{3,20}"))) { "username 3–20 karakter: huruf, angka, underscore" }
        require(displayName.trim().length in 2..32)
        val id = uid()
        val old = root.child("publicProfiles").child(id).child("username").get().await().getValue(String::class.java).orEmpty()
        val oldReservation = if (old.isNotBlank() && old != clean) {
            root.child("usernames").child(old).get().await().getValue(String::class.java)
        } else null
        val taken = root.child("usernames").child(clean).get().await()
        require(!taken.exists() || taken.getValue(String::class.java).isNullOrBlank() || taken.getValue(String::class.java) == id) { "username sudah dipakai" }
        val updates = mutableMapOf<String, Any?>(
            "publicProfiles/$id/uid" to id, "publicProfiles/$id/username" to clean, "publicProfiles/$id/displayName" to displayName.trim(),
            "publicProfiles/$id/bio" to bio.take(160), "publicProfiles/$id/updatedAt" to ServerValue.TIMESTAMP,
            "users/$id/uid" to id, "users/$id/username" to clean, "users/$id/displayName" to displayName.trim(),
            "users/$id/bio" to bio.take(160), "users/$id/updatedAt" to ServerValue.TIMESTAMP,
            "usernames/$clean" to id
        )
        // Release only a reservation that is still owned by this UID. Older builds
        // wrote an empty string instead of deleting the node; do not try to delete
        // that legacy node because the hardened rules correctly reject that write.
        if (old.isNotBlank() && old != clean && oldReservation == id) updates["usernames/$old"] = null
        root.updateChildren(updates).await()
    }

    suspend fun searchUsers(prefix: String, limit: Int = 25): List<CloudUser> {
        val q = prefix.trim().lowercase()
        if (q.length < 2) return emptyList()
        val snapshot = root.child("publicProfiles").orderByChild("username").startAt(q).endAt(q + "\uf8ff").limitToFirst(limit).get().await()
        return snapshot.children.mapNotNull { it.getValue(CloudUser::class.java) }
    }

    suspend fun getUser(userId: String): CloudUser? = root.child("publicProfiles").child(userId).get().await().getValue(CloudUser::class.java)

    suspend fun follow(targetUid: String) {
        requireMember(); require(targetUid != uid())
        root.child("follows").child(uid()).child(targetUid).setValue(mapOf("createdAt" to ServerValue.TIMESTAMP)).await()
    }

    suspend fun unfollow(targetUid: String) {
        requireMember(); root.child("follows").child(uid()).child(targetUid).removeValue().await()
    }

    suspend fun sendFriendRequest(targetUid: String) {
        requireMember(); require(targetUid != uid())
        val key = root.child("friendRequests").push().key ?: error("gagal membuat request")
        root.child("friendRequests").child(key).setValue(mapOf(
            "id" to key, "fromUid" to uid(), "toUid" to targetUid,
            "status" to "pending", "createdAt" to ServerValue.TIMESTAMP
        )).await()
    }

    suspend fun getIncomingRequests(): List<FriendRequest> {
        requireMember()
        return root.child("incomingRequests").child(uid()).get().await()
            .children.mapNotNull { it.getValue(FriendRequest::class.java) }.filter { it.status == "pending" }
    }

    suspend fun getFriends(): List<CloudUser> {
        requireMember()
        val ids = root.child("friendships").child(uid()).get().await().children.map { it.key.orEmpty() }
        return ids.mapNotNull { getUser(it) }
    }

    suspend fun respondFriendRequest(requestId: String, accept: Boolean) {
        requireMember()
        firebase.functions.getHttpsCallable(if (accept) "acceptFriendRequest" else "declineFriendRequest")
            .call(mapOf("requestId" to requestId)).await()
    }

    suspend fun removeFriend(otherUid: String) {
        requireMember()
        firebase.functions.getHttpsCallable("removeFriend").call(mapOf("otherUid" to otherUid)).await()
    }

    suspend fun block(otherUid: String) {
        requireMember()
        firebase.functions.getHttpsCallable("blockUser").call(mapOf("otherUid" to otherUid)).await()
    }

    suspend fun report(targetType: String, targetId: String, reason: String) {
        requireMember(); require(targetType in setOf("user", "comment", "message")); require(reason.trim().length >= 5)
        val key = root.child("reports").push().key ?: error("gagal membuat laporan")
        root.child("reports").child(key).setValue(mapOf(
            "id" to key, "reporterId" to uid(), "targetType" to targetType,
            "targetId" to targetId, "reason" to reason.take(500), "createdAt" to ServerValue.TIMESTAMP,
            "status" to "open"
        )).await()
    }

    suspend fun postComment(animeSlug: String, text: String, parentId: String = ""): String {
        requireMember(); require(text.trim().length in 1..2000)
        val id = root.child("comments").child(animeSlug).push().key ?: error("gagal membuat komentar")
        val me = getUser(uid())
        root.child("comments").child(animeSlug).child(id).setValue(mapOf(
            "id" to id, "animeSlug" to animeSlug, "authorId" to uid(),
            "authorName" to (me?.displayName ?: "pengguna"), "authorAvatar" to (me?.avatarHash ?: ""),
            "text" to text.trim(), "parentId" to parentId,
            "createdAt" to ServerValue.TIMESTAMP, "likeCount" to 0
        )).await()
        return id
    }

    suspend fun getComments(animeSlug: String, limit: Int = 30): List<CloudComment> =
        root.child("comments").child(animeSlug).orderByChild("createdAt").limitToLast(limit).get().await()
            .children.mapNotNull { it.getValue(CloudComment::class.java) }.reversed()

    suspend fun hasLikedComment(animeSlug: String, commentId: String): Boolean {
        if (firebase.auth.currentUser == null || firebase.auth.currentUser?.isAnonymous == true) return false
        return root.child("commentLikes").child(animeSlug).child(commentId).child(uid()).get().await().exists()
    }

    suspend fun likeComment(animeSlug: String, commentId: String, liked: Boolean) {
        requireMember()
        val ref = root.child("commentLikes").child(animeSlug).child(commentId).child(uid())
        if (liked) ref.setValue(true).await() else ref.removeValue().await()
    }

    suspend fun createConversation(otherUid: String): String {
        requireMember(); require(otherUid != uid())
        val currentUid = uid()
        val ids = listOf(currentUid, otherUid).sorted()
        val id = "${ids[0]}_${ids[1]}"
        val peer = getUser(otherUid)
        local.upsertConversation(currentUid, LocalConversation(
            id = id, otherUid = otherUid, otherDisplayName = peer?.displayName.orEmpty(),
            otherUsername = peer?.username.orEmpty(), updatedAt = System.currentTimeMillis()
        ))
        firebase.functions.getHttpsCallable("ensureConversation").call(mapOf("otherUid" to otherUid)).await()
        return id
    }

    suspend fun getConversations(): List<LocalConversation> {
        requireMember()
        val currentUid = uid()
        try {
            val snapshot = root.child("userConversations").child(currentUid).get().await()
            snapshot.children.forEach { item ->
                val id = item.key.orEmpty()
                val otherUid = item.child("otherUid").getValue(String::class.java).orEmpty()
                if (id.isBlank() || otherUid.isBlank()) return@forEach
                val remoteProfile = getUser(otherUid)
                val existing = local.getConversations(currentUid).firstOrNull { it.id == id }
                local.upsertConversation(currentUid, LocalConversation(
                    id = id, otherUid = otherUid,
                    otherDisplayName = remoteProfile?.displayName ?: existing?.otherDisplayName.orEmpty(),
                    otherUsername = remoteProfile?.username ?: existing?.otherUsername.orEmpty(),
                    lastMessage = item.child("lastMessage").getValue(String::class.java) ?: existing?.lastMessage.orEmpty(),
                    updatedAt = item.child("updatedAt").getValue(Long::class.java) ?: existing?.updatedAt ?: 0L,
                    unreadCount = item.child("unreadCount").getValue(Int::class.java) ?: existing?.unreadCount ?: 0
                ))
            }
        } catch (_: Exception) {
            // Local conversation history is intentionally available when the network is unavailable.
        }
        return local.getConversations(currentUid)
    }

    suspend fun markConversationRead(conversationId: String, force: Boolean = false) {
        requireMember()
        val ownerUid = uid()
        if (local.markConversationReadPending(ownerUid, conversationId, force)) {
            ChatSyncScheduler.schedule(context)
        }
    }

    suspend fun sendTextMessage(conversationId: String, text: String) {
        requireMember(); require(text.trim().length in 1..4000)
        val id = UUID.randomUUID().toString()
        local.insertPendingMessage(uid(), conversationId, ChatMessage(
            id = id, senderId = uid(), type = "text", text = text.trim(), createdAt = System.currentTimeMillis(), deliveryState = "pending"
        ))
        ChatSyncScheduler.schedule(context)
    }

    suspend fun sendAnimeMessage(conversationId: String, slug: String, title: String, cover: String, episodeId: String = "", episodeNumber: String = "") {
        requireMember(); require(slug.isNotBlank())
        val id = UUID.randomUUID().toString()
        val type = if (episodeId.isBlank()) "anime" else "episode"
        local.insertPendingMessage(uid(), conversationId, ChatMessage(
            id = id, senderId = uid(), type = type, animeSlug = slug, animeTitle = title,
            coverUrl = cover, episodeId = episodeId, episodeNumber = episodeNumber,
            createdAt = System.currentTimeMillis(), deliveryState = "pending"
        ))
        ChatSyncScheduler.schedule(context)
    }

    suspend fun syncPendingMessages() {
        val user = firebase.auth.currentUser ?: return
        if (user.isAnonymous) return
        // Re-read the outbox after each batch so messages queued during a running worker are included.
        while (true) {
            val pending = local.getPendingMessages(user.uid)
            if (pending.isEmpty()) break
            pending.forEach { (conversationId, message) ->
                local.setMessageState(user.uid, message.id, "sending")
                try {
                    val messageRef = root.child("conversations").child(conversationId).child("messages").child(message.id)
                    if (!messageRef.get().await().exists()) {
                        messageRef.setValue(mapOf(
                            "id" to message.id, "senderId" to message.senderId, "type" to message.type,
                            "text" to message.text, "animeSlug" to message.animeSlug, "animeTitle" to message.animeTitle,
                            "coverUrl" to message.coverUrl, "episodeId" to message.episodeId,
                            "episodeNumber" to message.episodeNumber, "createdAt" to ServerValue.TIMESTAMP
                        )).await()
                    }
                    local.setMessageState(user.uid, message.id, "sent")
                } catch (e: Exception) {
                    local.setMessageState(user.uid, message.id, "failed")
                    throw e
                }
            }
        }

        // Read receipts are also queued locally so opening a chat offline is not lost.
        local.getPendingConversationReads(user.uid).forEach { conversationId ->
            firebase.functions.getHttpsCallable("markConversationRead")
                .call(mapOf("conversationId" to conversationId)).await()
            local.markConversationReadSynced(user.uid, conversationId)
        }
    }

    fun observeMessages(
        conversationId: String,
        onData: (List<ChatMessage>) -> Unit,
        onError: (String) -> Unit
    ): Pair<Query, ValueEventListener> {
        val ownerUid = uid()
        val query = root.child("conversations").child(conversationId).child("messages")
            .orderByChild("createdAt").limitToLast(100)
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val rows = snapshot.children.mapNotNull { it.getValue(ChatMessage::class.java) }
                local.upsertMessages(ownerUid, conversationId, rows)
                onData(local.getMessages(ownerUid, conversationId))
            }
            override fun onCancelled(error: DatabaseError) { onError(error.message) }
        }
        query.addValueEventListener(listener)
        return query to listener
    }

    fun removeMessagesListener(pair: Pair<Query, ValueEventListener>) {
        pair.first.removeEventListener(pair.second)
    }

    fun getLocalMessages(conversationId: String, limit: Int = 100): List<ChatMessage> =
        local.getMessages(uid(), conversationId, limit)

    suspend fun getMessages(conversationId: String, limit: Int = 100): List<ChatMessage> {
        val ownerUid = uid()
        try {
            val rows = root.child("conversations").child(conversationId).child("messages")
                .orderByChild("createdAt").limitToLast(limit).get().await()
                .children.mapNotNull { it.getValue(ChatMessage::class.java) }
            local.upsertMessages(ownerUid, conversationId, rows)
        } catch (_: Exception) { }
        return local.getMessages(ownerUid, conversationId, limit)
    }
}
