import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import test, { before, after } from 'node:test';
import { initializeTestEnvironment, assertFails, assertSucceeds } from '@firebase/rules-unit-testing';
import { get, ref, set, update } from 'firebase/database';

let env;
before(async () => {
  env = await initializeTestEnvironment({
    projectId: 'demo-animego-rules',
    database: {
      rules: readFileSync(resolve('database/database.rules.json'), 'utf8'),
      host: '127.0.0.1',
      port: 9000
    }
  });
});
after(async () => { await env?.cleanup(); });

test('public comments are readable without login, but guest cannot post', async () => {
  await env.withSecurityRulesDisabled(async (ctx) => {
    await set(ref(ctx.database(), 'comments/demo/c1'), {
      id: 'c1', animeSlug: 'demo', authorId: 'user1', authorName: 'test',
      text: 'hello', parentId: '', createdAt: Date.now(), likeCount: 0
    });
  });
  const guest = env.authenticatedContext('guest1', { firebase: { sign_in_provider: 'anonymous' } }).database();
  const publicDb = env.unauthenticatedContext().database();
  await assertSucceeds(get(ref(publicDb, 'comments/demo')));
  await assertFails(set(ref(guest, 'comments/demo/c2'), {
    id: 'c2', animeSlug: 'demo', authorId: 'guest1', authorName: 'guest',
    text: 'guest comment', parentId: '', createdAt: Date.now(), likeCount: 0
  }));
});

test('chat write requires a mutual friendship and an unblocked member', async () => {
  await env.withSecurityRulesDisabled(async (ctx) => {
    await set(ref(ctx.database(), 'friendships/alice/bob'), true);
    await set(ref(ctx.database(), 'friendships/bob/alice'), true);
    await set(ref(ctx.database(), 'conversations/alice_bob/members'), {
      alice: { peerUid: 'bob' }, bob: { peerUid: 'alice' }
    });
    await set(ref(ctx.database(), 'conversations/alice_eve/members'), {
      alice: { peerUid: 'eve' }, eve: { peerUid: 'alice' }
    });
  });
  const alice = env.authenticatedContext('alice', { firebase: { sign_in_provider: 'password' } }).database();
  await assertSucceeds(set(ref(alice, 'conversations/alice_bob/messages/m1'), {
    id: 'm1', senderId: 'alice', type: 'text', text: 'hello', createdAt: Date.now()
  }));
  await assertFails(set(ref(alice, 'conversations/alice_eve/messages/m2'), {
    id: 'm2', senderId: 'alice', type: 'text', text: 'hello', createdAt: Date.now()
  }));
});


test('new authenticated account can create its private/public profile and reserve username', async () => {
  const uid = 'new-user-1';
  const db = env.authenticatedContext(uid, { firebase: { sign_in_provider: 'password' } }).database();
  const now = Date.now();
  await assertSucceeds(update(ref(db), {
    [`users/${uid}/uid`]: uid,
    [`users/${uid}/displayName`]: 'AnimeGo User',
    [`users/${uid}/username`]: `user_${uid.slice(0, 8)}`,
    [`users/${uid}/createdAt`]: now,
    [`users/${uid}/updatedAt`]: now,
    [`publicProfiles/${uid}/uid`]: uid,
    [`publicProfiles/${uid}/displayName`]: 'AnimeGo User',
    [`publicProfiles/${uid}/username`]: `user_${uid.slice(0, 8)}`,
    [`publicProfiles/${uid}/createdAt`]: now,
    [`usernames/user_${uid.slice(0, 8)}`]: uid
  }));
  await assertSucceeds(get(ref(db, `users/${uid}`)));
});
