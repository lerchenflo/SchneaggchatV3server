# Todo

Collected follow-ups and checks for the server, so no thought gets lost. Each item names where it came from. Delete an item once it is done or rejected.

---

## From the client (handovers)

**Next** (user, 2026-11-13: it comes right after "User names by id"): "Feedback: edit entries and own comments". ("Profile picture privacy setting" and "User names by id" are built on `beta`, 2026-11-15; contracts in the client plans `SchneaggchatV3/plans/PROFILE_PICTURE_PRIVACY_PLAN.md` and `SchneaggchatV3/plans/USER_NAMES_BY_ID_PLAN.md`.)

- [ ] **Feedback: edit entries and own comments** (client session, user approved 2026-11-12; client `plans/Todo.md` "Feedback entries can't be edited", essential for the next update). Today `FeedbackController` has no edit endpoint.
  - Who may edit an entry: its creator and admins. Seeded entries stay read-only for everyone (the seed file is their source and would overwrite edits).
  - Editable entry fields: `title`, `description`, `tags`, `location`, `type` (FEATURE / BUG). Same validation as create. When `type` changes, the status resets to the start status of the new type. `premium` is not editable.
  - Comments: each user may edit the text of their own comments (author only).
  - After an edit: the entry or comment shows an "edited" marker. An edit is no news for others: it must not move `activityAt` (no "Updated" label, no home badge). The edited entry must still come back through `/feedback/sync`, like the seed changes that move `updatedAt` only.
  - What the client sends: entry edit with `entryId`, `title`, `description`, `tags`, `location`, `type` (same value types as `FeedbackCreateRequest`); comment edit with `commentId`, `text`. It reads back the updated `FeedbackEntryResponse` / `FeedbackCommentResponse`.
  - New fields the client reads: `canEdit` on `FeedbackEntryResponse` and on `FeedbackCommentResponse` (like `canDelete`, so the client only shows the edit action where it is allowed), and `editedAt` (epoch ms, null when never edited) on both, for the marker.
  - Compatibility: released clients ignore unknown fields.
  - Please reply to the client (SchneaggchatV3 session or its `plans/Todo.md`) with the contract you built (paths, request and field names); the client builds its side after that.

---

## Road to end-to-end encryption

Source: client session 2026-11-12, plan written on the user's request.

