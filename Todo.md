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

## Admin announcements

Source: client `plans/ANNOUNCEMENTS_PLAN.md` (2026-10-16). Server part done 2026-10-17.

- [ ] Later (optional): cleanup of long-expired announcements.
