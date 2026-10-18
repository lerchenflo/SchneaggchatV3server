# Todo

Collected follow-ups and checks for the server, so no thought gets lost. Each item names where it came from. Delete an item once it is done or rejected.

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

- [ ] Account deletion (`UserService.deleteAccount` -> `groupLookupService.leaveAllGroups`) neither bumps the groups' `updatedAt` nor sends `GroupChange`, so co-members keep the deleted user in the member list until another change of that group.
- [ ] `/users/sharelocation` changes the actor's `SelfUserResponse.locationShared` but sends no `UserChange` for the actor's own row.
- [ ] `src/test/.../AuthServiceTokenRotationTest.kt` does not compile: it passes `emailService` to `AuthService` but never declares the mock.

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

Source: user request (2026-10-18). Not started.

Today there are three console formats (`AppLogger` println, Spring/SLF4J, raw `println` / `printStackTrace()`), and the user shows up at the front, in the middle, at the end or not at all. Goal: one format, every line with `username (id)` in the same column, resolved without a DB lookup on the request thread.

- [ ] Infrastructure: `logback-spring.xml` with one console pattern behind an `AsyncAppender` (the "channel": one worker thread, lines stay in order, drained on shutdown; `neverBlock=true`, `discardingThreshold=0`). It also catches Spring's own lines.
- [ ] `LogContextFilter` after `JwtAuthFilter` puts the requester's `userId` (and ip) into the MDC; a `%user` converter in the worker thread turns it into `username (id)` through a cache (TTL about 10 min, invalidated on rename/account delete), id only before Spring is up or when Mongo fails.
- [ ] `AppLogger` stays the facade (same methods), on top of SLF4J, with optional `user`, `tag` and `error` params. `LogUser(id)` in a message resolves a second user (receiver, kicked member) the same way.
- [ ] Migrate call sites: drop the hand-written user text (`GlobalExceptionHandler.logWithUserInfo`, `FirebaseService.describeUser`, `user=` in `RateLimitFilter`, `by $adminId`), move `[Firebase]` / `RATELIMIT` / `ADMIN:` prefixes into `tag`, pass `user` explicitly where no request thread exists (Firebase/APNs coroutines, socket handler, schedulers).
- [ ] Replace `println` and `printStackTrace()` with `AppLogger.error(..., error = e)`; log each 500 once instead of twice.
- [ ] Bug found while checking: `BirthdayNotificationScheduler.kt` logs `"$user.id"`, which prints the whole `User` (email, password hash) followed by ".id". Use `${user.id}`.

---

## Admin announcements

Source: client `plans/ANNOUNCEMENTS_PLAN.md` (2026-10-16). Server part done 2026-10-17.

- [ ] Later (optional): cleanup of long-expired announcements.
