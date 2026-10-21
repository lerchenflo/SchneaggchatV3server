# Todo

Collected follow-ups and checks for the server, so no thought gets lost. Each item names where it came from. Delete an item once it is done or rejected.

---

## Push notifications: none may get lost

Source: Android push audit in the client repo (2026-10-21) and the Android notification review (2026-10-16). The user's top priority: no case where a notification is swallowed and the user is never told. Done 2026-10-21: `INVALID_ARGUMENT` only deletes the FCM token when the error names the token, and friend-request pushes carry the sender as `requesterId` (see git history). Retrying transient FCM errors was rejected (user, 2026-10-21: not the cause in nearly any case; the SDK already retries HTTP 503 four times).

Constraint for everything below: the server must stay compatible with the released client (v3.0.17), so anything that changes what the client sends or receives waits for a client release.
- [ ] **One ack cancels the push for every device** (`notifications/websocket/SocketConnectionHandler.kt:150-177`, `PendingPushRegistry`). Push tokens only know the user, so a minimized desktop app (it keeps its socket) or a second phone in the foreground acks the message and the phone in the pocket never buzzes. Plan: client repo `plans/DEVICE_AWARE_PUSH_PLAN.md` (device id from the `RefreshToken` row as an access-token claim, push token and socket tied to it, the push held per device). Decide later (user, 2026-10-21).
- [ ] **Confirm-or-push for reactions, friend requests and events** (`notifications/NotificationService.kt`: `notifyReactionAdded` :398, `notifyEventUpdate` :363, `notifyFriendRequest` :479). They count as delivered once the socket write succeeds; new messages already wait for the client's ack (`PendingPushRegistry`). A socket that is closing (app going to the background, signal lost) loses them. The client would have to ack these too.
- [ ] **`setMessagesRead` marks every unread message of the chat read** (`message/MessageService.kt:682-760`). The client's timestamp is only used as `readAt`. So "Mark as read" / "Reply" on a notification also marks a message read that arrived after the notification was shown, and the user never sees it. Needs the client first: v3.0.17 sends its own clock's "now" (chat screen and notification action) and marks every local message read, so filtering on `sendDate <= timestamp` now would leave fresh messages unread on the server whenever the phone's clock is behind (they come back as unread on the next sync). Compatible way: a new optional parameter (e.g. the newest shown message id) that the server only filters on when it is present, sent by the notification actions of a new client.
- [ ] Client follow-up together with this refactor: the Android push path skips a push when its message is already stored (`AppFirebaseMessagingService.kt:137-142`), and a sync (e.g. the one behind "Mark as read") stores other chats' messages before their pushes arrive. To be replaced by "skip only what this device already notified" when the server side above is reworked (user, 2026-10-21). Tracked in the client `plans/Todo.md` (P1).

---

## Mappers in their own files

Source: user decision (2026-10-17). Not started - do it later, not as part of other work.

Every mapper (`toXResponse()`, `toX()` extension functions and similar conversions) lives in a separate mapper file, never inside a domain model, entity or response class file. Example: `user/usermodel/UserMapper.kt` next to `User.kt` / `UserResponse.kt`.

- [ ] Move the mappers out of these files (found 2026-10-17 by searching for `fun X.toY(` outside mapper files; check each one, some may not be mappers):
  - `announcement/model/AnnouncementResponse.kt`
  - `events/eventmodel/EventParticipation.kt`, `EventResponse.kt`, `GroupDeleteDelay.kt`
  - `games/model/GameScoreResponse.kt`
  - `group/model/GroupMember.kt`
  - `message/messagemodel/Message.kt`, `PollRequest.kt`, `PollResponse.kt`, `SystemEvent.kt`
  - `schneaggmap/model/MapEntryResponse.kt`
  - `user/usermodel/UserResponse.kt`
  - `website/donations/model/DonationResponse.kt`
  - `website/faq/model/FaqResponse.kt`
- [ ] From then on, new mappers go straight into a mapper file.

---

## Socket push after every user/group mutation - client follow-up

Source: client `plans/REPOSITORY_INTERFACE_MIGRATION_PLAN.md` (decision D16, 2026-10-13).

The server now pushes a socket event to every affected user and device for all 10 user/group mutations the client used to follow with a full sync (done 2026-10-17, see git history).

- [ ] Tell the client side once this is deployed, so the client can drop the `syncUsers()` / `syncGroups()` calls after these mutations and the risk note in the client plan can be removed. Prod builds from the branch set in `server_docker/Dockerfile` (`GIT_BRANCH`), so the change only counts once it is on that branch and deployed.

Found while checking, not fixed:

- [ ] Account deletion (`GroupLookupService.leaveAllGroups`) only removes the member row: when the deleted user was a group's last admin nobody is promoted, and a group whose last member is deleted stays as an undeleted group without members. `GroupService.performUserAction` (`REMOVE_USER`) handles both for a normal leave. (`updatedAt` bump and `GroupChange` were fixed 2026-10-21.)
- [ ] `/users/sharelocation` now pushes the actor's own row when `locationShared` flips (2026-10-21), but `/users/sync` still misses it on the actor's other devices that were offline: the own row only counts as changed through `User.updatedAt`, which a friendship setting does not bump.

---

## Event reminders

Source: user request (2026-10-18). Not started.

Push a reminder for an event one day before it starts and on the day it starts.

- [ ] Scheduler next to `notifications/BirthdayNotificationScheduler.kt` (`@Scheduled`, zone `Europe/Vienna`) that finds events whose `startDate` is tomorrow / today and sends both reminders through `NotificationService` (socket, FCM, APNs).
- [ ] Recipients: users with `EventParticipationStatus.ACCEPTED` (decide whether the creator and invited users who have not answered get it too).
- [ ] Send each reminder only once: remember it per event (e.g. sent-at fields on `Event`), reset them when `startDate` changes, skip ended events (`Event.hasEnded`) and skip the day-before reminder for events created less than a day before the start.
- [ ] Payload: a new `NotificationResponse` subtype or a flag on `EventNotificationResponse`. Older clients drop an unknown `_class`, and a reused `event` type would show as a new event, so check what released clients do with either. Add the APNs fallback title/body in `ApnsService.buildPayload`.
- [ ] Open questions: what time of day the reminders go out (fixed time like 08:00 / 18:00, or relative to the start), and whether users can turn them off.
- [ ] Client: show the reminder (texts in all locales) and open the event on tap.

---

## Unified console logging

Source: user request (2026-10-18). Mostly done in `6ac1cb9` ("updated logging"), in a different shape than planned: `AppLogger` got its own channel worker (one thread, in order, drained on shutdown), the request user from the `SecurityContext`, `user` / `error` params, `AppLogger.nameOf(id)` for a second user, and a username cache (10 min TTL). The hand-written user texts, raw `println` / `printStackTrace()` calls and the birthday `"$user.id"` bug are gone.

Left:

- [ ] Spring's own lines (SLF4J/logback) still use Spring's console format, not `AppLogger`'s. A `logback-spring.xml` (or routing them into `AppLogger`) would give one format.
- [ ] The username cache is only refreshed by its TTL, so a rename or a deleted account shows the old name for up to 10 minutes.

---

## Admin announcements

Source: client `plans/ANNOUNCEMENTS_PLAN.md` (2026-10-16). Server part done 2026-10-17.

- [ ] Later (optional): cleanup of long-expired announcements.
