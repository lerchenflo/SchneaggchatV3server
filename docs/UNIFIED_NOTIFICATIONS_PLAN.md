# Unified notification delivery - plan

Status: phases 1-4 built on `beta` (2026-11-06), not deployed yet. Phases 5-8 open. Revised 2026-11-06 after a design review, see [Review](#review). Replaces `plans/DEVICE_AWARE_PUSH_PLAN.md` in the client repo.

## Goal

One call for everything the server tells a user:

```kotlin
deliveryService.send(userId, Delivery.Alert(socket = change, push = notification))
```

The service finds every device of the user and gets the update to each one: over the socket when the device is connected and confirms it, over FCM / APNs when it is not. Callers never decide between socket and push, and never touch sessions, tokens or acks.

## Decisions

| # | Question | Decision |
|---|---|---|
| D1 | Desktop is open. Does the phone without a socket get a push? | **Always push.** Decided per device, whatever the other devices do. No delay and no quiet mode. |
| D2 | Do the acting user's other devices get live updates of their own actions? | **Yes, socket only.** Every device of the actor except the acting one. The actor never gets a push for their own action. |
| D3 | Ack only for new messages, or for everything that has a push? | **Generic ack.** Everything that can fall back to a push is acked per device. |
| D4 | What is a device? | **The `refreshTokens` row.** Its id goes into the access token as `sid`, and the push token moves onto the row. Ending a session then removes its push target without extra code. |
| D5 | Support for older app versions? | **None, on every release** (user, 2026-11-05). Play forces the update on Android, a popup on iOS blocks the app before anything starts. The deploy order below still matters, because store review means server and client can never go out at the same moment. |
| D6 | Read on one device, clear the notification on the others? | **Last phase**, after core delivery works. |
| D7 | Delivery guarantee? | **At least once per device, while the server is up.** The client drops duplicates by `deliveryId`. A crash between the database write and the send can lose a notification, not the data: the next sync brings it. No outbox. |

## Current state

Checked against the code on 2026-11-05.

### Where notifications go out

`NotificationService` calls the socket handler and both push senders directly from each `notifyX` method. The only other sender is `user/wake/WakeService.kt`, which calls `FirebaseService` itself.

| Method | Socket | Push | Fallback today |
|---|---|---|---|
| `notifyMessageUpdate`, new message | `MessageChange`, actor excluded | FCM + APNs `message` | Per **user**: one ack from any session cancels the push for all devices. A v3.0.17 session counts a socket write as delivered. |
| `notifyMessageUpdate`, other changes | `MessageChange`, actor excluded | none | - |
| `notifyReactionAdded` | rides on `MessageChange` | `message` with `reaction=true` | Per user, a socket write counts as delivered. |
| `notifyFriendRequest` | `FriendRequest` | `friend_request` | Per user, a socket write counts as delivered. |
| `notifyEventUpdate` | `EventChange` | `event`, new events only, not to the creator | Per user, a socket write counts as delivered. |
| `notifyBirthday` | none | `birthday` | Always pushed. |
| Wake (`WakeService`) | none | FCM only, TTL 0 | Always pushed. |
| `notifySystemMessage`, `notifyUserUpdate`, `notifyOwnUserUpdate`, `notifyRelationChange`, `notifyGroupUpdate`, `notifyGroupExtensionUpdate`, online status, location | various | none | Socket only, offline devices sync. |
| `notifyMapUpdate` | `MapChange` broadcast, all devices of the actor excluded | none | - |

### Problems

1. **Fallback is per user, not per device.** With the desktop app open, the phone in the pocket gets no push. This is the main bug.
2. **Nothing links a socket to a push token.** `SocketConnection` knows `userId` + `sessionId`, the token rows only `userId` + `token`.
3. **Only new messages are acked.** Reactions, friend requests and events treat a TCP write as delivered, so they get lost while an app goes to the background.
4. **The actor's other devices miss their own actions** until the next sync.
5. **Ended sessions keep their push tokens**, and pushes carry plaintext message content. Only `/auth/logout` deletes tokens, and only the one the client names. These paths keep them: `AuthService.endSession`, `UserService.changePassword`, `UserService.resetPassword`, `AdminUserService.forceLogout`, `DatabaseCleanupTask`, `UserService.deleteAccount`.
6. **Ended sessions keep their socket.** Only `forceLogout` closes it. "Log out" of a device, a password change and a password reset leave the live socket running, and it keeps getting updates.
7. **Unauthenticated token delete.** The credential-less branch of `AuthService.logout` deletes whatever push token the request names.
8. **A token can sit under two users.** The unique index is `(userId, token)`, so after an account switch without a clean logout, user A's messages reach user B's phone.
9. **Copy-paste push layer.** Each sender has per-type send methods, the same parameter blocks appear twice in `notifyMessageUpdate`, both senders keep their own `type` name `when`, and tokens are read twice per send.
10. **Unstructured threading.** A new `CoroutineScope(Dispatchers.IO).launch` per send, APNs blocks on `.get()` per token.
11. **The Android client drops a push when its message is already stored**, and a sync can store messages before their pushes arrive, so the user is never told (`AppFirebaseMessagingService.kt:137-142`).

Smaller bugs: the 1:1 `MessageChange` is always built for the receiver, so the sender gets `clientMessageId = null`. On every connect, both snapshots go to all sessions of the user instead of the new one.

### Client facts that matter

- The app closes its socket in the background, so a phone in the pocket depends on push.
- It shows a local notification for each `MessageChange` with `newMessage`, also for own messages. D2 needs a client guard.
- `AppJson` sets `ignoreUnknownKeys = true`, so an unknown top-level key is harmless. An unknown `_class` is caught and dropped.
- v3.0.18 sends `X-Socket-Acks` and `messageack`. v3.0.17 does neither.
- It registers its push token on every start of the chat list.
- Device names carry a stable id (`androidId`, `vendorId`, `installId`, `macAddress`), so login reuses the same `refreshTokens` row and its id stays stable.

---

## Target design

### `Delivery`: what a caller passes

Three cases, so an impossible combination cannot be built:

```kotlin
sealed interface Delivery {
    /** Socket only. Offline devices catch up with their next sync. */
    data class Live(val socket: SocketConnectionMessage) : Delivery

    /** Socket for connected devices, push for every device that did not confirm it. */
    data class Alert(
        val socket: SocketConnectionMessage,
        val push: NotificationResponse,
        val id: String = ObjectId.get().toHexString(), // the message id for new messages
        val awaitAck: Boolean = false,                 // true for new messages, goes away in phase 5
    ) : Delivery

    /** Push to every device, sockets ignored (wake, birthday). */
    data class PushOnly(val push: NotificationResponse, val options: PushOptions = PushOptions()) : Delivery
}
```

### `DeliveryService`: the only place that decides

```kotlin
fun send(userId: ObjectId, delivery: Delivery)
fun sendAll(userIds: Collection<ObjectId>, build: (ObjectId) -> Delivery)
fun sendToSession(sessionId: String, socket: SocketConnectionMessage) // snapshots after a connect
fun broadcast(socket: SocketConnectionMessage, excludeUserId: ObjectId?)
fun pushDeviceCount(userId: ObjectId, provider: PushProvider): Int    // shown by wake
```

Phase 6 replaces `excludeUserId` with a `skipDevice` on all of them. `NotificationService` keeps its `notifyX` methods, so its callers stay as they are. Each method only works out recipients and payloads and calls `DeliveryService`. `sendAll` reads the push targets of a whole group in one query, and only for `Alert` and `PushOnly`.

`SocketConnectionHandler` keeps the session registry and exposes `sessionsOf(userId)` and `write(session, json)`. `sendMessage`, `sendNewMessage` and the delivery logic leave it. After this, only `DeliveryService` uses the socket writer and the push senders.

### Per-device rule

```
Live:     write to every session
PushOnly: push to every target
Alert, for each device in (sessions + targets):
    no target (desktop)  -> write to its sessions, done
    no session           -> push now
    session and target, awaitAck
                         -> hold the push, then write to its sessions
                            released by an ack from any session of this device
                            pushed after 3 s, or when all its sessions closed
    session and target, no awaitAck
                         -> write to its sessions, push when every write failed

A session without a device id (token issued before the deploy) -> write only.
A target without a device id (legacy token) -> push now.
```

The push is registered before the write, so an ack can never arrive ahead of it. This is how `sendNewMessage` already works.

### Device model

`RefreshToken` gets two fields. Its row already is one device, with a stable id across refresh and re-login:

```kotlin
val pushProvider: PushProvider? = null, // FCM or APNS
val pushToken: String? = null,
```

- A partial unique index on `pushToken` (`{'$type': 'string'}`), created in `ensureRefreshTokenIndexes`, keeps one token on one row. This fixes problem 8.
- `rotateTokenRow` only sets named fields, so refresh and login keep the push fields.
- `DeviceDirectory` (new, `notifications/`) is the only class that reads or writes these fields:
  - `targetsOf(userIds): Map<ObjectId, List<PushTarget>>`
  - `registerPushToken(deviceId, provider, token)`: clears the token from any other row, then sets it. Refuses a row that no longer exists.
  - `clearPushToken(token)`: for `UNREGISTERED`, `BadDeviceToken` and the like.
- `PushTarget(deviceId: ObjectId?, provider, token)`.

Access token:

- `generateAccessToken(userId, sid)`. `login` builds it after the row is known, today it builds it before. `refresh` has the row already.
- `JwtAuthFilter` puts `sid` into `auth.details`. `currentDeviceOrNull()` sits next to `requireAuth()`.
- Web logins have no row and no `sid`. The admin panel never opens `/ws`.

Socket: the handshake reads `sid` into `SocketConnection.deviceId` and refuses a `sid` whose row is gone. `acksMessages` goes away.

### Ack

- `DeliveryService` stamps a top-level `deliveryId` key onto the JSON of an `Alert` (write the message to a tree, add the key). No message type changes, so a new type gets acks for free, and old clients ignore the key.
- Client to server: `DeliveryAck(deliveryId)`, routed to `PendingPushRegistry.ack(deviceId, deliveryId)`.
- `MessageAck(messageId)` keeps working until phase 7, because the `deliveryId` of a new message is its message id. It costs one line and covers the time between the server deploy and the client release.
- `PendingPushRegistry` key becomes `(deviceId, deliveryId)`, and an entry holds that device's targets.
- The push carries the same `deliveryId` (FCM data key, APNs custom key and `apns-collapse-id`), so a device can drop a push for something it already showed. On Android this replaces the "already stored" check from problem 11. On iOS the collapse id should also replace a local notification with the same identifier. Test this on a device before relying on it.
- On shutdown the registry sends everything it still holds. It depends on the senders, so Spring destroys it first.

### Push layer

- `PushSender` interface, implemented by `FirebaseService` and `ApnsService`: `send(target, notification, options)`. All per-type send methods go.
- `PushOptions(ttlSeconds: Long? = null, providers: Set<PushProvider> = all)`. Wake uses TTL 0, FCM only.
- `NotificationResponseMapper.kt` holds the shared flatten and `type` name. The FCM 4 KB cut and the APNs fallback texts stay in their senders.
- One thread pool for push I/O with fixed threads and an unbounded queue: a burst gets delayed, never dropped. It waits for running work on shutdown. `PendingPushRegistry` uses it too.
- APNs uses Pushy's future instead of `.get()`.
- Remove the unused `ApnsConfig` in `FirebaseService.constructMessage`.
- No FCM `sendEach` batching: with per-device holds nearly every send has one target.
- The later FID move (`Todo.md`) only touches `PushTarget`, `DeviceDirectory` and `FirebaseService`.

### Ending a session

`DeviceSessions` (new, `authentication/`) replaces the direct delete calls on `refreshTokenRepository`:

- `endDevice(userId, deviceId)` and `endAll(userId)`: delete the row(s) and close the matching sockets. The push target goes with the row.
- Used by `logout`, `endSession`, `changePassword`, `resetPassword`, `forceLogout`, `deleteAccount` and `DatabaseCleanupTask`.

### Legacy tokens

Existing FCM / APNs rows cannot be matched to a device, because they only know the user. A phone that was not opened since the deploy must keep getting pushes, so during the transition:

- `setnotificationtoken` writes the row **and** the old collection, so a rollback loses nothing.
- `DeviceDirectory.targetsOf` adds old rows whose token is on no device row, with `deviceId = null`. They are always pushed.
- `endAll` deletes the user's old rows too.
- Phase 7 drops the old collections and this code. All of it sits in `DeviceDirectory`.

### Delivery matrix

| Trigger | Recipient | Delivery |
|---|---|---|
| New message | other participants | `Alert` (`id` = message id) |
| New message | actor's other devices | `Live` |
| Edit, delete, read, poll vote | participants, actor's other devices | `Live` |
| Reaction added | message sender, unless they reacted | `Alert` |
| Reaction added or removed | everyone else, actor's other devices | `Live` |
| System message | all participants incl. actor | `Live` |
| Friend request sent or accepted | receiving user | `Alert` |
| New event | audience | `Alert` |
| New event | creator | `Live` |
| Event changed or deleted | audience, creator | `Live` |
| User, relation, group, group extension change | as today | `Live` |
| Online status, location | as today | `Live` |
| Connect snapshots | the new session only | `sendToSession` |
| Map change | everyone except the acting device | `broadcast` |
| Birthday | birthday user, friends | `PushOnly` |
| Wake | targets | `PushOnly`, FCM only, TTL 0 |
| Event reminder (`Todo.md`) | accepted users, creator | `Alert` |

---

## Phases

All on one branch from `beta`. Each phase compiles and works on its own.

| Phase | Content | Size | Needs client |
|---|---|---|---|
| 1 | Push layer | M | no |
| 2 | `DeliveryService`, still per user | M | no |
| 3 | Device identity and session end | L | no |
| 4 | Per-device rule | M | no |
| 5 | Generic ack | M | yes |
| 6 | Actor's other devices | S | yes |
| 7 | Cleanup | S | no |
| 8 | Read elsewhere (D6) | M | yes |

**Phase 1 - push layer (no behaviour change).** `PushTarget`, `PushOptions`, `PushSender`, `NotificationResponseMapper.kt`, thread pool. One `send` per sender. Build each `NotificationResponse` once. Build the 1:1 `MessageChange` per recipient. Move wake into `NotificationService`.

**Phase 2 - `DeliveryService` (no behaviour change).** Add `Delivery` and `DeliveryService`, keep the per-user rule inside. Move every `notifyX` onto it. Write the decision table tests here. Add the metrics.

**Phase 3 - device identity.** `sid` claim, `currentDeviceOrNull()`, `deviceId` on `SocketConnection`, the handshake check. Push fields, index, `DeviceDirectory`, legacy read. `setnotificationtoken` writes both. `DeviceSessions` replaces the direct deletes. Remove the unauthenticated delete branch of `logout`.

**Phase 4 - per-device rule.** Switch the rule, key `PendingPushRegistry` by device, drain it on shutdown, drop `acksMessages` and the `X-Socket-Acks` read. The main bug is fixed here, for released clients too. `sendToSession` for the snapshots.

**Phase 5 - generic ack.** Client: send `DeliveryAck` for a stamped message after storing it and showing the notification, before media downloads. Drop duplicates by `deliveryId`. Server: stamp `Alert`, send `deliveryId` with the push, remove `awaitAck` so reactions, friend requests and events wait for an ack too.

**Phase 6 - actor's other devices.** Client: no notification for own messages. Server: `Actor(userId, deviceId)` on `notifyMessageUpdate`, `notifyMapUpdate` and the reaction path (12 call sites), plus the extra `Live` delivery.

**Phase 7 - cleanup.** Remove `MessageAck`, the old token collections and their code, and `notificationToken` / `isAndroid` on logout.

**Phase 8 - read elsewhere.** Socket message and silent push that clear a chat's notification. Silent types need a flag the client skips, because Android shows a generic notification for an unknown push type. So the client learns the flag first.

## Release order

No compatibility code is needed for this, only the right order:

1. **Server, phases 1-4.** Works with the released apps, nothing on the wire changes. Reactions, friend requests and events are per device too, but a socket write still counts as delivered for them until phase 5. A v3.0.17 app does not ack, so it gets a push on top of each live message.
2. **Client release** with the client parts of phases 5 and 6. They do nothing until the server stamps messages, and an app on this version works with the server from step 1.
3. **After the forced update, server, phases 5 and 6.** Doing this before step 2 would give old apps a push on top of each live reaction, friend request and event, and own messages would ring their other devices.
4. **Later, phase 7**, once the old token rows stopped being used (metric below).

Server rollback at any step: the new fields are ignored by older code, and the old collections stay complete until phase 7.

## Observability

New counters in `ServerMetrics`, shown in the admin monitoring: deliveries per type, pushes sent right away, pushes held, held pushes released by ack, by timeout and by session close, ack latency, legacy tokens used. If timeouts are high, the 3 s value or the client is wrong, and this shows which one.

## Tests

Written with the implementation, run when asked.

- `DeliveryService` decision table with fakes: no session, write fails, ack, timeout, session close, desktop and phone (the phone gets the push), two phones, token without device, session without device, `skipDevice`, all three `Delivery` cases.
- `PendingPushRegistry`: an ack from device A does not cancel device B, session close releases only that device, shutdown sends what is held.
- `DeviceDirectory`: a token moves between rows and users, an ended session gets refused, legacy rows are only returned while unlinked.
- `RefreshToken` row: refresh and login keep the push fields.
- Mapper: flatten and `type` per subtype, FCM 4 KB cut.
- Manual round before release: Android, iPhone and desktop on one account, app in foreground, background and killed, each `Alert` type.

## Risks

- **Central change.** A bug hits all notifications at once. Phases 1 and 2 change no behaviour and bring the tests first, and phase 4 is one switch.
- **Duplicate notification** when a device acks after 3 s. Handled by `deliveryId` from phase 5. Until then it stays as it is today, for messages only.
- **15 minutes after the phase 3 deploy**, access tokens without `sid` still exist. Those sessions are write-only, so a device can get a push on top of the socket until its next refresh.
- **Two rows for one device** after the rare login race in `AuthService.login`, or with a blank device name. The token stays on the older row until the next app start moves it, and the device can get a double notification until then.
- **More pushes for multi-device users**, the direct result of D1.
- **A stale phone notification** after reading on the desktop, until phase 8.
- **Up to 3 s push delay** for a connected device that does not ack, for all `Alert` types instead of only messages.
- **Not addressed:** sessions and held pushes stay in memory, so this is single instance only. No retry of transient FCM errors (rejected 2026-10-21). Push content stays plaintext.

## What it fixes

Problems 1-11 and both smaller bugs. Event reminders and radar pushes get a ready path: one `Alert`, no new delivery code.

---

## Review

Reviewed as a senior developer would review it, against the code and the 2026-10-20 version of this plan. This was a review by the plan's author, not an independent one.

**Verdict: the 2026-10-20 version would not have passed. This version would, with the conditions below.**

| # | Objection to the old version | Severity | Change |
|---|---|---|---|
| 1 | It assumed server and client ship at the same moment. Store review makes that impossible, and the old wire change would have dropped live updates or doubled notifications in between. | Blocker | [Release order](#release-order), additive `deliveryId` |
| 2 | Tokens without a device had no rollback path, and deleting old rows on migration would lose pushes after a rollback. | Blocker | Write both until phase 7 |
| 3 | Seven session end paths had to remember a cleanup call, which is the bug class it wanted to remove. | Major | Push target on the session row |
| 4 | Ended sessions kept their live socket, and only push tokens were covered. | Major | `DeviceSessions` closes sockets |
| 5 | `DeliveryMode` next to two nullable payloads allowed nonsense such as `NOTIFY` without a push. | Major | Sealed `Delivery` |
| 6 | `deliveryId` on each message type means every new type has to remember it. | Major | Stamped by `DeliveryService` |
| 7 | Client dedupe by "already stored" is the bug in problem 11. | Major | Dedupe by `deliveryId` |
| 8 | A deploy lost up to 3 s of held pushes. | Minor | Drain on shutdown |
| 9 | No way to see whether acks work in production. | Minor | [Observability](#observability) |
| 10 | `sendEach` batching and a session index add code for a gain that this load does not need. | Minor | Dropped |

**What was checked in the code:** one row per device and in-place rotation, `rotateTokenRow` sets single fields, login reuses the row, the admin panel does not use `/ws`, `ignoreUnknownKeys` on the client, the list of session end paths, all direct sender callers.

**Conditions for approval:**

1. Phases 1 and 2 merge with the decision table tests, before any behaviour changes.
2. Phase 4 is tested by hand on real devices before it reaches `main`.
3. Phase 5 waits for the metrics from phase 2 in production.
4. The push token on the session row is a deliberate trade: an auth collection now holds a push field. It is accepted because the shared lifecycle removes a whole bug class, and only `DeviceDirectory` touches the field.

**Not verified yet:** the iOS collapse id behaviour, and how old desktop apps behave, since D5 only names Android and iOS. The additive wire keeps them working, but they get no forced update.
