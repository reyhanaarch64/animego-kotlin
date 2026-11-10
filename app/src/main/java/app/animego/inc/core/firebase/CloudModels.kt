package app.animego.inc.core.firebase

data class CloudUser(
    val uid: String = "", val username: String = "", val displayName: String = "",
    val bio: String = "", val avatarHash: String = "", val avatarMimeType: String = "image/jpeg",
    val googlePhotoUrl: String = "", val avatarFallback: String = "",
    val avatarUpdatedAt: Long = 0L, val createdAt: Long = 0L,
    val followerCount: Long = 0L, val followingCount: Long = 0L
)

data class CloudComment(
    val id: String = "", val animeSlug: String = "", val authorId: String = "",
    val authorName: String = "", val authorAvatar: String = "", val text: String = "",
    val parentId: String = "", val createdAt: Long = 0L, val likeCount: Long = 0L
)

data class ChatMessage(
    val id: String = "", val senderId: String = "", val type: String = "text",
    val text: String = "", val animeSlug: String = "", val animeTitle: String = "",
    val coverUrl: String = "", val episodeId: String = "", val episodeNumber: String = "",
    val createdAt: Long = 0L, val deliveryState: String = "sent"
)

data class LocalConversation(
    val id: String = "", val otherUid: String = "", val otherDisplayName: String = "",
    val otherUsername: String = "", val lastMessage: String = "", val updatedAt: Long = 0L,
    val unreadCount: Int = 0
)

data class FriendRequest(
    val id: String = "", val fromUid: String = "", val toUid: String = "",
    val status: String = "pending", val createdAt: Long = 0L
)
