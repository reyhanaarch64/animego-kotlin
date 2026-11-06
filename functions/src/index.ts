import { initializeApp } from "firebase-admin/app";
import { getDatabase, ServerValue } from "firebase-admin/database";
import { onCall, HttpsError } from "firebase-functions/v2/https";
import { onValueCreated, onValueDeleted } from "firebase-functions/v2/database";

initializeApp();
const db = getDatabase();
const region = "asia-southeast1";
function caller(request: { auth?: { uid: string } }) {
  if (!request.auth?.uid) throw new HttpsError("unauthenticated", "login diperlukan");
  return request.auth.uid;
}

export const acceptFriendRequest = onCall({ region }, async (request) => {
  const uid = caller(request);
  const requestId = String(request.data?.requestId ?? "");
  if (!requestId) throw new HttpsError("invalid-argument", "requestId wajib diisi");
  const ref = db.ref(`friendRequests/${requestId}`);
  const snap = await ref.get();
  const item = snap.val();
  if (!item || item.toUid !== uid || item.status !== "pending") throw new HttpsError("permission-denied", "permintaan tidak valid");
  const other = String(item.fromUid);
  if (await db.ref(`blocks/${uid}/${other}`).get().then(s => s.exists()) || await db.ref(`blocks/${other}/${uid}`).get().then(s => s.exists())) {
    throw new HttpsError("failed-precondition", "pengguna diblokir");
  }
  const updates: Record<string, unknown> = {
    [`incomingRequests/${uid}/${requestId}/status`]: "accepted",
    [`friendRequests/${requestId}/status`]: "accepted",
    [`friendRequests/${requestId}/respondedAt`]: ServerValue.TIMESTAMP,
    [`friendships/${uid}/${other}`]: true,
    [`friendships/${other}/${uid}`]: true
  };
  await db.ref().update(updates);
  return { success: true };
});

export const declineFriendRequest = onCall({ region }, async (request) => {
  const uid = caller(request);
  const requestId = String(request.data?.requestId ?? "");
  const ref = db.ref(`friendRequests/${requestId}`);
  const snap = await ref.get();
  const item = snap.val();
  if (!item || item.toUid !== uid || item.status !== "pending") throw new HttpsError("permission-denied", "permintaan tidak valid");
  await db.ref().update({
    [`friendRequests/${requestId}/status`]: "declined",
    [`friendRequests/${requestId}/respondedAt`]: ServerValue.TIMESTAMP,
    [`incomingRequests/${uid}/${requestId}/status`]: "declined"
  });
  return { success: true };
});

export const removeFriend = onCall({ region }, async (request) => {
  const uid = caller(request);
  const other = String(request.data?.otherUid ?? "");
  if (!other || other === uid) throw new HttpsError("invalid-argument", "target tidak valid");
  await db.ref().update({ [`friendships/${uid}/${other}`]: null, [`friendships/${other}/${uid}`]: null });
  return { success: true };
});

export const blockUser = onCall({ region }, async (request) => {
  const uid = caller(request);
  const other = String(request.data?.otherUid ?? "");
  if (!other || other === uid) throw new HttpsError("invalid-argument", "target tidak valid");
  await db.ref().update({
    [`blocks/${uid}/${other}`]: true,
    [`friendships/${uid}/${other}`]: null,
    [`friendships/${other}/${uid}`]: null
  });
  return { success: true };
});

export const ensureConversation = onCall({ region }, async (request) => {
  const uid = caller(request);
  const other = String(request.data?.otherUid ?? "");
  if (!other || other === uid) throw new HttpsError("invalid-argument", "target tidak valid");
  const [a, b, ba, bb] = await Promise.all([
    db.ref(`friendships/${uid}/${other}`).get(), db.ref(`friendships/${other}/${uid}`).get(),
    db.ref(`blocks/${uid}/${other}`).get(), db.ref(`blocks/${other}/${uid}`).get()
  ]);
  if (!a.exists() || !b.exists() || ba.exists() || bb.exists()) throw new HttpsError("permission-denied", "chat hanya tersedia untuk teman dan pengguna yang tidak diblokir");
  const id = [uid, other].sort().join("_");
  const members = { [uid]: { peerUid: other }, [other]: { peerUid: uid } };
  const updates: Record<string, unknown> = {
    [`conversations/${id}/members`]: members,
    [`conversations/${id}/createdAt`]: ServerValue.TIMESTAMP,
    [`userConversations/${uid}/${id}`]: { otherUid: other, updatedAt: ServerValue.TIMESTAMP },
    [`userConversations/${other}/${id}`]: { otherUid: uid, updatedAt: ServerValue.TIMESTAMP }
  };
  await db.ref().update(updates);
  return { conversationId: id };
});

