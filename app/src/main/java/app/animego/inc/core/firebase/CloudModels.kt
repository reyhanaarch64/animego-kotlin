package app.animego.inc.core.firebase

data class CloudUser(
    val uid: String = "", val username: String = "", val displayName: String = "",
    val bio: String = "", val avatarUrl: String = "", val createdAt: Long = 0L,
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
    val createdAt: Long = 0L
)

data class FriendRequest(
    val id: String = "", val fromUid: String = "", val toUid: String = "",
    val status: String = "pending", val createdAt: Long = 0L
)
