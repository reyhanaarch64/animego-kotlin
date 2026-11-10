# AnimeGo 2.1.0 — update implementation notes

## Data isolation

SQLite schema version 8 adds `owner_uid` to favorites and history and uses composite `(owner_uid, slug)` primary keys. Every UI read/write and playback progress write now passes the authenticated UID (or `local` when no non-anonymous account is active). Cloud sync reads and merges only the current UID's rows. Existing v7 rows are preserved under the `legacy` owner because the old schema did not retain account ownership; they are not automatically assigned to the first account that logs in.

## Avatar

Avatar bytes continue to be compressed JPEG Base64 in Realtime Database (`avatars/{uid}/data`) with hash and timestamp metadata. Account editing now opens an in-app crop step with drag and zoom controls before upload. The UI listens for avatar metadata changes and refreshes the local file cache. Google profile photo URLs and a public fallback initial are stored in `publicProfiles`; the current user's email initial is used locally for email/password accounts. Firebase Storage is not used.

## Download integrity

Resume appends only a valid HTTP 206 response whose Content-Range begins at the exact partial-file length. A 200 response after a Range request restarts from byte zero. HTML/JSON/text responses, malformed ranges, inconsistent body lengths, and incomplete final files are rejected.

## UI

- Anime detail comments use a draggable Material 3 ModalBottomSheet.
- Profile shortcuts are horizontally scrollable FilterChips.
- Home catalog uses in-memory cache-first rendering and keeps the last non-empty section while refreshes complete.

## Realtime Database rules

`database/database.rules.json` now supports the client paths used by profile editing, username release/reclaim, favorites/history sync, avatar upload, public comment/like reads, and private account data. Username deletion is represented by a null write; stale empty username nodes from the previous implementation may be reclaimed by their next claimant.

Deploy with:

```bash
firebase deploy --only database
```

The rules unit tests were expanded for username reuse, public avatar reads, and account-private favorites/history. They still need to be executed against the Firebase Database Emulator before production deployment.
