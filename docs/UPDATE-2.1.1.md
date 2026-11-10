# AnimeGo 2.1.1 — RTDB compatibility and security hardening

## Client path compatibility

| RTDB path | Client operation | Rule behavior |
|---|---|---|
| `users/{uid}` | Read own private profile | Owner-only read; no parent write grant |
| `users/{uid}/uid`, `displayName`, `username`, `bio`, `createdAt`, `updatedAt` | Initialize/update private profile | Explicit owner writes with field validation |
| `users/{uid}/favorites/{slug}` | Favorite sync | Owner-only write; slug/title/addedAt validation |
| `users/{uid}/history/{slug}` | Watch-history sync | Owner-only write; slug/title/watchedAt validation |
| `users/{uid}/favoriteTombstones/{slug}` | Favorite deletion sync | Owner-only numeric timestamp |
| `users/{uid}/historyTombstones/{slug}` | History deletion sync | Owner-only numeric timestamp |
| `users/{uid}/historyClearedAt`, `lastSyncAt` | Sync metadata | Owner-only numeric timestamp |
| `usernames/{username}` | Availability lookup and reservation | Public read; authenticated owner claim/release; empty legacy node reclaim |
| `publicProfiles/{uid}` | Search and public profile | Public read; field-level owner writes; counters server-managed |
| `avatars/{uid}/data` and `meta` | Base64 avatar upload/read | Public read; owner-only write; payload and metadata validation |
| `follows/{uid}/{targetUid}` | Follow/unfollow | Authenticated owner only; self-follow and blocked pairs rejected |
| `friendRequests/{requestId}` | Send/read request | Sender creates; participants read; response is Cloud Functions-only |
| `incomingRequests/{uid}` | Read inbox requests | Recipient-only read; server-managed writes |
| `friendships/{uid}` | Read friend list | Owner-only read; server-managed writes |
| `blocks/{uid}` | Read own block records | Owner-only read; server-managed writes |
| `comments/{slug}` | Public read/create comment | Public read; authenticated author-only create |
| `commentLikes/{slug}/{commentId}/{uid}` | Read/like/unlike | Public read; each user writes only their own like node |
| `conversations/{id}/members`, `createdAt` | Read conversation metadata | Members-only read; server-managed writes |
| `conversations/{id}/messages/{messageId}` | Read/send messages | Mutual-friend member check, block check, sender identity, immutable create |
| `userConversations/{uid}` | Read inbox index | Owner-only read; server-managed writes |
| `userTokens/{uid}/{token}` | Register/remove FCM token | Owner-only write; value must be `true` |
| `reports/{reportId}` | Submit report | Authenticated reporter-only create; valid target/reason and `open` status |

## Important implementation notes

- A broad `.write` rule at `users/{uid}` would override more restrictive child `.write` rules. The parent now grants read only; every client-written child has its own `.write` rule.
- Cloud Functions use the Admin SDK for server-owned paths. The Android client must not receive write permission for friendship state, blocks, incoming request copies, conversation membership/indexes, counters, or unread counts.
- Chat message timestamps sent to RTDB use `ServerValue.TIMESTAMP`. Local outbox timestamps remain local for offline ordering until the server accepts the message.
- The previous username-release implementation wrote an empty string. Rules allow the next claimant to replace that legacy empty node. The client only deletes a reservation when its current value still equals the caller UID.
- SQLite v7 favorites/history rows migrate to owner `legacy`. They remain on device but are not assigned to the first account that logs in, because the previous schema did not record ownership.

## Verification status

- JSON syntax validation: run locally with `python -m json.tool database/database.rules.json`.
- Rules test syntax validation: run with `node --check functions/tests/database.rules.test.mjs`.
- Firebase Database Emulator tests: defined in `functions/tests/database.rules.test.mjs`, but require `firebase-tools`, `@firebase/rules-unit-testing`, `firebase`, and a working emulator runtime.
- Android compilation/device testing: not performed in this environment because Android SDK/Gradle dependencies are unavailable.