- [ ] **Plan: `docs/E2E_ROADMAP_SERVER_PLAN.md`** (client side: `SchneaggchatV3/plans/E2E_ROADMAP_PLAN.md`). Fixed order: the client first merges its inbound message handling (no server work), then chat-based addressing ships in one release with the client (`chatId` replaces `receiverId` + `groupMessage`, new `chats` / `chatmembers` collections, `/chats/*` replaces `/groups/*`, data migration before serving), then E2E Phase 0 (key endpoints, share store, backup store). Section 2 of the plan is the wire contract; reply to the client with the contract as built. Nothing is approved to build yet: the user starts each stage.
- [ ] `docs/UNIFIED_NOTIFICATIONS_PLAN.md` names the client plan `DEVICE_AWARE_PUSH_PLAN.md`, which was deleted on 2026-11-12 (superseded by this server plan; text in the client's git history at `ca7d0ee7`). Drop or reword the reference.

---

## GitHub issues

Source: open issues of `lerchenflo/SchneaggchatV3server`, read 2026-11-06. Close an issue on GitHub when its item here is done.

- [ ] #278 Website: `static/web_images/screens/02-chat.webp` and `05-umfrage.webp` show the same poll, only scrolled differently. Replace one of them or drop it from the page.
- [ ] #270 Pushes reach every device without a socket: phases 1-4 are built on `beta`, not on `main` yet. The rest is in `docs/UNIFIED_NOTIFICATIONS_PLAN.md`.
- [ ] #268 Server update script: check the Redis key `schneaggchat:deploy:requested` every 5 minutes and run the update. `POST /server/update` in `website/admin/AdminController.kt` sets the key, but nothing in `scripts/` or `server_docker/` reads it.
- [ ] Client repo #917: a new login syncs all messages and the server answers with rate limit errors. No cause found yet, re-check against the current server first (comment of 2026-10-17: wait for the server update).
- [ ] #223 Delete message images older than 1.5 months. The image message stays, only without content to load. `core/DatabaseCleanupTask.kt` does not touch message images today, and clients must handle the missing image.

---

## Push notifications: none may get lost

Source: Android push audit in the client repo (2026-10-21) and the Android notification review (2026-10-16). The user's top priority: no case where a notification is swallowed and the user is never told. Retrying transient FCM errors was rejected (user, 2026-10-21: not the cause in nearly any case; the SDK already retries HTTP 503 four times).

Older app versions are not supported on any release (user, 2026-11-05): Play forces the update on Android, a popup on iOS. Changes to what the client sends or receives still need the deploy order in `docs/UNIFIED_NOTIFICATIONS_PLAN.md`. Per-device delivery is built on `beta` (2026-11-06, phases 1-4 of that plan). Phases 5-8 are open.
- [ ] **Confirm-or-push for reactions, friend requests and events** (`Delivery.Alert.awaitAck`, phase 5 of the plan). They count as delivered once the socket write succeeds; new messages already wait for the client's ack (`PendingPushRegistry`). A socket that is closing (app going to the background, signal lost) loses them. The client has to ack these too.
- [ ] **`setMessagesRead` marks every unread message of the chat read** (`MessageService.setMessagesRead`). The client's timestamp is only used as `readAt`. So "Mark as read" / "Reply" on a notification also marks a message read that arrived after the notification was shown, and the user never sees it. Needs the client first: v3.0.17 sends its own clock's "now" (chat screen and notification action) and marks every local message read, so filtering on `sendDate <= timestamp` now would leave fresh messages unread on the server whenever the phone's clock is behind (they come back as unread on the next sync). Compatible way: a new optional parameter (e.g. the newest shown message id) that the server only filters on when it is present, sent by the notification actions of a new client.
- [ ] **APNs sends that fail are lost.** `ApnsService.onSendFailed` and rejections with 5XX or `TooManyRequests` only log, and Pushy leaves the re-send to the caller. The decision of 2026-10-21 against retries was about FCM, whose SDK retries itself. Apple allows a retry of 5XX after 15 minutes and of `TooManyRequests` after a delay (iOS push check, 2026-11-06).
- [ ] Client follow-up together with this refactor: the Android push path skips a push when its message is already stored (`AppFirebaseMessagingService.kt:137-142`), and a sync (e.g. the one behind "Mark as read") stores other chats' messages before their pushes arrive. To be replaced by "skip only what this device already notified" when the server side above is reworked (user, 2026-10-21). Tracked in the client `plans/Todo.md` (P1).

---

## Found during the socket push work

Source: check of the user and group socket pushes (2026-10-17).

- [ ] Account deletion (`UserService.deleteAccount`) does not touch events: the user's participation rows and the events they created stay. A normal group leave takes the participation back (`GroupService.performUserAction`, `REMOVE_USER`).
- [ ] `/users/sharelocation` now pushes the actor's own row when `locationShared` flips (2026-10-21), but `/users/sync` still misses it on the actor's other devices that were offline: the own row only counts as changed through `User.updatedAt`, which a friendship setting does not bump.

---

## Map setting: pinned friends first

Source: user request (2026-11-05), GitHub issue #946.

The app's Schneaggmap settings have a new synced setting `sortMapUsersByPinned` (default `true`).

- [ ] Client follow-up after the deploy: make the field non-null in the client's `PersonalUserSettings` and `SyncedSettings`. It is nullable there so that a server without the field can't reset the local value on each sync.

---

## Feedback board: pinning and cleanup

Source: user request (2026-11-06), GitHub issue #947. The client items are in the app repo's `plans/Todo.md`.

- [ ] Next release: remove the deprecated `GET /feedback/list` (`FeedbackController.list`, `FeedbackService.list`, `FeedbackListResponse`) and `FeedbackEntryResponse.descriptionPreview` with `previewOf`. Only clients from before `/feedback/sync` (2026-11-08) still use them; remove once the client release with the sync is out.
- [ ] Pinned requests: a `pinned` field on `FeedbackEntry`, an admin endpoint guarded by `AdminGuard` like `/feedback/status`, and the field in the entry response. Old clients ignore it.

---

## Event reminders

Source: user request (2026-10-18), decided in the interview of 2026-11-05 and 2026-11-08 (client plan `SchneaggchatV3/plans/EVENT_NOTIFICATIONS_PLAN.md`).

Server part built 2026-11-08 (on `beta`): `Event.reminderMinutes` + `sentReminders`, `EventReminderScheduler` (every 60 s), push `event_reminder` / socket `eventreminder`, change alert push `event_update` / socket `eventupdatealert` (START, LOCATION, CANCELLED), system messages `EVENT_START_CHANGED` / `EVENT_LOCATION_CHANGED` / `EVENT_CANCELLED`, `userName` on participations and `invitedUserNames` on events.

- [ ] Release order: the client release with these types must go out before this server reaches `main`. Older apps show the new pushes as a generic notification and the new system messages as "Chat updated".
- [ ] Client: reminder picker, showing reminders and change alerts, the new system message texts, names in the participant list (client agent, see the plan).

---

## Account emails: one account per address

Source: user bug report (2026-10-21): two accounts with the same email, password reset then threw (`findByEmail` is a single-result query).

- [ ] GitHub issue #92: changing the email keeps `emailVerifiedAt`, so a new, unproven address counts as verified (login alerts and resets go to it). Reset it on a real change; the released client already handles an unverified account (verification screen). Later (user, 2026-11-05). Decide then which mails go out: verification to the new address, a notice to the old one (see the `TODO` in `UserService.changeUserProfile`).

---

## FCM: Firebase Installation ID

Source: firebase-admin update check (2026-10-30). firebase-admin 9.10.0 deprecated sending to an FCM token (`Message.Builder.setToken`, `MulticastMessage` tokens) in favor of the Firebase Installation ID (`setFid`). Tokens still work and the release notes give no removal date. The client part is tracked in the client `plans/Todo.md` (P3).

- [ ] Accept and store the FID (`/users/setnotificationtoken` in `user/UserController.kt:51`, `notifications/firebase/model/FirebaseToken.kt`, `ValidationUtils.validateNotificationToken` - an FID has a different format than a token) and send with `setFid` in `FirebaseService.constructMessage`. Keep `setToken` for the released clients until none of them is left. The token cleanup on FCM "unregistered" errors has to cover FIDs too. Done before the client starts sending FIDs.

