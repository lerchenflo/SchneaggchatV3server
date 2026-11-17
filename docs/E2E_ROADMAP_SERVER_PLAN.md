# E2E Roadmap Plan (server)

Server side of the road to end-to-end encryption. Written 2026-11-12 from the client repo on the user's request (user, 2026-11-12: "also write the plan for the server side and move it into the server side folder"). All code references verified that day on branch `beta` at `3b7b349`.

Client side and the shared design: `SchneaggchatV3/plans/E2E_ROADMAP_PLAN.md`. It replaces the old client plans `CHAT_BASED_MIGRATION_PLAN.md` and `E2E_ENCRYPTION_PLAN_V2.md` (deleted, in the client's git history at `ca7d0ee7`).

Paths: `S/` = `src/main/kotlin/com/lerchenflo/schneaggchatv3server/`.

---

## 0. Order (fixed)

| Stage | What | Server work |
|---|---|---|
| 1a-1c, 2a | Client only: stored sync cursor, one inbound handler, one notifier, `chatId` inside the client on today's wire format | **none** |
| 2b | Chat-based addressing: `chatId` replaces `receiverId` + `groupMessage` | sections 1-4 of this file. Same release as client phase 2b. Runs on the test server with a migrated copy of production first; the client is built against that. |
| 3 | End-to-end encryption | section 5 (Phase 0) first and alone; later phases are client work |

Rules for the agent that executes this:

1. Stage 2 server and client phase 2b ship together. No backwards compatibility, no dual-format endpoints. Old app versions are not supported (user, 2026-11-05).
2. Sections 1.2-1.5 describe what is needed. The package, class and service layout is a suggestion; decide it with the server skills (`spring-boot-architecture`, the project skills in `.claude/skills/`). The **field names and endpoint paths in section 2 are the contract with the client**: if one changes, tell the client (`SendMessage` to a live `SchneaggchatV3` session, else an entry in `SchneaggchatV3/plans/Todo.md`).
3. Line numbers are from 2026-11-12. If one does not match, find the place by the function name. Never guess; if the code does not look like this file says, stop and ask the user.
4. When stage 2 is built, reply to the client with the contract as built (endpoints, field names, changed names).
5. Run `spring-endpoint-security-check` on every new or changed endpoint, and `schneaggchat-flow-stability` on send, sync and backfill.
6. Keep the big release small (review 2026-11-13): everything in section 1.5 that does not need the new data model ships before stage 2b as its own small release (list below the table there).
7. Extend the Docker API test (commit `3b7b349`) to the contract of section 2 before the client starts phase 2b; it is the proof that the contract is built as written.

---

## 1. Stage 2: chat-based addressing

### 1.1 Current state (verified 2026-11-12, branch `beta`)

- `S/message/messagemodel/Message.kt`: `senderId` (`:44`, the group id for group SYSTEM messages), `receiverId` (`:46`), `groupMessage` (`:47`), `version` (`:69`, bumped by every change). Indexes `senderId_version_idx`, `receiverId_version_idx`, unique partial `sender_client_message_idx` (`:16-31`).
- Requests `S/message/messagemodel/MessageRequest.kt:6-82` and `MessageResponse.kt:3-30` carry `receiverId` + `groupMessage`.
- Access checks: `MessageService.canUserAccessMessage` (`S/message/MessageService.kt:882-902` per message, `:911-929` for a send): group membership, or friendship for direct messages.
- Sync: `MessageLookupService.getMessagesSince` (`S/message/MessageLookupService.kt:90-133`): visibility = direct messages I sent or received, or group messages of my groups; plus `fullHistoryGroups` (`:92`, `:115-121`) that replays the whole history of a group joined after the cursor. `MessageService.messageSync` (`:856-877`).
- Groups: `S/group/` (`GroupService`, `GroupLookupService`, `GroupController` under `/groups`, `model/Group.kt`, `model/GroupMember.kt`). Group extensions in `S/group/groupextensions/`, keyed by `ownerType = GROUP` + `ownerId`.
- Friendship: `S/user/friends/FriendsService.kt`: `acceptFriendRequest` (`:115`), `declineFriendRequest` (`:160`), `removeFriend` (`:183`), `blockUser` (`:197`), `unblockUser` (`:217`), `deleteFriendship` (`:230`, hard delete).
- `/users/sync`: `UserService.userIdSync` (`S/user/UserService.kt:101`) covers only users with a friendship row (`FriendsLookupService.getAllInteractions`, `S/user/friends/FriendsLookupService.kt:68`).
- Delivery (new on `beta`): `NotificationService.notifyMessageUpdate` (`S/notifications/NotificationService.kt:64-91`) builds one `Delivery` per recipient through `DeliveryService.sendAll`; a new message is a `Delivery.Alert` with `awaitAck = true` (push only if the client does not ack, `PendingPushRegistry`). Push model `MessageNotificationResponse` (`S/notifications/firebase/model/NotificationResponse.kt:28-46`) has `receiverId`, `groupMessage`, `groupName`, `groupId` and plaintext `content` (`NotificationService.kt:99`). `notifySystemMessage` (`:133-149`), `notifyGroupUpdate` (`:264`).
- Socket messages: `S/notifications/websocket/model/SocketConnectionMessage.kt:22-37` (`groupchange` at `:24`, `:45`).
- System messages: `S/message/system/SystemMessageService.kt`: `groupEvent` (`:49`), `friendshipAccepted` (`:93`), `wakeSentToUser` (`:115`), `wakeSentToGroup` (`:138`), `emit` (`:147`).
- Startup migrations run on `ApplicationReadyEvent` (`S/core/MainController.kt:52`), which is after the web server accepts requests.

### 1.2 Target collections

```
chats
  _id                ObjectId   GROUP: the old group id, unchanged. DIRECT: new id.
  type               DIRECT | GROUP
  pairKey            String?    DIRECT only: "<smallerHex>:<largerHex>" (lowercase hex, same order as
                                Friendship.userId1/2). Unique partial index.
  name, description, profilePicUpdatedAt, expiresAt, creatorId     GROUP only (nullable)
  createdAt, updatedAt, deleted
  membershipVersion  Long       +1 on every member-set change (join, leave, remove, account deletion)
                                and on a DIRECT send-permission change (unfriend, block, re-accept)
  historyVisibility  FULL | SINCE_JOIN   new groups SINCE_JOIN, migrated groups FULL

chatmembers                     replaces groupmembers; unique {chatId, userId}, index {userId}
  chatId, userId, joinedAt, joinedAtVersion
  role               MEMBER | ADMIN      DIRECT: always MEMBER
  color              Int                 GROUP only
  muted              Boolean             private: only returned to the member itself

messages
  _id, chatId (ObjectId), senderId (ObjectId?, null = SYSTEM message of the chat),
  createdVersion (Long, immutable, = version at insert),
  msgType, answerId, content, poll, systemEvent, sendDate, lastChanged, deleted, edited,
  version, readers, reactions, clientMessageId
  removed: receiverId, groupMessage
```

Indexes on `messages`: `{chatId: 1, version: 1}` (sync), `{chatId: 1, createdVersion: 1}` (backfill and visibility), and `sender_client_message_idx` kept byte-identical in name and definition (a changed definition under the same name aborts the startup with an index conflict).

Fixed rules:
- GROUP chat id = old group id. Events, group pictures and group extensions keep their keys. Group extensions are not re-keyed (user, 2026-11-12): `ownerType = GROUP`, `ownerId` = chat id.
- DIRECT chats are created only by the server, idempotent by `pairKey`, at friendship accept and by the migration. A pair of the same user is rejected.
- All SYSTEM messages (group and direct) get `senderId = null`; the actor stays in `systemEvent.actorId`.
- `chatId` is an `ObjectId` everywhere in Mongo, never a hex string.
- A DIRECT chat outlives the friendship. Removed / declined / blocked: the chat and both members stay, sending and mutating is refused, `membershipVersion` is bumped, both sides are told. Re-accept reuses the same chat.

### 1.3 What the server must do

**Chats (absorbs `S/group/`)**
- One lookup for membership that also checks `chat.deleted` (today `GroupLookupService.isUserInGroup`, `S/group/GroupLookupService.kt:83-86`, does not).
- `canSend(userId, chatId)` = member and (GROUP, or the friendship is ACCEPTED).
- Everything `GroupService` does today (create, name, description, picture, expiry, `performUserAction` `:363`, `leaveAllGroups` `:580`, delete `:668`, `deleteExpiredGroups` `:718`) works on chats; every member-set change bumps `membershipVersion` and `updatedAt`.
- `/chats/sync`: same id + timestamp diff as `/groups/sync` (`GroupService.syncGroups`, `:164`), over all my chats including DIRECT. It answers three lists: changed chats, chats I left or was removed from (the client keeps them read-only), deleted chats (the client wipes them).
- `/users/sync` also returns every user who shares a chat with me, in the simple user shape (name, picture timestamp; later the key fields of section 5). Without this, co-members of event groups and ex-friends have no name on the client and no reachable keys.
- Friendship accept: get or create the DIRECT chat, tell both sides about the chat **first**, then write the "friendship accepted" system message into it. Remove / block / unblock: bump `membershipVersion`, tell both sides (today `removeFriend` only sends the relation change, `FriendsService.kt:183-192`).
- New per-member settings: mute (private) and, admins only, `historyVisibility`.

**Messages**
- Every request takes `chatId` instead of `receiverId` + `groupMessage` (`MessageController.kt:22-130`, `MessageRequest.kt`).
- Access rule replaces `canUserAccessMessage`:
  - send: `canSend(sender, chatId)`;
  - read (media fetch, sync, backfill): member and visible-to-member;
  - mutate (react, vote, edit, delete, set read): `canSend` and visible-to-member;
  - **visible-to-member** = `historyVisibility == FULL` or `message.createdVersion > member.joinedAtVersion`. Use the immutable `createdVersion`, never `version` (a reaction on an old message would leak it).
- Send (`MessageService.sendMessage`, `:114`): set `createdVersion = version`; check that `answerId` is a message of the same chat and visible to the sender.
- Set read (`setMessagesRead`, `:741`): by `chatId`; one socket frame for the whole batch instead of one `notifyMessageUpdate` per message (`:824`).
- System messages: every event by `chatId`, `senderId = null`.
- Account deletion (`UserService.kt:620-628`): leave every chat through the chat service (admin promotion, empty-group deletion, notifications, `membershipVersion`); delete all friendships of the user, not only accepted ones; messages become tombstones (soft delete + version bump) so other clients learn about it.

**Sync and backfill**
- `getMessagesSince`: `chatId IN myChats AND version > since AND version <= watermark AND visible-to-member`. Remove `fullHistoryGroups` from the stream (flaw F10 below).
- New backfill call: older messages of one chat, newest first by `createdVersion`, paged with `beforeVersion` and `limit`, filtered by visible-to-member. The client calls it when a chat appears for the first time or after a rejoin.
- A removed member gets no sync, no backfill and no access by id.

**Notifications** (on the `beta` delivery code)
- `notifyMessageUpdate` (`NotificationService.kt:64-91`): one path over the members of `chatId` instead of the group / direct branch (`:65-69`). Also deliver a change to the changing user's **other** devices (today they are skipped: `:66`, `:68`).
- `notifySystemMessage` (`:133-149`), `groupNameOf` (`:120`), `reactionNotification` (`:107-118`), `notifyWake` (`:393`): chat-based.
- Push model: drop `receiverId`, `groupMessage`, `groupName`, `groupId`; add `chatId`, `chatType`, `chatName` (empty for DIRECT). Same for the wake push. Keep `_class`.
- Do not push to a member who muted the chat.
- Socket: `groupchange` becomes `chatchange` carrying the chat response; `EventResponse.groupId` becomes `chatId`.

**Other places that carry a group or receiver id**
| Area | Change |
|---|---|
| Events (`S/events/`) | `Event.groupId` -> `chatId` (same value). Event join adds non-friends to a GROUP chat: keep that as the one exception to "only friends can be added". |
| Wake (`S/user/wake/`) | request by `chatId`; for a DIRECT chat the target is the partner. |
| Recap (`S/user/recap/RecapService.kt`) | group by `chatId` and chat type. |
| Media (`S/util/ImageManager.kt`, `AudioManager.kt`) | new files named by message id only; reads try the new name first, then both old names. No rename migration. |
| Pinned chats (`S/user/usermodel/PersonalUserSettings.kt:22, :43`, `UserController.kt:235`) | `PinnedChat(chatId, pinTimePoint)`; reject entries that are not chats of the user. |
| Group extensions (`S/group/groupextensions/`) | keys unchanged; membership checks go through the chat lookup; deleting a chat deletes its extensions as today. |
| `S/core/MainController.kt` | remove or rewrite every startup helper that reads `Group::class` or the old `Message` shape; they cannot map documents in the new shape. |
| Skills and docs | `.claude/skills/*` references, `docs/domain-model.html`, `README.md`, `SECURITY_AUDIT.md` after the build. |

### 1.4 Data migration (one shot, before serving)

Execution rules:
- It must run **before** the web server accepts requests. `ApplicationReadyEvent` (`MainController.kt:52`) is too late: a new client could sync in the middle, move its cursor past messages that have no `chatId` yet and never get them. Run it in a bean that finishes during context start, or answer every non-health request with 503 until the marker exists.
- Raw `Document`s only (`mongoTemplate.getCollection(...)`); the Kotlin models are already in the new shape.
- Index pre-step: `auto-index-creation` builds indexes during context start, before the migration. Drop `senderId_version_idx`, `receiverId_version_idx` and the single `receiverId` index first. Keep `sender_client_message_idx` identical.
- Idempotent and resumable: marker document `migrations{_id: "chat_based_v1", step}`; every step uses upserts and `chatId: {$exists: false}` guards.
- Dry run on a `mongodump` copy first; log counts and the run time per step (the run time is the length of the maintenance window); backup before the deploy.
- **Additive** (review 2026-11-13): the migration only adds fields and collections. It does not remove `receiverId` / `groupMessage` from messages and does not overwrite the old sender of SYSTEM messages without keeping it (`legacySenderId`). A cleanup release, after stage 2b has run in production for some weeks, removes the old fields together with `groups` / `groupmembers`.
- **Verify before the marker is written**: every message has a `chatId` that exists in `chats`; message count before = after + logged orphans; every group has a chat with the same member count; every ACCEPTED friendship has exactly one DIRECT chat with two members. If a check fails: no marker, the server does not serve, the log names the check.
- **Way back**: the backup taken right before the deploy. Until the new clients are released (client plan section 3.8, step 3) restoring it loses nothing of value: old clients cannot write to the new server, only testers did. After that step there is no way back without losing what was sent since. Because the migration is additive, a wrong step can also be repaired in place (the old fields are still there to compute from).

Steps:
1. Groups -> chats: upsert `chats{_id: group._id, type: GROUP, name, description, profilePicUpdatedAt, expiresAt, creatorId, deleted, createdAt, updatedAt, membershipVersion: 0, historyVisibility: FULL}`. Check `S/group/model/Group.kt` for fields added since this plan was written and carry every one over.
2. `groupmembers` -> `chatmembers`: upsert by `{chatId, userId}`; `userid` -> `userId`, `admin` -> `role`; keep `joinedAt`, `joinedAtVersion`, `color`.
3. DIRECT chats: pairs = ACCEPTED and BLOCKED friendships, plus every pair found by an aggregate over `messages{groupMessage: false}` on `{min(senderId, receiverId), max(...)}`. Skip pairs of the same user (count and log) and pairs where a user no longer exists. Upsert `chats{type: DIRECT, pairKey}` and two `chatmembers`. PENDING / DECLINED pairs get no chat.
4. Group messages, one pipeline update: `updateMany({groupMessage: true, chatId: {$exists: false}}, [{$set: {chatId: "$receiverId", createdVersion: "$version"}}])`.
5. Direct messages, per pair: `updateMany({senderId: {$in: [a, b]}, receiverId: {$in: [a, b]}, groupMessage: false, chatId: {$exists: false}}, ...)` with the same `$set`.
6. SYSTEM sender: `updateMany({msgType: "SYSTEM", chatId: {$exists: true}}, [{$set: {legacySenderId: "$senderId", senderId: null}}])` (only where `legacySenderId` does not exist yet).
7. Orphans: group messages whose group is missing, direct messages of skipped pairs (incl. self-messages): count, log, delete. Messages and media of groups already marked deleted are purged too.
8. Pinned chats, per user: entries that still have a `group` field: `group == false` -> look up the DIRECT chat by `pairKey(self, chatId)`, drop if none; `group == true` -> keep if the chat exists and the user is a member. Bump `user.updatedAt`.
9. Events: rename `groupId` -> `chatId`.
10. Write the marker `done`. Drop `groups` / `groupmembers` in a later release (they are the rollback source).

### 1.5 Flaws found during the analysis (status re-checked 2026-11-12)

Ship before stage 2b, each as its own small release (they do not need the new model): F12, F15, F16, and F24 as a general "run a migration before serving" runner, proven with a small migration first. F10 is a live bug today (a member who joined a group with more than 400 older messages syncs the same page forever); if stage 2b is more than a few weeks away, give it a stopgap now. Fix the other open ones in the stage 2b release.

| # | Flaw | Status |
|---|---|---|
| F1 | Edit had no sender check and no deleted check | **Fixed** (`MessageService.kt:581-587`) |
| F2 | New group members can fetch pre-join messages and media by id | Open -> visible-to-member rule |
| F3 | Unfriended / blocked users keep full mutation access to direct messages | Open -> `canSend` for mutations |
| F4 | `answerId` is not checked against the chat (`MessageService.kt:114`, `:222`) | Open |
| F5 | Adding a user to a group: friendship and user-exists check | Create checks friendship (`GroupService.kt:77`); `ADD_USER` (`:393`) **re-check** |
| F6 | Any member may change group name / description / picture | Kept on purpose. Only the `historyVisibility` toggle is admin-only |
| F7 | A friend can overwrite another user's global description (`UserService.changeUserProfile`) | **Re-check**, separate fix |
| F8 | Account deletion: hard-deleted messages, only accepted friendships deleted | Leaving groups now promotes an admin (`GroupService.kt:580, :601`); rest **re-check** (`UserService.kt:620-628`, `MessageLookupService.kt:135`) |
| F9 | Group deletion kept messages and media | Mostly fixed 2026-10-26 (`MessageLookupService.deleteAllGroupMessages`, `:159`); legacy media names and orphans are covered by migration step 7 |
| F10 | Endless sync loop: a group joined after the cursor with more than 400 older messages returns the same page forever (`MessageLookupService.kt:92, :115-121`) | Open -> backfill |
| F11 | Direct socket update was built for the wrong user | **Fixed** (`NotificationService.kt:79`, per-recipient response) |
| F12 | The changing user's other devices get no socket update | Open, needs the client: server `Todo.md` "Needs client changes" (`NotificationService.kt:66, :68`) |
| F13 | `deleteFriendship` deletes the friendship setting by the friendship id (`FriendsService.kt:230-234`, `UserService.kt:620`) | **Re-check** what `deleteFriendshipSettingById` expects |
| F14 | Friend-request push set `requesterId` to the receiver | Looks fixed (`NotificationService.kt:460`); **re-check** `:215` |
| F15 | Membership check ignores `deleted` (`GroupLookupService.kt:83-86`) | **Fixed** 2026-11-16 (`GroupLookupService.isUserInGroup` also needs a non-deleted group) |
| F16 | Read receipts send one socket frame per message (`MessageService.kt:824`) | Open, needs the client: server `Todo.md` "Needs client changes" |
| F19 | Push payload over 4 KB | FCM handled (`FirebaseService.kt:65, :191`); **check APNs** |
| F20 | `VersionCounterService.safeWatermark` works for one server instance only | Note, no fix now |
| F23 | Server reads message content: recap stats, image re-encoding | Text-only E2E touches only the recap (section 5) |
| F24 | Startup migrations run after the server accepts traffic (`MainController.kt:52`) | Not a problem (user, 2026-11-16): no runner, one-shot migrations run in `MainController.onStartup` and are removed once they ran on production (server `Todo.md`) |
| F25 | `/users/sync` misses non-friend co-members (`UserService.kt:101-108`) | Open |

---

## 2. Wire contract with the client (proposal; reply with what was built)

| Call | Fields |
|---|---|
| Send text / image / audio / poll | `chatId` instead of `receiverId` + `groupMessage`; everything else unchanged (`messageId`, `msgType`, `content`, `answerId`, `clientMessageId`, `poll`) |
| `MessageResponse` | `messageId, chatId, senderId (nullable), createdVersion, msgType, content, pollResponse, systemEventResponse, answerId, sendDate, lastChanged, deleted, edited, version, readers, reactions, clientMessageId` |
| `POST /messages/setread` | `chatId`, `timestamp` |
| `POST /chats/sync` | request: list of `{id, timeStamp}`; response: `updatedChats`, `leftChats` (ids), `deletedChats` (ids) |
| Chat response | `id, type, name?, description?, updatedAt, profilePicUpdatedAt, createdAt, creatorId?, expiresAt?, membershipVersion, historyVisibility, ownMuted, members: [{userId, joinedAt, role, color}]` (no names: the client resolves user ids through `/users/lookup`) |
| `/chats/create`, `/chats/{id}/profilepic`, `/chats/{id}/setname`, `setdescription`, `setprofilepic`, `setexpiry`, `setmuted`, `sethistoryvisibility`, `/chats/changemembers`, `/chats/delete` | replace `/groups/*` |
| `GET /chats/{id}/messages?beforeVersion=&limit=` | list of `MessageResponse`, newest first by `createdVersion` |
| `/users/sync` | plus users sharing a chat with me (simple shape) |
| Wake request | `chatId` |
| `PinnedChat` | `{chatId, pinTimePoint}` |
| Socket | `chatchange` `{chat, deleted}` replaces `groupchange` |
| `EventResponse` | `chatId` instead of `groupId` |
| Push `_class = message` | `msgId, senderName, messageType, chatId, chatType, chatName, content, senderId, reaction, sendDate, answerId` |
| Push `_class = wake` | `chatId`, `chatName` instead of `groupId`, `groupName` |

---

## 3. Release

1. Backup. Dry-run the migration on a copy.
2. The client submits Android and iOS and holds both after approval (Play managed publishing, App Store manual release). Then: backup, deploy (the migration runs before serving), release both store versions and the desktop build at once. Details and the old-client check: client plan section 3.8.
3. Smoke test with the new client build: sync on a fresh install, send in a DIRECT and a GROUP chat, join a group with a long history (backfill), unfriend and re-friend (same chat comes back), leave a group (the chat disappears on the client; the read-only view comes with client phase 2c).

## 4. Decisions that bind the server (user)

- DIRECT chat with a non-friend: hidden on the client, sending refused by the server (2026-10-10).
- New groups `SINCE_JOIN`, migrated groups `FULL`, toggle admin-only (2026-10-10).
- Group metadata: any member (2026-10-10).
- No DM to group conversion (2026-10-10).
- No min-version gate (2026-10-10); old versions unsupported (2026-11-05).
- Mute per chat member, private, server skips the push (2026-10-10).
- Group deletion deletes messages and media (2026-10-10).
- Self-DM legacy messages are deleted as orphans (2026-10-10).
- Group extensions keep `ownerType` + `ownerId` (2026-11-12).
- E2E keys per account, not per device, although a device registry exists now (2026-11-12).

---

## 5. Stage 3, Phase 0: fields, key endpoints, share store, backup store

Deployable alone and inert (no client uses it until client Phase 1). Do it only after stage 2 is live. The protocol itself (ratchet, key shares, backup, safety numbers) is in the client plan, section 4.3; the server only stores opaque strings.

What is needed:

1. Messages: `encryptionVersion: Int = 0` on the message document, on every send / edit request and on `MessageResponse`. `membershipVersion: Long?` on send requests. Encrypted text content is longer than plaintext: allow 56 000 characters when `encryptionVersion == 2` (10 000 characters of plaintext fit).
2. Stale-membership guard: an encrypted send whose `membershipVersion` differs from `chat.membershipVersion` is answered with `409 Conflict`. The client syncs the chat, rotates its session and sends again. This closes the race "encrypted for a member who was removed a second ago".
3. Per user, public key material (opaque strings): `identityPub`, `spkPub`, `spkSig`, `spkId: Int`, `updatedAt`, `backupInvalidatedAt`.
4. Key shares: `{chatId, senderId, recipientId, sessionId, blob, createdAt}`, unique per `(recipientId, sessionId, senderId)`, auto-expire after about 90 days.
5. Key backup per user: `wrappedBackupKey`, `kdfSalt`, `kdfIterations`; and backup entries `{userId, entryId, blob, createdAt}`, unique per `(userId, entryId)` so a re-upload overwrites.
6. Endpoints, all authenticated and limited to the caller's own data:
   - `POST /keys/publish`: store identity key + signed prekey; clears `backupInvalidatedAt`; bumps `user.updatedAt` so the keys travel through `/users/sync`. Publishing after an invalidation also deletes the user's stored key shares and backup entries (they are unusable).
   - `POST /keys/shares`: batch `[{chatId, recipientId, sessionId, blob}]`. **One rule**: sender and recipient are both members of `chatId`. No friendship check.
   - `GET /keys/shares`: my waiting shares. `POST /keys/shares/ack`: delete the ones I picked up.
   - `POST /keys/backup` and `GET /keys/backup` (wrapped key + KDF parameters); `POST /keys/backup/entries` (batch upsert) and `GET /keys/backup/entries` (paged).
7. Every user response shape, including the simple shape for non-friend co-members, carries `identityPub`, `spkPub`, `spkSig`, `spkId` (all nullable) (`UserService.serializeSyncUser`, `S/user/UserService.kt:570`, and the places in `NotificationService` that build user responses).
8. New outbound socket message `keysharewaiting`: a hint without payload, sent to the recipients of new shares. The pull endpoint stays the source of truth.
9. Push for `encryptionVersion == 2`: put the opaque content into `content` only if the payload stays under 4 KB; otherwise send `content = ""` and `needsFetch = true`. Add `encryptionVersion` to the payload. Never send a plaintext preview of an encrypted message.
10. Recap (`S/user/recap/RecapService.kt`): leave messages with `encryptionVersion != 0` out of every content statistic (longest message, word and character counts, most-reacted content).
11. Email password reset (`EmailService.resetPassword`): set `backupInvalidatedAt = now`. The password change request gets optional fields for the re-wrapped backup key (the client sends them together with the change).
12. Edits: an encrypted message is edited with an encrypted content; the server keeps `encryptionVersion` as sent.
13. **Login key instead of the password** (review 2026-11-13; without it the server could open the key backup at every login, because it receives the password; client plan section 4.3 "Login secret"). Needed: per user `kdfSalt` and a marker which login scheme the account uses; a call that returns the `kdfSalt` for a user name before login (a stable fake value for unknown names, rate limited like the login); login, signup and password change accept `loginKey` (the server stores bcrypt of it, as it does for the password today); old accounts switch at their next login, where the client sends the password once more plus `kdfSalt` and `loginKey`; the reset form `reset_password_form.html` computes both in the browser. After an account has switched, app versions older than the login-key release cannot log in to it. Other pages that take a password (`chefdev.html`) need the same or stay on a separate credential.
14. Key upload is "store only if the user has no keys" (answer `409` otherwise): two devices of one account that log in at the same time must not both create an identity. The same for the first backup key. Replacing existing keys needs an explicit `replace: true` in the request; the client sends it only from its "generate new keys" dialog (client plan P3.3).
15. `chats.encrypted` (returned in the chat response): set when the first message with `encryptionVersion == 2` is stored in a chat; from then on a TEXT send or edit with `encryptionVersion == 0` in that chat is refused. This catches client bugs; it is not a protection against the server itself.
16. Limits on every `/keys/*` body: size of one blob, entries per batch, stored shares per recipient, backup entries per user, and the normal rate limit.

What the server still sees after E2E: who talks to whom, timestamps, message types, chat membership, read receipts, reactions, polls, media and image captions, content length, push tokens. The server controls chat membership; adding a member is visible to users as a system message.

Contract names for the client (reply with what was built): the endpoint paths and field names above, the `409` on a stale `membershipVersion`, socket type `keysharewaiting`, push fields `encryptionVersion` and `needsFetch`.