export const onCommentLikeCreated = onValueCreated({ ref: "/commentLikes/{slug}/{commentId}/{uid}", region }, async (event) => {
  if (event.data.val() !== true) return;
  await db.ref(`comments/${event.params.slug}/${event.params.commentId}/likeCount`).transaction(v => (Number(v) || 0) + 1);
});
export const onCommentLikeDeleted = onValueDeleted({ ref: "/commentLikes/{slug}/{commentId}/{uid}", region }, async (event) => {
  await db.ref(`comments/${event.params.slug}/${event.params.commentId}/likeCount`).transaction(v => Math.max(0, (Number(v) || 0) - 1));
});

export const onFollowCreated = onValueCreated({ ref: "/follows/{uid}/{targetUid}", region }, async (event) => {
  const { uid, targetUid } = event.params;
  await Promise.all([
    db.ref(`users/${uid}/followingCount`).transaction(v => (Number(v) || 0) + 1),
    db.ref(`users/${targetUid}/followerCount`).transaction(v => (Number(v) || 0) + 1)
  ]);
});
export const onFollowDeleted = onValueDeleted({ ref: "/follows/{uid}/{targetUid}", region }, async (event) => {
  const { uid, targetUid } = event.params;
  await Promise.all([
    db.ref(`users/${uid}/followingCount`).transaction(v => Math.max(0, (Number(v) || 0) - 1)),
    db.ref(`users/${targetUid}/followerCount`).transaction(v => Math.max(0, (Number(v) || 0) - 1))
  ]);
});


export const onFriendRequestCreated = onValueCreated({ ref: "/friendRequests/{requestId}", region }, async (event) => {
  const request = event.data.val();
  if (!request?.toUid) return;
  await db.ref(`incomingRequests/${request.toUid}/${event.params.requestId}`).set(request);
});

export const onChatMessageCreated = onValueCreated({ ref: "/conversations/{conversationId}/messages/{messageId}", region }, async (event) => {
  const message = event.data.val();
  if (!message?.senderId) return;
  const conv = await db.ref(`conversations/${event.params.conversationId}/members`).get();
  const sender = String(message.senderId);
  const peer = String(conv.child(sender).child("peerUid").val() ?? "");
  if (!peer) return;
  const preview = message.type === "text" ? String(message.text ?? "pesan") : message.type === "episode" ? `episode ${message.episodeNumber ?? ""}` : `anime: ${message.animeTitle ?? ""}`;
  await db.ref().update({
    [`userConversations/${sender}/${event.params.conversationId}/lastMessage`]: preview,
    [`userConversations/${sender}/${event.params.conversationId}/updatedAt`]: ServerValue.TIMESTAMP,
    [`userConversations/${peer}/${event.params.conversationId}/lastMessage`]: preview,
    [`userConversations/${peer}/${event.params.conversationId}/updatedAt`]: ServerValue.TIMESTAMP
  });
  await db.ref(`userConversations/${peer}/${event.params.conversationId}/unreadCount`).transaction(v => (Number(v) || 0) + 1);
  const tokens = await db.ref(`userTokens/${peer}`).get();
  const registrationTokens = Object.keys(tokens.val() ?? {});
  if (registrationTokens.length) {
    const { getMessaging } = await import("firebase-admin/messaging");
    await getMessaging().sendEachForMulticast({
      tokens: registrationTokens,
      notification: { title: "pesan baru", body: preview.slice(0, 120) },
      android: { notification: { clickAction: "OPEN_CHAT" } },
      data: { type: "chat", conversationId: event.params.conversationId }
    });
  }
});

export const markConversationRead = onCall({ region }, async (request) => {
  const uid = caller(request);
  const conversationId = String(request.data?.conversationId ?? "");
  if (!conversationId) throw new HttpsError("invalid-argument", "conversationId wajib diisi");
  const member = await db.ref(`conversations/${conversationId}/members/${uid}`).get();
  if (!member.exists()) throw new HttpsError("permission-denied", "bukan anggota percakapan");
  await db.ref(`userConversations/${uid}/${conversationId}`).update({ unreadCount: 0, readAt: ServerValue.TIMESTAMP });
  return { success: true };
});
