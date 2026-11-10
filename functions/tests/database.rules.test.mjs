import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import test, { before, beforeEach, after } from 'node:test';
import { initializeTestEnvironment, assertFails, assertSucceeds } from '@firebase/rules-unit-testing';
import { get, ref, set, update } from 'firebase/database';

let env;
const auth = (uid) => env.authenticatedContext(uid, { firebase: { sign_in_provider: 'password' } }).database();
const guest = (uid) => env.authenticatedContext(uid, { firebase: { sign_in_provider: 'anonymous' } }).database();
const publicDb = () => env.unauthenticatedContext().database();
const seed = (path, value) => env.withSecurityRulesDisabled(async (ctx) => set(ref(ctx.database(), path), value));

before(async () => {
  env = await initializeTestEnvironment({
    projectId: 'animego2',
    database: {
      rules: readFileSync(resolve('database/database.rules.json'), 'utf8'),
      host: '127.0.0.1',
      port: 9000
    }
  });
});
beforeEach(async () => { await env.clearDatabase(); });
after(async () => { await env?.cleanup(); });

test('profile initialization supports the exact private/public paths used by AuthRepository', async () => {
  const alice = auth('alice');
  const now = Date.now();
  await assertSucceeds(update(ref(alice), {
    'users/alice/uid': 'alice',
    'users/alice/displayName': 'AnimeGo User',
    'users/alice/username': 'anime_user',
    'users/alice/bio': 'watching anime',
    'users/alice/createdAt': now,
    'users/alice/updatedAt': now,
    'publicProfiles/alice/uid': 'alice',
    'publicProfiles/alice/displayName': 'AnimeGo User',
    'publicProfiles/alice/username': 'anime_user',
    'publicProfiles/alice/createdAt': now,
    'publicProfiles/alice/updatedAt': now,
    'publicProfiles/alice/googlePhotoUrl': 'https://example.com/photo.jpg',
    'publicProfiles/alice/avatarFallback': 'A',
    'usernames/anime_user': 'alice'
  }));
  await assertSucceeds(get(ref(alice, 'users/alice')));
  await assertSucceeds(get(ref(publicDb(), 'publicProfiles/alice')));
  await assertFails(get(ref(auth('bob'), 'users/alice')));
  await assertFails(set(ref(auth('bob'), 'publicProfiles/alice/displayName'), 'intruder'));
});

test('private profile fields and favorites/history are writable only by their account owner', async () => {
  const alice = auth('alice');
  const bob = auth('bob');
  const now = Date.now();
  await assertSucceeds(set(ref(alice, 'users/alice/uid'), 'alice'));
  await assertFails(set(ref(alice, 'users/alice/unrecognizedAdminFlag'), true));
  await assertSucceeds(set(ref(alice, 'users/alice/favorites/demo'), {
    slug: 'demo', title: 'Demo', cover: 'https://example.com/cover.jpg', addedAt: now
  }));
  await assertSucceeds(set(ref(alice, 'users/alice/history/demo'), {
    slug: 'demo', title: 'Demo', ep: 'episode-1', cover: '', source: 'source', position: 10, duration: 100, watchedAt: now
  }));
  await assertSucceeds(set(ref(alice, 'users/alice/favoriteTombstones/demo'), now));
  await assertSucceeds(set(ref(alice, 'users/alice/historyTombstones/demo'), now));
  await assertSucceeds(set(ref(alice, 'users/alice/historyClearedAt'), now));
  await assertSucceeds(set(ref(alice, 'users/alice/lastSyncAt'), now));
  await assertFails(get(ref(bob, 'users/alice/favorites')));
  await assertFails(set(ref(bob, 'users/alice/favorites/other'), { slug: 'other', title: 'Other', addedAt: now }));
  await assertFails(set(ref(bob, 'users/alice/history/other'), { slug: 'other', title: 'Other', watchedAt: now }));
  await assertFails(set(ref(guest('guest1'), 'users/guest1/favorites/demo'), { slug: 'demo', title: 'Demo', addedAt: now }));
  await assertFails(set(ref(alice, 'users/alice/favorites/wrong-key'), { slug: 'demo', title: 'Demo', addedAt: now }));
});

test('username reservation can be released, reused, and cannot be stolen', async () => {
  const alice = auth('alice');
  const bob = auth('bob');
  await assertSucceeds(set(ref(alice, 'usernames/reusable_name'), 'alice'));
  await assertFails(set(ref(bob, 'usernames/reusable_name'), 'bob'));
  await assertSucceeds(set(ref(alice, 'usernames/reusable_name'), null));
  await assertSucceeds(set(ref(bob, 'usernames/reusable_name'), 'bob'));
});

