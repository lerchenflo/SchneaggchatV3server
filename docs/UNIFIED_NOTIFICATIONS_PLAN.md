# Unified notification delivery - plan

Status: plan, not started (2026-10-20). Written on branch `fcm-ios-token-cleanup`.

## Scope and timing

- Target: the release **after** the current one. Nothing of this goes into the current release.
- All work happens on its own branch (e.g. `unified-notifications`), branched from `main` once the current release is out - not on `fcm-ios-token-cleanup` or any other feature branch.
- The phases below are steps inside that branch. The branch is merged into `main` (and so reaches prod, see `server_docker/Dockerfile`) for the release after the current one.
- **Server and client are released together. Older app versions are not supported** (the app forces an update on start when a new version is out): no compatibility layers, no capability flags, wire formats may change freely. See [Release](#release).

## Goal

One delivery path for everything the server tells a user. For each recipient account:

- every device with a live socket gets the update over the socket,
- every device without a live socket (or whose socket did not confirm the update) gets an FCM / APNs push,
- decided **per device**, not per account as today.

## Decisions

| # | Question | Decision |
|---|---|---|
| D1 | Another device of the same account is connected (desktop open). Does the phone without a socket get a push? | **Always push.** Strict per device: every device without a confirmed live socket gets the push right away, whatever the other devices do. No delay, no "read elsewhere" suppression, no silent pushes. |
| D2 | Do the acting user's *other* devices get live updates of their own actions (message sent on the phone shows up on the desktop)? | **Yes, socket only.** Every live session of the actor except the acting one gets the socket change. The actor never gets a push for their own action. |
| D3 | Ack (delivery confirmation) only for new messages, or for every push-worthy socket event? | **Generic ack.** Every push-worthy socket event carries a `deliveryId`, the client acks it, the push for that device is only sent when no ack arrives. Replaces `MessageAck`. |
| D4 | How does the server know which push token and which socket belong to the same device? | **Open - decide before phase 3.** Options in [Device identity](#device-identity-open-decision-d4). Phases 1 and 2 do not depend on it. |
| D5 | Backward compatibility with released app versions? | **None.** Server and client ship together in one release, and the app forces the update on start. |

---

## Current state (analysis)

### Where notifications go out

All realtime/push traffic goes through `notifications/NotificationService.kt`, with three exceptions:

- `user/wake/WakeService.kt` calls `FirebaseService.sendWakeToUser` and `FirebaseService.getTokensForUser` directly (breaks the "only `NotificationService` talks to the senders" rule).
- `website/admin/*` reads `SocketConnectionHandler` directly (presence only, no delivery - fine; the admin panel itself uses SSE, not `/ws`, so every `/ws` session is an app client).
- `UserController` / `AuthService` save and delete tokens directly on `FirebaseService` / `ApnsService` (token management, not delivery - fine).

| `NotificationService` method | Socket | Push | Fallback rule today |
|---|---|---|---|
| `notifyMessageUpdate` (new message) | `MessageChange` to every participant **except the actor** | FCM + APNs `message` | Per **user**: push to all of the user's devices if the user has no session; if any session acks (`messageack`) or a non-acking session takes the write, **no device** gets a push. |
| `notifyMessageUpdate` (edit, delete, read, poll vote, reaction) | `MessageChange`, actor excluded | none | - |
| `notifyReactionAdded` | (rides on the `MessageChange` above) | FCM + APNs `message` with `reaction=true` | Push if the message sender was not in the `MessageChange` write set. Socket write = delivered, no ack. |
| `notifySystemMessage` | `MessageChange` to all incl. actor | none | - |
| `notifyUserUpdate`, `notifyRelationChange` | `UserChange` | none | - |
| `notifyFriendRequest` | `FriendRequest` | FCM + APNs `friend_request` | Per user: push only if no session took the write. No ack. |
| `notifyGroupUpdate` | `GroupChange` | none | - |
| `notifyEventUpdate` | `EventChange` to audience + creator | FCM + APNs `event` (new events only, not to creator) | Per user: push only if no session took the write. No ack. |
| `notifyBirthday` | none | FCM + APNs `birthday` | Always push, to every device. |
| `notifyMapUpdate` | `MapChange` broadcast to everyone **except the acting user** (all their devices) | none | - |
| Location / online status / snapshots | various | none | - |
| Wake (`WakeService`, direct) | none | FCM only, TTL 0 | Always push. |

### Problems

1. **Fallback is per user, not per device.** `SocketConnectionHandler.sendMessage` / `sendNewMessage` return "the user got it" as soon as one session took it. A user with the desktop app open never gets a push on the phone in their pocket. Two phones: the one in the foreground suppresses the push for the one in the background. This is the main bug the plan fixes.
2. **No link between a socket session and a push token.** `SocketConnection` knows `userId` + `sessionId`; `FirebaseToken` / `ApnsToken` only `userId` + `token`. There is no way to say "this token belongs to the device whose socket just acked". Needed for any per-device decision.
3. **Ack only for new messages.** Reactions, friend requests and new events treat a socket *write* as delivery. A write only means the bytes reached the TCP buffer (see the comment on `PendingPushRegistry`), so pushes get lost when the app is on its way to the background or the peer vanished without a close frame.
4. **Actor's other devices are left out** of `notifyMessageUpdate` (and `notifyMapUpdate`): a message sent on the phone only reaches the desktop at the next sync.
5. **Copy-paste push layer.** `FirebaseService` and `ApnsService` each have `sendNewMessageNotificationToUser`, `sendReactionNotificationToUser`, `sendFriendRequestNotificationToUser`, which only build a `NotificationResponse` and call `sendNotificationToUser`. Same parameter lists twice in `NotificationService` (group branch and 1:1 branch), twice again in each sender. Tokens are read from Mongo twice per send (once to early-return, once inside `sendNotificationToUser`).
6. **Unstructured threading.** Every send starts `CoroutineScope(Dispatchers.IO).launch` and nests another launch inside (`send*ToUser` -> `sendNotificationToUser`). No bound, no shutdown, exceptions only logged. FCM sends one HTTP call per token; APNs blocks on `.get()` per token.
7. **Duplicated payload mapping.** `notificationResponseToDataMap` (Firebase) and `buildPayload` (APNs) both flatten the `NotificationResponse` and both keep their own `type` name `when`.
8. **Ended sessions keep their push tokens.** The server cannot tell which push token belongs to which session, so only `/auth/logout` deletes tokens, and only the one the client sends along (`notificationToken` + `isAndroid`) or all of them (`allDevices`). Every other way a session ends deletes the `refreshTokens` row(s) but leaves the push tokens, so the device keeps getting pushes - with plaintext message content (Gotcha 9 in the project skill) - for an account it is no longer logged into:

   | Session end | Code | Push tokens today |
   |---|---|---|
   | Logout on the device | `AuthService.logout` | Deleted, but only the token the client sends along. A client that lost its token (reinstall, token rotated) leaves the old row behind. |
   | Logout "all devices" | `AuthService.logout(allDevices = true)` | All deleted. |
   | "Log out" of another device in the device list | `AuthService.endSession` (`SessionController`) | **Kept.** Only removed if the ended device later calls `/auth/logout` itself after its refresh fails - never, if it is off, lost, stolen or uninstalled. |
   | Password change | `UserService.changePassword` (deletes all sessions) | **Kept** on every device. |
   | Password reset by email | `UserService.resetPassword` (deletes all sessions) | **Kept** on every device - the typical "my phone was stolen" action does not stop the stolen phone's pushes. |
   | Admin "log out everywhere" | `AdminUserService` (deletes all sessions, closes sockets) | **Kept** on every device. |
   | Session expired | `DatabaseCleanupTask` (`deleteByExpiresAtBefore`) | **Kept** forever. |
   | Account deleted | `UserService.deleteAccount` | **Kept** - token rows of a deleted account stay in the database. |

   On top of that, the credential-less branch of `AuthService.logout` deletes whatever push token the request names (`deleteToken(token)` without an owner check), so anyone who knows a token can unregister it.

### Smaller bugs found while reading

- `NotificationService.notifyMessageUpdate`, 1:1 branch: the `MessageChange` is always built with `toMessageResponse(message.receiverId)`, also when the recipient is the sender (receiver edits / reacts / reads). The sender then gets `clientMessageId = null`. Building the response per recipient fixes it for free.
- `FirebaseService` / `ApnsService.sendFriendRequestNotificationToUser` set `requesterId = receivingUserId` (the receiver, not the requester). Fix it together with whatever the client reads from it.
- `FirebaseService.sendFriendRequestNotificationToUser` logs the exception under `senderId` instead of the receiver.
- `PendingPushRegistry` keys on `(userId, messageId)`: the first ack from any device cancels the push for all devices (this is problem 1 in the ack path).

### Client facts that matter (SchneaggchatV3)

- The client closes its socket when the app goes to the background (`SocketConnectionManager.close`), so a phone in the pocket has no socket and relies on push.
- The client shows a **local notification** for every `MessageChange` with `newMessage && msgType != SYSTEM` it receives over the socket (`SocketConnectionMessage.kt` ~line 233). It does **not** check `senderId == ownId`. Sending the actor's own new message to their other devices (D2) would ring those devices. Needs a client guard.
- Reactions on own messages also create a local notification from the socket (`SocketConnectionMessage.kt` ~line 589, guarded by `senderId != ownId`).
- The client acks with `MessageAck(messageId)` only for `newMessage && !deleted`, and announces it with handshake header `X-Socket-Acks: 1`.
- The client registers its push token on every start (`ChatSelectorViewModel` -> `/users/setnotificationtoken`) and on iOS from `IosPushDelegateBridge`.

---

## Target design

### Concepts

- **Device**: one installation of the app on one phone / desktop. Identified by a `deviceKey` (string). How it is obtained is D4; the rest of the design only needs "a socket session and a push token carry the same `deviceKey`".
- **Session**: one live socket (`SocketConnection`), gets `deviceKey`. Every session acks (all `/ws` clients run the new app), so `acksMessages` and the `X-Socket-Acks` header go away.
- **Push target**: one push token, `PushTarget(platform: FCM | APNS, token, userId, deviceKey: String?)`. Read from both token collections by a small `PushTokenService` (or a method on each sender).
- **Delivery**: one update for one recipient user:

```kotlin
data class Delivery(
    val recipientId: ObjectId,
    val socket: SocketConnectionMessage?,        // null = push only
    val push: NotificationResponse?,             // null = socket only
    val mode: DeliveryMode,
    val pushOptions: PushOptions = PushOptions(), // ttl, platforms (wake = FCM only, TTL 0)
)

enum class DeliveryMode {
    LIVE_ONLY,   // socket to every live session, never a push (data changes, offline devices sync)
    NOTIFY,      // socket to live sessions, push to every device that did not confirm it
    PUSH_ALL,    // push to every device, socket ignored (wake, birthday)
}
```

- **Actor**: `Actor(userId, deviceKey)` - who triggered the change and from which device. Passed by services that act on an HTTP request; `null` for schedulers.

### `DeliveryService` (new, `notifications/DeliveryService.kt`)

The only place that decides socket vs push. `NotificationService` keeps its `notifyX` methods (callers do not change), but each method only works out recipients and payloads and hands a list of `Delivery` objects to `DeliveryService.deliver(...)`. `NotificationService` stops calling `SocketConnectionHandler`, `FirebaseService` and `ApnsService` itself.

Per-device rule for `NOTIFY`:

```
sessions = live sessions of recipient, grouped by deviceKey
targets  = push targets of recipient, grouped by deviceKey

for each device D (in sessions or targets):
    write socket message (with deliveryId) to every session of D
    if at least one write succeeded -> hold push for D's targets in PendingPushRegistry
                                       (released on ack from any session of D,
                                        pushed on timeout or when all of D's sessions close)
    else (no session, or every write failed) -> push to D's targets now

tokens without deviceKey: always pushed (a device that has not re-registered its token since the
release - it registers on every app start, so this only covers devices that have not opened the
new app yet).
```

`LIVE_ONLY` writes to every session and returns. `PUSH_ALL` sends to every target.

Actor handling (D2), applied by `NotificationService` when building the deliveries: the actor's own account gets a `LIVE_ONLY` delivery with the acting device (`actor.deviceKey`) excluded. This covers new messages too - the client guard against notifying own messages ships in the same release.

`deliver` returns a small result (`DeliveryResult`: devices reached by socket, devices pushed, devices pending) for callers that need it - this replaces the `Set<ObjectId>` that `notifyMessageUpdate` returns for `notifyReactionAdded` today, and the device count that `WakeService` shows.

### Generic ack (D3)

Server -> client: push-worthy socket messages carry `deliveryId: String?`:
- `MessageChange` (new message: `deliveryId = messageId`; reaction added, only towards the message sender: a fresh id)
- `FriendRequest`
- `EventChange` (new event)
- future: event reminders (see `Todo.md`), anything else that has a push.

`deliveryId` is only set on the copy sent to the recipients that get a push for it. `LIVE_ONLY` copies have `deliveryId = null`, so the client never acks those.

Client -> server: `DeliveryAck(deliveryId: String)` (`_class = "deliveryack"`) **replaces** `MessageAck`. Remove `MessageAck` from the sealed interface and `@JsonSubTypes` on both sides, and drop the `X-Socket-Acks` handshake header.

Server side: `PendingPushRegistry` key becomes `(userId, deviceKey, deliveryId)` and holds the push for that device's targets only. `ack(userId, deviceKey of the acking session, deliveryId)`. `onSessionClosed` stays as is (per session). Ack timeout stays 3 s.

### Push layer cleanup

- `FirebaseService` and `ApnsService` each get one send method: `send(targets: List<PushTarget>, notification: NotificationResponse, options: PushOptions)`. The `sendNewMessage...`, `sendReaction...`, `sendFriendRequest...`, `sendWakeToUser` variants go away; `NotificationService` builds the `NotificationResponse` once per recipient.
- Both implement a small `PushSender` interface (`platform`, `send`), so `DeliveryService` loops over senders instead of naming both.
- Token lookup happens once, in `DeliveryService`, and the targets are passed down.
- Flattening `NotificationResponse` into a string map and the `type` name live in one mapper file, `notifications/firebase/model/NotificationResponseMapper.kt` (mappers go in their own files). The FCM 4 KB content cut and the APNs fallback title/body stay in their senders.
- One bounded executor for push I/O (`ThreadPoolTaskExecutor` bean or a shared `Executors.newFixedThreadPool`, shut down on `@PreDestroy`) instead of `CoroutineScope(Dispatchers.IO).launch` per call. `PendingPushRegistry` hands its due pushes to the same executor.
- FCM: `FirebaseMessaging.sendEach(messages)` for all targets of one delivery (one HTTP batch instead of one call per token); keep the invalid-token cleanup per response. APNs: use the `CompletableFuture` from Pushy instead of `.get()` per token.
- Token cleanup (`UNREGISTERED`, `BadDeviceToken`, ...) is unchanged.
- Push payloads may change shape freely (D5), e.g. fix `requesterId`.

### Device identity (open decision D4)

Both options give every socket session and every push token a `deviceKey`, and both link it to the device's `refreshTokens` row, so every session end can delete that device's push tokens (see [Logout and session end](#logout-and-session-end)). The rest of the plan is the same either way.

**Option A - session id in the access token (server only).**
`refreshTokens` already holds exactly one row per logged-in device (login dedups on userId + deviceName + deviceType). Put that row's id as a `sid` claim into every access token (`JwtService.generateAccessToken(userId, sessionId)`, set on login and refresh). Then:
- socket handshake: `deviceKey = sid` from the bearer token,
- `/users/setnotificationtoken`: `deviceKey = sid` from the request's token, stored on the token row,
- actor device of any HTTP request: `sid` from the request's token (needs the claim exposed next to the principal, e.g. a `requireSession()` helper beside `requireAuth()`).
- `deviceKey` **is** the session row id, so a session end knows the keys of the push tokens to delete without any extra field.
- `/users/setnotificationtoken` must reject a `sid` whose session row no longer exists - otherwise an app whose session was just ended re-registers its token with its still valid access token (up to 15 minutes).
- Pros: no client work; one source of truth (the session list the user already sees). Cons: tied to the auth session model - a re-login that creates a new row gives the same physical device a new key (the token row is re-linked on the next app start). Access tokens issued before the release have no `sid`; they expire within 15 minutes, a session without `deviceKey` in that window just covers no token.

**Option B - client-generated device id.**
The client creates a UUID once per installation, stores it, and sends it as `X-Device-Id` on the socket handshake, on `/users/setnotificationtoken` and on every HTTP request (Ktor default header).
- Login and refresh store the header value on the session row (`refreshTokens.deviceKey`), so a session end can look up which push tokens belong to it.
- Pros: independent of auth; survives re-login. Cons: client work (store the id, send the header everywhere); one more field on `refreshTokens`; the value is client-controlled (only matters within the user's own devices, so low risk).

Either way: `FirebaseToken` and `ApnsToken` get `deviceKey: String? = null` (indexed). `saveToken` updates `deviceKey` on an existing `(userId, token)` row instead of returning early.

### Logout and session end

Today the logout request is `{ refreshToken, notificationToken, isAndroid, allDevices }`. The push token is in there only because the server cannot find the device's push token on its own. Once push tokens carry the device's `deviceKey`, the server can:

- **Logout:** `{ refreshToken, allDevices }`. The server ends the session and deletes the push tokens with that session's `deviceKey` (or all of the user's tokens for `allDevices`). `notificationToken` and `isAndroid` go away, and with them the credential-less "delete whatever token is named" branch.
- **Every other session end** deletes the push tokens of the ended session(s) in the same step - one helper, e.g. `PushTokenService.deleteForSessions(userId, deviceKeys)` / `deleteAllForUser(userId)`, called from:
  - `AuthService.endSession` (device list "log out"),
  - `UserService.changePassword` and `UserService.resetPassword`,
  - `AdminUserService` forced logout,
  - `DatabaseCleanupTask` for expired sessions (read the ids before deleting the rows),
  - `UserService.deleteAccount` (all tokens of the user).
- A device whose session ended gets no more pushes for that account. After its next login it registers its push token again (the client does this on every start of the chat list - check that a fresh login reaches that point too), and the token is linked to the new session.

### Delivery matrix after the change

| Trigger | Socket type | Recipient | Mode | Push |
|---|---|---|---|---|
| New message (1:1 / group) | `MessageChange(new)` + `deliveryId` | other participants | `NOTIFY` | `message` |
| New message | `MessageChange(new)` | actor's other devices | `LIVE_ONLY` | - |
| Edit, delete, read, poll vote | `MessageChange` | all participants, actor's other devices | `LIVE_ONLY` | - |
| Reaction added | `MessageChange` + `deliveryId` | message sender (if not the reactor) | `NOTIFY` | `message` (`reaction=true`) |
| Reaction added / removed | `MessageChange` | everyone else, actor's other devices | `LIVE_ONLY` | - |
| System message | `MessageChange` | all incl. actor | `LIVE_ONLY` | - (unchanged) |
| Friend request sent / accepted | `FriendRequest` + `deliveryId` | receiving user | `NOTIFY` | `friend_request` |
| User / relation change | `UserChange` | as today | `LIVE_ONLY` | - |
| Group change | `GroupChange` | as today | `LIVE_ONLY` | - |
| New event | `EventChange` + `deliveryId` | audience | `NOTIFY` | `event` |
| New event | `EventChange` | creator | `LIVE_ONLY` | - |
| Event changed / deleted | `EventChange` | audience + creator | `LIVE_ONLY` | - |
| Event reminder (Todo, not built yet) | new type + `deliveryId` | participants | `NOTIFY` | new subtype |
| Birthday | - | birthday user, friends | `PUSH_ALL` | `birthday` (unchanged) |
| Wake | - | target(s) | `PUSH_ALL`, FCM only, TTL 0 | `wake` (unchanged) |
| Map change | `MapChange` broadcast | everyone except the acting **device** (was: acting user) | `LIVE_ONLY` | - |
| Location, online status, snapshots | as today | as today | `LIVE_ONLY` | - |

---

## Implementation steps

All phases happen on the separate branch (see [Scope and timing](#scope-and-timing)). They are an order of work, not separate releases; client and server changes for one phase can land together.

### Phase 1 - push layer cleanup (no behaviour change)

1. Add `PushTarget`, `PushOptions`, `PushSender` (in `notifications/`), `NotificationResponseMapper.kt` (shared flatten + type name).
2. `FirebaseService` / `ApnsService`: one `send(targets, notification, options)` each, on a shared bounded executor; FCM via `sendEach`, APNs async. Remove the per-type `send*ToUser` methods and the double token lookup.
3. `NotificationService`: build each `NotificationResponse` once (one helper per type instead of the duplicated parameter blocks in `notifyMessageUpdate`), build the 1:1 `MessageChange` per recipient (fixes the `clientMessageId` bug).
4. Move wake into `NotificationService.notifyWake(...)` returning the device count; `WakeService` stops using `FirebaseService`.
5. Fix `requesterId` in `FriendRequestNotificationResponse` (server and client together).

### Phase 2 - `DeliveryService` (refactor)

1. Add `Delivery`, `DeliveryMode`, `DeliveryResult`, `Actor`, `DeliveryService`.
2. `SocketConnectionHandler`: keep the registry, but expose what `DeliveryService` needs (`sessionsOf(userId)`, `write(session, json): Boolean`) and drop the delivery logic from `sendNewMessage`. Optional: index sessions by user (`ConcurrentHashMap<ObjectId, List<SocketConnection>>`) instead of filtering the whole list for every send.
3. Move every `notifyX` in `NotificationService` onto `DeliveryService.deliver(...)`. Without device keys yet, keep the current per-user rule inside `DeliveryService`, so this step is a pure refactor and the place to add tests for the decision table.

### Phase 3 - per-device delivery (needs D4)

1. Implement the chosen device identity (option A: `sid` claim + `requireSession()`; option B: `X-Device-Id` header in the client and a server helper that reads it).
2. `deviceKey` on `SocketConnection`, `FirebaseToken`, `ApnsToken`; `saveToken` back-fills it.
3. `PendingPushRegistry` keyed per device, holding that device's targets.
4. `DeliveryService` switches from the per-user rule to the per-device rule; tokens without `deviceKey` are always pushed.
5. Every session end deletes the device's push tokens by `deviceKey` - logout, device list "log out", password change / reset, admin forced logout, expiry cleanup, account deletion (see [Logout and session end](#logout-and-session-end)); drop `notificationToken` / `isAndroid` from the logout request.

### Phase 4 - generic ack

1. Server: `deliveryId` on `MessageChange`, `FriendRequest`, `EventChange`; `DeliveryAck` replaces `MessageAck` in the sealed interface **and** `@JsonSubTypes`; `handleTextMessage` routes it to the registry; remove `acksMessages`, `ACK_SUPPORT_HEADER` and the legacy "write = delivered" path.
2. Client: ack every socket message with a non-null `deliveryId`; remove `MessageAck` and the `X-Socket-Acks` header.

### Phase 5 - actor's other devices (D2)

1. Services pass `Actor(userId, deviceKey)` into `notifyMessageUpdate`, `notifyMapUpdate` and the reaction path (from `requireAuth()` + the device key of the request).
2. `notifyMessageUpdate` adds a `LIVE_ONLY` delivery for the actor, excluding the acting device.
3. `notifyMapUpdate` excludes the acting device instead of the acting user.
4. Client: no local notification for own messages (see below).

## Client changes (SchneaggchatV3)

1. Do not show a local notification for a socket `MessageChange` whose `senderId == ownId`.
2. Send `DeliveryAck(deliveryId)` for every socket message with a non-null `deliveryId` - after storing it and showing / scheduling the notification, before media downloads, like `MessageAck` today. Remove `MessageAck` and the `X-Socket-Acks` header.
3. Option B only: device UUID, sent as `X-Device-Id` on the handshake, on `setnotificationtoken` and on every HTTP request.
4. Logout: stop sending `notificationToken` / `isAndroid` (the server deletes the device's tokens itself).
5. Read the fixed `requesterId` in friend request pushes.
6. Push handler: drop a push for something already stored (it already does this for messages); same for friend requests / events if the ack raced the push.

## Release

- Server and client go out together in the release after the current one; the branch is merged into `main` for that release.
- No compatibility with older app versions (D5): the app forces users to update on start when a new version is released, so no old client ever opens a socket against the new server.
- An install that has not been opened since the release still has its old push token without `deviceKey`. It keeps getting pushes (see "Unlinked tokens" below) until it is opened, updated and re-registers its token.
- Prod builds from the branch set in `server_docker/Dockerfile`, so nothing reaches prod before the feature branch is merged.

## Risks and edge cases

- **Double notification on a device**: a device acks too late (> 3 s, slow phone) and also gets the push. Same as today for new messages; the client push handler must drop already-stored items.
- **Unlinked tokens right after the release**: tokens without `deviceKey` are always pushed until the device opens the new app once (it re-registers on every start). A device that is in the foreground before its token is re-linked can get one push on top of the socket notification.
- **Token moved between accounts** (account switch on one phone): the unique index is `(userId, token)`, so the same token can sit under two users. Logout already deletes it for the old user; with the per-device delete on session end this stays covered.
- **Several sessions of one device** (reconnect before the old one is reaped): grouped by `deviceKey`, any ack from that device releases the push.
- **Desktop** has no push token: it only ever gets the socket. Nothing to do.
- **Horizontal scaling**: sessions and pending pushes are in memory (Gotcha 7 in the project skill). This plan does not change that.
- **Pending push memory**: entries live at most `ACK_TIMEOUT_MS`; per-device keys multiply the count by the number of devices, which stays small.

## Tests (write with the implementation, run only when asked)

- `DeliveryService` decision table with MockK fakes for the socket registry and the push senders: no session; session write fails; session acks / times out / closes; two devices with one connected (the phone must be pushed); token without `deviceKey`; `LIVE_ONLY`, `PUSH_ALL`; actor exclusion.
- `PendingPushRegistry`: ack from device A does not cancel the push for device B; session close releases only that device.
- Mapper: flatten + type name per `NotificationResponse` subtype; FCM 4 KB cut.

---

## Pros, cons and everything it fixes

### What it fixes

Delivery (user-visible):

1. **No push on the phone while another device is online.** Desktop open, or a second phone in the foreground, suppresses every push for all other devices of the account today. After: decided per device (D1).
2. **One device's ack cancels the push for all devices.** `PendingPushRegistry` keys on `(userId, messageId)` today. After: `(userId, deviceKey, deliveryId)`.
3. **Lost pushes for reactions, friend requests and new events.** These count a socket *write* as delivered, so they get lost when the app is on its way to the background or the peer vanished without a close frame. After: acked like new messages (D3).
4. **Own actions do not reach own other devices live.** A message sent on the phone shows up on the desktop only after the next sync; map edits skip all of the editor's devices. After: socket update to every other device of the actor (D2).

Sessions and push tokens (privacy / security):

5. **Ended sessions keep receiving pushes**, with plaintext message content: device list "log out", password change, password reset (the "phone was stolen" case), admin forced logout, expired sessions. After: every session end deletes that device's tokens.
6. **Push tokens of deleted accounts stay in the database.** After: deleted with the account.
7. **Unauthenticated token delete.** The credential-less logout branch deletes any push token named in the request. After: the branch and the `notificationToken` / `isAndroid` fields are gone.
8. **Logout depends on the client remembering its push token.** A reinstalled app or a rotated token leaves the old row behind. After: the server finds the device's tokens itself.

Code structure:

9. **`WakeService` bypasses `NotificationService`** and talks to `FirebaseService` directly. After: wake goes through `NotificationService` like everything else.
10. **Copy-paste push layer**: three per-type send methods in each of the two senders, plus the same parameter blocks twice in `notifyMessageUpdate`. After: one `send(targets, notification, options)` per sender, one place that builds each payload.
11. **Two payload mappers** (`notificationResponseToDataMap`, `buildPayload`) with their own `type` name lists. After: one mapper file.
12. **Socket vs push decided in many places** (each `notifyX` method, `SocketConnectionHandler.sendNewMessage`, `notifyReactionAdded`). After: one decision point (`DeliveryService`); a new feature only picks a `DeliveryMode`.
13. **Event reminders (`Todo.md`)** get a ready-made path: a new socket type + push subtype with `NOTIFY`.

Performance / robustness:

14. **Unbounded threading**: a new `CoroutineScope(Dispatchers.IO).launch` per send, nested twice. After: one bounded executor, shut down cleanly.
15. **One FCM HTTP call per token, blocking APNs `.get()` per token.** After: FCM `sendEach` batch, APNs async futures.
16. **Push tokens read from Mongo twice per send.** After: once per delivery.
17. **Every socket send filters the whole connection list** (optional fix: index sessions by user).

Smaller bugs:

18. 1:1 `MessageChange` built for the receiver also when it goes to the sender, so the sender gets `clientMessageId = null`.
19. `requesterId` in friend request pushes holds the receiver's id.
20. Friend request push errors are logged under the sender instead of the receiver.

Testability:

21. The delivery decision becomes a pure decision table in one class, testable with fakes - today it is spread over `NotificationService`, `SocketConnectionHandler` and `PendingPushRegistry`.

### Pros

- Every device of a user gets every notification exactly once in the normal case: by socket when it is connected, by push when it is not.
- Pushes are only skipped when the device itself confirmed the update - no more guessing from a TCP write or from another device.
- Ending a session really ends it: no more pushes to logged-out, stolen or expired devices.
- One code path for all notifications: easier to reason about, to log, to test, and to extend (event reminders, future types).
- Less code: the duplicated send methods, both ack variants (`MessageAck` + header) and the logout token fields go away.
- No compatibility code needed (D5), so the result is simpler than a gradual migration would be.

### Cons and costs

- **More notifications for multi-device users.** A phone in the pocket buzzes while the user is chatting on the desktop (the direct consequence of D1). A later "delay and drop if read elsewhere" mode could soften it; out of scope here.
- **Big, central change.** Every notification in the app goes through the new code. A bug there breaks all of them at once. Needs the decision table tests and a careful manual test round before the release.
- **All-or-nothing release.** Server and client ship together and old apps are forced to update, so there is no gradual rollout. A rollback means rolling back the server *and* shipping a client rollback.
- **Client work** in the same release: `DeliveryAck`, no notification for own messages, logout without push token, fixed `requesterId`, de-duplication of late pushes for friend requests / events; with option B also the device id header.
- **Push delay of up to 3 s** for a connected device that does not ack (app just going to the background) - today only for new messages, afterwards also for reactions, friend requests and new events.
- **Duplicate notification on a slow device** that acks after the 3 s timeout - existing behaviour for messages, now possible for more types; the client has to drop already-stored items.
- **Unlinked tokens right after the release** are always pushed, so a device can get one double notification until it re-registers its token on the next app start.
- **Device identity has a price either way**: option A ties devices to auth sessions (a new session row = a new device key until the token is re-registered); option B needs client work and stores a client-chosen value.
- **More socket traffic**: one inbound ack per push-worthy event and per device (small).
- **Not addressed**: sessions and pending pushes stay in memory (single server instance only, Gotcha 7).
