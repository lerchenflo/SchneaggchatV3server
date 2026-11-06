# Todo

Collected follow-ups and checks for the server, so no thought gets lost. Each item names where it came from. Delete an item once it is done or rejected.

---

## Push notifications: none may get lost

Source: Android push audit in the client repo (2026-10-21) and the Android notification review (2026-10-16). The user's top priority: no case where a notification is swallowed and the user is never told. Done 2026-10-21: `INVALID_ARGUMENT` only deletes the FCM token when the error names the token, and friend-request pushes carry the sender as `requesterId` (see git history). Retrying transient FCM errors was rejected (user, 2026-10-21: not the cause in nearly any case; the SDK already retries HTTP 503 four times).

Older app versions are not supported on any release (user, 2026-11-05): Play forces the update on Android, a popup on iOS. Changes to what the client sends or receives still need the deploy order in `docs/UNIFIED_NOTIFICATIONS_PLAN.md`.
- [ ] **One ack cancels the push for every device** (`notifications/websocket/SocketConnectionHandler.kt:150-177`, `PendingPushRegistry`). Push tokens only know the user, so a minimized desktop app (it keeps its socket) or a second phone in the foreground acks the message and the phone in the pocket never buzzes. Plan: `docs/UNIFIED_NOTIFICATIONS_PLAN.md`, revised 2026-11-06, waits for a go. Next big item (user, 2026-11-05). Fixes this item and the next one.
- [ ] **Confirm-or-push for reactions, friend requests and events** (`notifications/NotificationService.kt`: `notifyReactionAdded` :398, `notifyEventUpdate` :363, `notifyFriendRequest` :479). They count as delivered once the socket write succeeds; new messages already wait for the client's ack (`PendingPushRegistry`). A socket that is closing (app going to the background, signal lost) loses them. The client would have to ack these too.
- [ ] **`setMessagesRead` marks every unread message of the chat read** (`message/MessageService.kt:682-760`). The client's timestamp is only used as `readAt`. So "Mark as read" / "Reply" on a notification also marks a message read that arrived after the notification was shown, and the user never sees it. Needs the client first: v3.0.17 sends its own clock's "now" (chat screen and notification action) and marks every local message read, so filtering on `sendDate <= timestamp` now would leave fresh messages unread on the server whenever the phone's clock is behind (they come back as unread on the next sync). Compatible way: a new optional parameter (e.g. the newest shown message id) that the server only filters on when it is present, sent by the notification actions of a new client.
- [ ] Client follow-up together with this refactor: the Android push path skips a push when its message is already stored (`AppFirebaseMessagingService.kt:137-142`), and a sync (e.g. the one behind "Mark as read") stores other chats' messages before their pushes arrive. To be replaced by "skip only what this device already notified" when the server side above is reworked (user, 2026-10-21). Tracked in the client `plans/Todo.md` (P1).

---

## Socket push after every user/group mutation - client follow-up

Source: client `plans/REPOSITORY_INTERFACE_MIGRATION_PLAN.md` (decision D16, 2026-10-13).

The server now pushes a socket event to every affected user and device for all 10 user/group mutations the client used to follow with a full sync (done 2026-10-17, see git history).

- [ ] Tell the client side once this is deployed, so the client can drop the `syncUsers()` / `syncGroups()` calls after these mutations and the risk note in the client plan can be removed. Prod builds from the branch set in `server_docker/Dockerfile` (`GIT_BRANCH`), so the change only counts once it is on that branch and deployed.

Found while checking, not fixed:

- [ ] Account deletion (`UserService.deleteAccount`) does not touch events: the user's participation rows and the events they created stay. A normal group leave takes the participation back (`GroupService.performUserAction`, `REMOVE_USER`).
- [ ] `/users/sharelocation` now pushes the actor's own row when `locationShared` flips (2026-10-21), but `/users/sync` still misses it on the actor's other devices that were offline: the own row only counts as changed through `User.updatedAt`, which a friendship setting does not bump.

---

## Map setting: pinned friends first

Source: user request (2026-11-05), GitHub issue #946. Client and server done (server 2026-11-05).

The app's Schneaggmap settings have a new synced setting `sortMapUsersByPinned` (default `true`).

- [ ] Client follow-up after the deploy: make the field non-null in the client's `PersonalUserSettings` and `SyncedSettings`. It is nullable there so that a server without the field can't reset the local value on each sync.

---

## Event reminders

Source: user request (2026-10-18), decided in the interview of 2026-11-05. Not started, comes after the device-aware push work.

The event creator picks the reminders per event, several at once, from fixed presets: none, 1 hour, 6 hours, 1 day, 2 days before the start. Replaces the older wish for a day-before and a day-of reminder.

- [ ] Field on `Event`: the picked lead times (empty = none), set on create and edit. Still open: what events from released clients get, which do not send the field.
- [ ] Scheduler next to `notifications/BirthdayNotificationScheduler.kt` that runs every few minutes, not daily like the birthdays, finds due reminders and sends them through `NotificationService` (socket, FCM, APNs).
- [ ] Recipients: users with `EventParticipationStatus.ACCEPTED` plus the creator. No opt-out for now.
- [ ] Send each reminder only once: remember it per event and lead time, reset when `startDate` changes, skip ended events (`Event.hasEnded`) and skip lead times that are already past when the event gets created.
- [ ] Payload: a new `NotificationResponse` subtype or a flag on `EventNotificationResponse`. Older clients drop an unknown `_class`, and a reused `event` type would show as a new event, so check what released clients do with either. Add the APNs fallback title/body in `ApnsService.buildPayload`.
- [ ] Client: lead time picker on the event create and edit screens, show the reminder (texts in all locales) and open the event on tap.

---

## Account emails: one account per address

Source: user bug report (2026-10-21): two accounts with the same email, password reset then threw (`findByEmail` is a single-result query). Fixed 2026-10-21: the email change checks uniqueness, and every email is stored and looked up normalized (startup migration `migrateEmailNormalization`). Prod had no duplicates left (checked 2026-10-21).

Done 2026-11-05: unique `email` index, created in `MainController.ensureUniqueEmailIndex`.

- [ ] Changing the email keeps `emailVerifiedAt`, so a new, unproven address counts as verified (login alerts and resets go to it). Reset it on a real change; the released client already handles an unverified account (verification screen). Later (user, 2026-11-05). Decide then which mails go out: verification to the new address, a notice to the old one (see the `TODO` in `UserService.changeUserProfile`).

---

## FCM: Firebase Installation ID

Source: firebase-admin update check (2026-10-30). firebase-admin 9.10.0 deprecated sending to an FCM token (`Message.Builder.setToken`, `MulticastMessage` tokens) in favor of the Firebase Installation ID (`setFid`). Tokens still work and the release notes give no removal date. The client part is tracked in the client `plans/Todo.md` (P3).

- [ ] Accept and store the FID (`/users/setnotificationtoken` in `user/UserController.kt:51`, `notifications/firebase/model/FirebaseToken.kt`, `ValidationUtils.validateNotificationToken` - an FID has a different format than a token) and send with `setFid` in `FirebaseService.constructMessage` (`notifications/firebase/FirebaseService.kt:470`). Keep `setToken` for the released clients until none of them is left. The token cleanup on FCM "unregistered" errors has to cover FIDs too. Done before the client starts sending FIDs.