test('legacy empty username nodes can be reclaimed by a new owner', async () => {
  await seed('usernames/old_empty_name', '');
  await assertSucceeds(set(ref(auth('alice'), 'usernames/old_empty_name'), 'alice'));
});

test('avatar Base64 is public-readable but only its owner can upload metadata and image data', async () => {
  const alice = auth('alice');
  const bob = auth('bob');
  await assertSucceeds(update(ref(alice), {
    'avatars/alice/data': 'aGVsbG8=',
    'avatars/alice/meta/hash': 'a'.repeat(64),
    'avatars/alice/meta/mimeType': 'image/jpeg',
    'avatars/alice/meta/updatedAt': Date.now(),
    'publicProfiles/alice/avatarHash': 'a'.repeat(64),
    'publicProfiles/alice/avatarUpdatedAt': Date.now()
  }));
  await assertSucceeds(get(ref(publicDb(), 'avatars/alice/data')));
  await assertSucceeds(get(ref(publicDb(), 'avatars/alice/meta')));
  await assertFails(set(ref(bob, 'avatars/alice/data'), 'not-owner'));
  await assertFails(set(ref(alice, 'avatars/alice/meta/mimeType'), 'image/png'));
  await assertFails(set(ref(alice, 'avatars/alice/data'), 'not-base64!'));
  await assertFails(set(ref(alice, 'avatars/alice/data'), 'x'.repeat(204801)));
});

test('comments are public-readable, authenticated users may create valid comments only', async () => {
  const alice = auth('alice');
  const now = Date.now();
  await assertSucceeds(set(ref(alice, 'comments/demo/c1'), {
    id: 'c1', animeSlug: 'demo', authorId: 'alice', authorName: 'Alice', authorAvatar: '',
    text: 'hello', parentId: '', createdAt: now, likeCount: 0
  }));
  await assertSucceeds(get(ref(publicDb(), 'comments/demo')));
  await assertFails(set(ref(auth('bob'), 'comments/demo/c1'), {
    id: 'c1', animeSlug: 'demo', authorId: 'bob', text: 'overwrite', parentId: '', createdAt: now
  }));
  await assertFails(set(ref(auth('bob'), 'comments/demo/c2'), {
    id: 'c2', animeSlug: 'demo', authorId: 'alice', text: 'spoof', parentId: '', createdAt: now
  }));
  await assertFails(set(ref(guest('guest1'), 'comments/demo/c3'), {
    id: 'c3', animeSlug: 'demo', authorId: 'guest1', text: 'guest', parentId: '', createdAt: now
  }));
  await assertFails(set(ref(alice, 'comments/demo/c4'), {
    id: 'c4', animeSlug: 'another-anime', authorId: 'alice', text: 'wrong slug', parentId: '', createdAt: now
  }));
});

test('comment likes are publicly readable and user-scoped on writes', async () => {
  await seed('comments/demo/c1', { id: 'c1', animeSlug: 'demo', authorId: 'alice', text: 'hello', parentId: '', createdAt: Date.now(), likeCount: 0 });
  await assertSucceeds(set(ref(auth('bob'), 'commentLikes/demo/c1/bob'), true));
  await assertSucceeds(get(ref(publicDb(), 'commentLikes/demo/c1')));
  await assertFails(set(ref(auth('bob'), 'commentLikes/demo/c1/alice'), true));
  await assertFails(set(ref(auth('bob'), 'commentLikes/demo/missing/bob'), true));
});

test('follow writes are owner-scoped and blocked/self follows are denied', async () => {
  await assertSucceeds(set(ref(auth('alice'), 'follows/alice/bob'), { createdAt: Date.now() }));
  await assertFails(set(ref(auth('bob'), 'follows/alice/eve'), { createdAt: Date.now() }));
  await assertFails(set(ref(auth('alice'), 'follows/alice/alice'), { createdAt: Date.now() }));
  await seed('blocks/alice/bob', true);
  await assertFails(set(ref(auth('alice'), 'follows/alice/bob'), { createdAt: Date.now() }));
});

test('friend requests can only be created by the sender and cannot be modified by clients', async () => {
  const alice = auth('alice');
  const now = Date.now();
  await assertSucceeds(set(ref(alice, 'friendRequests/request1'), {
    id: 'request1', fromUid: 'alice', toUid: 'bob', status: 'pending', createdAt: now
  }));
  await assertSucceeds(get(ref(alice, 'friendRequests/request1')));
  await assertFails(set(ref(auth('bob'), 'friendRequests/request1/status'), 'accepted'));
  await assertFails(set(ref(auth('bob'), 'friendRequests/request2'), {
    id: 'request2', fromUid: 'alice', toUid: 'bob', status: 'pending', createdAt: now
  }));
  await seed('blocks/alice/bob', true);
  await assertFails(set(ref(alice, 'friendRequests/request3'), {
    id: 'request3', fromUid: 'alice', toUid: 'bob', status: 'pending', createdAt: now
  }));
});

