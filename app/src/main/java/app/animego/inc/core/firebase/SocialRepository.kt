package app.animego.inc.core.firebase

import android.net.Uri
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.Query
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ServerValue
import kotlinx.coroutines.tasks.await

class SocialRepository(private val firebase: FirebaseProvider) {
    private val root get() = firebase.database.reference
    private fun uid(): String = firebase.auth.currentUser?.uid ?: error("login diperlukan")
    private fun requireMember() { check(firebase.auth.currentUser != null && firebase.auth.currentUser?.isAnonymous == false) { "fitur ini memerlukan akun" } }

    suspend fun updateProfile(username: String, displayName: String, bio: String) {
        requireMember()
        val clean = username.trim().lowercase()
        require(clean.matches(Regex("[a-z0-9_]{3,20}"))) { "username 3–20 karakter: huruf, angka, underscore" }
        require(displayName.trim().length in 2..32)
        val id = uid()
        val old = root.child("users").child(id).child("username").get().await().getValue(String::class.java).orEmpty()
        val taken = root.child("usernames").child(clean).get().await()
        require(!taken.exists() || taken.getValue(String::class.java) == id) { "username sudah dipakai" }
        val updates = mutableMapOf<String, Any>(
            "users/$id/username" to clean, "users/$id/displayName" to displayName.trim(),
            "users/$id/bio" to bio.take(160), "users/$id/updatedAt" to ServerValue.TIMESTAMP,
            "usernames/$clean" to id
        )
        if (old.isNotBlank() && old != clean) updates["usernames/$old"] = ""
        root.updateChildren(updates).await()
    }

    suspend fun searchUsers(prefix: String, limit: Int = 25): List<CloudUser> {
        val q = prefix.trim().lowercase()
        if (q.length < 2) return emptyList()
        val snapshot = root.child("users").orderByChild("username").startAt(q).endAt(q + "\uf8ff").limitToFirst(limit).get().await()
        return snapshot.children.mapNotNull { it.getValue(CloudUser::class.java) }
    }

    suspend fun getUser(userId: String): CloudUser? = root.child("users").child(userId).get().await().getValue(CloudUser::class.java)

    suspend fun follow(targetUid: String) {
        requireMember(); require(targetUid != uid())
        root.child("follows").child(uid()).child(targetUid).setValue(mapOf("createdAt" to ServerValue.TIMESTAMP)).await()
    }

    suspend fun unfollow(targetUid: String) { requireMember(); root.child("follows").child(uid()).child(targetUid).removeValue().await() }

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
            "authorName" to (me?.displayName ?: "pengguna"), "authorAvatar" to (me?.avatarUrl ?: ""),
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
        // likeCount is server-maintained by an RTDB trigger, never trusted from the client.
    }

    suspend fun createConversation(otherUid: String): String {
        requireMember(); require(otherUid != uid())
        val ids = listOf(uid(), otherUid).sorted()
        val id = "${ids[0]}_${ids[1]}"
        firebase.functions.getHttpsCallable("ensureConversation").call(mapOf("otherUid" to otherUid)).await()
        return id
    }

    suspend fun markConversationRead(conversationId: String) {
        requireMember()
        firebase.functions.getHttpsCallable("markConversationRead").call(mapOf("conversationId" to conversationId)).await()
    }

    suspend fun sendTextMessage(conversationId: String, text: String) {
        requireMember(); require(text.trim().length in 1..4000)
        val ref = root.child("conversations").child(conversationId).child("messages").push()
        ref.setValue(mapOf("id" to ref.key, "senderId" to uid(), "type" to "text", "text" to text.trim(),
            "createdAt" to ServerValue.TIMESTAMP)).await()
    }

    suspend fun sendAnimeMessage(conversationId: String, slug: String, title: String, cover: String, episodeId: String = "", episodeNumber: String = "") {
        requireMember(); require(slug.isNotBlank())
        val ref = root.child("conversations").child(conversationId).child("messages").push()
        val type = if (episodeId.isBlank()) "anime" else "episode"
        ref.setValue(mapOf("id" to ref.key, "senderId" to uid(), "type" to type,
            "animeSlug" to slug, "animeTitle" to title, "coverUrl" to cover,
            "episodeId" to episodeId, "episodeNumber" to episodeNumber,
            "createdAt" to ServerValue.TIMESTAMP)).await()
    }

    fun observeMessages(conversationId: String, onData: (List<ChatMessage>) -> Unit, onError: (String) -> Unit): Pair<Query, ValueEventListener> {
        val query = root.child("conversations").child(conversationId).child("messages")
            .orderByChild("createdAt").limitToLast(40)
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                onData(snapshot.children.mapNotNull { it.getValue(ChatMessage::class.java) })
            }
            override fun onCancelled(error: DatabaseError) { onError(error.message) }
        }
        query.addValueEventListener(listener)
        return query to listener
    }

    fun removeMessagesListener(pair: Pair<Query, ValueEventListener>) {
        pair.first.removeEventListener(pair.second)
    }

    suspend fun getMessages(conversationId: String, limit: Int = 40): List<ChatMessage> =
        root.child("conversations").child(conversationId).child("messages")
            .orderByChild("createdAt").limitToLast(limit).get().await()
            .children.mapNotNull { it.getValue(ChatMessage::class.java) }

    suspend fun uploadAvatar(uri: Uri): String {
        requireMember()
        val ref = firebase.storage.reference.child("avatars/${uid()}/profile")
        ref.putFile(uri).await()
        val url = ref.downloadUrl.await().toString()
        root.child("users").child(uid()).child("avatarUrl").setValue(url).await()
        return url
    }

    private fun DataSnapshot.string(key: String) = child(key).getValue(String::class.java).orEmpty()
}