test('incoming requests and conversation indexes are private and server-managed', async () => {
  await seed('incomingRequests/alice/request1', { id: 'request1', fromUid: 'bob', toUid: 'alice', status: 'pending' });
  await seed('userConversations/alice/conv1', { otherUid: 'bob', unreadCount: 1 });
  await seed('friendships/alice/bob', true);
  await seed('blocks/alice/bob', true);
  await seed('conversations/conv1/members', { alice: { peerUid: 'bob' }, bob: { peerUid: 'alice' } });
  await assertSucceeds(get(ref(auth('alice'), 'incomingRequests/alice')));
  await assertFails(get(ref(auth('bob'), 'incomingRequests/alice')));
  await assertSucceeds(get(ref(auth('alice'), 'userConversations/alice')));
  await assertFails(get(ref(auth('bob'), 'userConversations/alice')));
  await assertFails(set(ref(auth('alice'), 'userConversations/alice/conv2'), { otherUid: 'eve' }));
  await assertFails(set(ref(auth('alice'), 'incomingRequests/alice/request2'), { id: 'request2' }));
  await assertSucceeds(get(ref(auth('alice'), 'friendships/alice')));
  await assertFails(set(ref(auth('alice'), 'friendships/alice/eve'), true));
  await assertSucceeds(get(ref(auth('alice'), 'blocks/alice')));
  await assertFails(set(ref(auth('alice'), 'blocks/alice/eve'), true));
  await assertSucceeds(get(ref(auth('alice'), 'conversations/conv1/members')));
  await assertFails(set(ref(auth('alice'), 'conversations/conv1/members/eve'), { peerUid: 'alice' }));
});

test('FCM tokens can only be registered or removed by their owner', async () => {
  await assertSucceeds(set(ref(auth('alice'), 'userTokens/alice/token_1'), true));
  await assertFails(set(ref(auth('bob'), 'userTokens/alice/token_2'), true));
  await assertFails(set(ref(auth('alice'), 'userTokens/alice/token_3'), false));
});

test('reports accept only valid open reports from the authenticated reporter', async () => {
  const alice = auth('alice');
  await assertSucceeds(set(ref(alice, 'reports/report1'), {
    id: 'report1', reporterId: 'alice', targetType: 'comment', targetId: 'c1',
    reason: 'spam content', createdAt: Date.now(), status: 'open'
  }));
  await assertFails(set(ref(alice, 'reports/report2'), {
    id: 'report2', reporterId: 'bob', targetType: 'comment', targetId: 'c1',
    reason: 'spam content', createdAt: Date.now(), status: 'open'
  }));
  await assertFails(set(ref(alice, 'reports/report3'), {
    id: 'report3', reporterId: 'alice', targetType: 'comment', targetId: 'c1',
    reason: 'spam content', createdAt: Date.now(), status: 'closed'
  }));
});

test('chat messages require mutual friendship, membership, and a valid server-time timestamp', async () => {
  await seed('friendships/alice/bob', true);
  await seed('friendships/bob/alice', true);
  await seed('conversations/alice_bob/members', {
    alice: { peerUid: 'bob' }, bob: { peerUid: 'alice' }
  });
  await seed('conversations/alice_eve/members', {
    alice: { peerUid: 'eve' }, eve: { peerUid: 'alice' }
  });
  const alice = auth('alice');
  await assertSucceeds(get(ref(alice, 'conversations/alice_bob/messages')));
  await assertSucceeds(set(ref(alice, 'conversations/alice_bob/messages/m1'), {
    id: 'm1', senderId: 'alice', type: 'text', text: 'hello', createdAt: Date.now()
  }));
  await assertFails(set(ref(alice, 'conversations/alice_eve/messages/m2'), {
    id: 'm2', senderId: 'alice', type: 'text', text: 'hello', createdAt: Date.now()
  }));
  await assertFails(set(ref(alice, 'conversations/alice_bob/messages/m3'), {
    id: 'm3', senderId: 'alice', type: 'text', text: 'future', createdAt: Date.now() + 120000
  }));
  await seed('blocks/alice/bob', true);
  await assertFails(set(ref(alice, 'conversations/alice_bob/messages/m4'), {
    id: 'm4', senderId: 'alice', type: 'text', text: 'blocked', createdAt: Date.now()
  }));
});
