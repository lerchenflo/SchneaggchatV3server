@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.events

import com.lerchenflo.schneaggchatv3server.events.eventmodel.Event
import com.lerchenflo.schneaggchatv3server.events.eventmodel.EventChangeKind
import com.lerchenflo.schneaggchatv3server.events.eventmodel.EventJoinRequest
import com.lerchenflo.schneaggchatv3server.events.eventmodel.EventJoinResponse
import com.lerchenflo.schneaggchatv3server.events.eventmodel.EventRequest
import com.lerchenflo.schneaggchatv3server.events.eventmodel.EventParticipation
import com.lerchenflo.schneaggchatv3server.events.eventmodel.EventParticipationRequest
import com.lerchenflo.schneaggchatv3server.events.eventmodel.EventParticipationStatus
import com.lerchenflo.schneaggchatv3server.events.eventmodel.EventResponse
import com.lerchenflo.schneaggchatv3server.events.eventmodel.EventSyncResponse
import com.lerchenflo.schneaggchatv3server.events.eventmodel.EventVisibility
import com.lerchenflo.schneaggchatv3server.events.eventmodel.SentReminder
import com.lerchenflo.schneaggchatv3server.events.eventmodel.hasEnded
import com.lerchenflo.schneaggchatv3server.events.eventmodel.isReminderHandled
import com.lerchenflo.schneaggchatv3server.events.eventmodel.reminderDueAt
import com.lerchenflo.schneaggchatv3server.events.eventmodel.toDurationOrNull
import com.lerchenflo.schneaggchatv3server.events.eventmodel.toResponse
import com.lerchenflo.schneaggchatv3server.group.GroupLookupService
import com.lerchenflo.schneaggchatv3server.group.GroupService
import com.lerchenflo.schneaggchatv3server.message.messagemodel.SystemEventType
import com.lerchenflo.schneaggchatv3server.message.system.SystemMessageService
import com.lerchenflo.schneaggchatv3server.notifications.NotificationService
import com.lerchenflo.schneaggchatv3server.user.UserLookupService
import com.lerchenflo.schneaggchatv3server.user.UserService.IdTimeStamp
import com.lerchenflo.schneaggchatv3server.user.friends.FriendsLookupService
import com.lerchenflo.schneaggchatv3server.util.AppLogger
import com.lerchenflo.schneaggchatv3server.util.requireOrLog
import org.bson.types.ObjectId
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@Service
class EventService(
    private val eventsLookupService: EventsLookupService,
    private val userLookupService: UserLookupService,
    private val friendsLookupService: FriendsLookupService,
    private val groupService: GroupService,
    private val groupLookupService: GroupLookupService,
    private val notificationService: NotificationService,
    private val systemMessageService: SystemMessageService,
) {

    fun eventIdSync(
        idTimeStamps: List<IdTimeStamp>,
        requesterId: ObjectId,
        page: Int,
        pageSize: Int,
    ) : EventSyncResponse {

        //Create map of id and timestamp for the users entrys
        val clientEvents = idTimeStamps.associate {
            it.id to it.timeStamp
        }

        //Get all this users friends for the privacy sync of the events
        val friends = friendsLookupService.getFriends(requesterId)

        val serverEvents = eventsLookupService.getActiveEvents()

        //Filter for all events this user can sync
        val availableEvents = serverEvents.filter {
            canAccessEvent(requesterId = requesterId, event = it, friends = friends)
        }

        //Remove all events the user has but are not available anymore
        val availableEventIds = availableEvents.map { it.id.toHexString() }.toSet()
        val eventsToRemove = clientEvents
            .filter { it.key !in availableEventIds } //Remove all events i can not access anymore (ended or friend removed)

        val eventsToUpdate = availableEvents
            .filter { event ->
                clientEvents[event.id.toHexString()]?.toLongOrNull()?.let { clientTimestamp ->
                    event.updatedAt.toEpochMilliseconds() > clientTimestamp
                } ?: true
            }

        val start = page * pageSize
        val pagedEntries = eventsToUpdate
            .sortedByDescending { it.updatedAt.toEpochMilliseconds() }
            .drop(start).take(pageSize)

        val paged = pagedEntries.map { it.toResponse() }
        val moreEntries = (start + pageSize) < eventsToUpdate.size

        val deletedEntries = if (page == 0) eventsToRemove.keys.toList() else emptyList()


        return EventSyncResponse(
            updatedEvents = paged,
            deletedEvents = deletedEntries,
            moreEntries = moreEntries
        )
    }


    fun upsertEvent(upsertingUser: ObjectId, eventRequest: EventRequest, profilePic: MultipartFile? = null): EventResponse {
        val existing = eventRequest.eventId?.let { eventId ->
            eventsLookupService.findById(eventId)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Existing event not found")
        }

        if (existing != null) {
            requireOrLog(
                "edit_event_not_creator",
                upsertingUser == existing.creatorId,
                { "Unauthorized event action - user: ${userLookupService.getUsername(upsertingUser)}, eventId: ${existing.id.toHexString()}, creator: ${userLookupService.getUsername(existing.creatorId)}" }
            ) { "You can not edit this event, you are not the creator" }
        }

        val now = Clock.System.now()

        val startDate = Instant.fromEpochMilliseconds(eventRequest.startDate)
        val closeDate = eventRequest.closeDate?.let { Instant.fromEpochMilliseconds(it) }
        //Group expiry stays in sync with the event: closeDate (or startDate if there's no closeDate) plus the
        //creator-chosen delay. NEVER means the group must not auto-expire.
        val groupExpiresAt = eventRequest.groupDeleteDelay.toDurationOrNull()?.let { (closeDate ?: startDate) + it }

        // Parsed before any group is created, so a bad id fails the request without leaving a chat behind
        val invitedUsers = eventRequest.invitedUsers.map { ObjectId(it) }

        val reminderMinutes = eventRequest.reminderMinutes?.distinct()?.sortedDescending()
            ?: existing?.reminderMinutes
            ?: emptyList()

        val event = Event(
            id = existing?.id ?: ObjectId.get(),
            creatorId = existing?.creatorId ?: upsertingUser,
            type = eventRequest.type,
            title = eventRequest.title,
            description = eventRequest.description,
            groupId = existing?.groupId,
            previousGroupId = existing?.previousGroupId,
            location = eventRequest.location,
            startDate = startDate,
            closeDate = closeDate,
            invitedUsers = invitedUsers,
            participations = existing?.participations
                ?: listOf(EventParticipation(userId = upsertingUser, status = EventParticipationStatus.ACCEPTED, updatedAt = now)),
            visibility = eventRequest.visibility,
            maxUsers = eventRequest.maxUsers,
            // Released clients do not send the fee: an edit from one must not reset it
            entryFeeCents = eventRequest.entryFeeCents ?: existing?.entryFeeCents ?: 0L,
            groupDeleteDelay = eventRequest.groupDeleteDelay,
            reminderMinutes = reminderMinutes,
            sentReminders = sentRemindersAfterSave(existing, startDate, reminderMinutes, now),
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
            updatedBy = upsertingUser,
        )

        if (existing == null) {
            return createEvent(event, eventRequest, profilePic, groupExpiresAt)
        }

        // Re-sync the group's timer only when the creator changed something it is derived from
        // (dates or delay). A title-only edit must not silently overwrite a timer a group admin
        // set by hand in the chat, but a real change of the delay - including away from NEVER, or
        // after an admin cleared the timer - has to arm or move it again. The old "only if a timer
        // is currently set" rule could never re-arm a cleared or NEVER timer.
        if (existing.groupId != null) {
            val timerInputsChanged = existing.startDate != startDate
                    || existing.closeDate != closeDate
                    || existing.groupDeleteDelay != eventRequest.groupDeleteDelay
            if (timerInputsChanged && groupLookupService.getGroupById(existing.groupId) != null) {
                groupService.setGroupExpiresAt(existing.groupId, groupExpiresAt)
            }
        }

        // Only the edited fields are written: a group detached or deleted while this edit ran,
        // or a response that came in meanwhile, must not be overwritten with the copy read above
        val updated = eventsLookupService.updateContent(event)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Existing event not found")

        // After the content write, so a reconnect's added invites are not overwritten by it
        val linked = existing.groupId == null && eventRequest.createGroup &&
            linkGroup(updated, upsertingUser, eventRequest, profilePic, groupExpiresAt)

        val response = (if (linked) eventsLookupService.findById(existing.id) ?: updated else updated).toResponse()

        // Content-diff so a no-op resubmission triggers neither the client notification nor the
        // group system message - only a real change (or a newly linked chat) should notify anyone.
        val contentChanged = existing.type != event.type
                || existing.title != event.title
                || existing.description != event.description
                || existing.location != event.location
                || existing.startDate != event.startDate
                || existing.closeDate != event.closeDate
                || existing.invitedUsers != event.invitedUsers
                || existing.visibility != event.visibility
                || existing.maxUsers != event.maxUsers
                || existing.entryFeeCents != event.entryFeeCents
                || existing.groupDeleteDelay != event.groupDeleteDelay
                || existing.reminderMinutes != event.reminderMinutes

        if (contentChanged || linked) {
            notificationService.notifyEventUpdate(
                eventResponse = response,
                newEntry = false,
                deleted = false
            )
        }

        val changes = buildList {
            if (existing.startDate != event.startDate) add(EventChangeKind.START)
            if (existing.location != event.location) add(EventChangeKind.LOCATION)
        }

        // Only for an actual update on an event that already had a group - a chat linked just now
        // already got GROUP_CREATED (new chat) or EVENT_GROUP_RELINKED (reconnected chat) in
        // linkGroup. And only if the event's content actually changed - a no-op resubmission must
        // not spam the group chat.
        // Start and location get their own message each, every other field the generic one. The
        // reminders are left out: they are the creator's settings, no news for the chat.
        if (existing.groupId != null && contentChanged) {
            val otherFieldsChanged = existing.type != event.type
                    || existing.title != event.title
                    || existing.description != event.description
                    || existing.closeDate != event.closeDate
                    || existing.invitedUsers != event.invitedUsers
                    || existing.visibility != event.visibility
                    || existing.maxUsers != event.maxUsers
                    || existing.entryFeeCents != event.entryFeeCents
                    || existing.groupDeleteDelay != event.groupDeleteDelay

            if (EventChangeKind.START in changes) {
                systemMessageService.groupEvent(
                    groupId = existing.groupId,
                    eventType = SystemEventType.EVENT_START_CHANGED,
                    actorId = upsertingUser,
                    text = event.title,
                    timestamp = event.startDate.toEpochMilliseconds(),
                )
            }
            if (EventChangeKind.LOCATION in changes) {
                systemMessageService.groupEvent(
                    groupId = existing.groupId,
                    eventType = SystemEventType.EVENT_LOCATION_CHANGED,
                    actorId = upsertingUser,
                    text = event.title,
                )
            }
            if (otherFieldsChanged) {
                systemMessageService.groupEvent(
                    groupId = existing.groupId,
                    eventType = SystemEventType.EVENT_CHANGED,
                    actorId = upsertingUser,
                    text = event.title,
                )
            }
        }

        if (changes.isNotEmpty() && !event.hasEnded(now)) {
            notificationService.notifyEventChangeAlert(
                recipients = changeAlertRecipients(event) - upsertingUser,
                eventId = response.id,
                eventTitle = event.title,
                changes = changes,
                startDate = event.startDate,
                endDate = event.closeDate ?: event.startDate,
                actorId = upsertingUser,
            )
        }

        return response
    }

    /**
     * Saves a brand-new event, with a new group chat of its own when asked. The chat has to exist
     * first (its id goes into the event), so should the event save fail, the chat is deleted again -
     * otherwise every retry would leave one more chat without an event behind.
     */
    private fun createEvent(event: Event, eventRequest: EventRequest, profilePic: MultipartFile?, groupExpiresAt: Instant?): EventResponse {
        val groupId = if (eventRequest.createGroup) {
            createEventGroup(event.creatorId, eventRequest, profilePic, groupExpiresAt)
        } else null

        val saved = try {
            eventsLookupService.save(event.copy(groupId = groupId))
        } catch (e: Exception) {
            groupId?.let { orphan ->
                runCatching { groupService.deleteGroup(orphan, deletedBy = event.creatorId) }
                    .onFailure { AppLogger.error("Removing the chat of a failed event save failed: ${it.message}", tag = "event_orphan_group_cleanup_failed", error = it) }
            }
            throw e
        }

        val response = saved.toResponse()
        notificationService.notifyEventUpdate(
            eventResponse = response,
            newEntry = true,
            deleted = false
        )
        return response
    }

    private fun createEventGroup(creatorId: ObjectId, eventRequest: EventRequest, profilePic: MultipartFile?, groupExpiresAt: Instant?): ObjectId =
        groupService.createGroup(
            groupName = eventRequest.title.trim(), //Full title, the client marks event groups with an icon
            members = emptyList(), //only creator
            creatorId = creatorId,
            description = eventRequest.description.take(200),
            profilePic = profilePic,
            createdFromEvent = true,
            expiresAt = groupExpiresAt
        ).id

    /**
     * Gives a groupless [event] a group chat on edit: the chat it was detached from
     * ([Event.previousGroupId]) while that chat still exists and the creator is still in it, else a
     * new one. The link is only written while the event is still groupless, so two saves at the
     * same moment can not each attach a chat; a new chat that lost that race is deleted again.
     *
     * Everyone who accepted the event goes straight into the chat, so they stay part of it
     * instead of having to join again (no friendship check, same as a regular join). A
     * reconnected chat also takes its members back as going - being in an event's group is what
     * counts as accepting it, and for an invite-only event that needs the invite too - and its
     * timer follows the event again.
     *
     * @return true when this call linked a chat.
     */
    private fun linkGroup(event: Event, upsertingUser: ObjectId, eventRequest: EventRequest, profilePic: MultipartFile?, groupExpiresAt: Instant?): Boolean {
        val oldGroupId = event.previousGroupId?.takeIf { groupLookupService.isUserInGroup(event.creatorId, it) }
        val groupId = oldGroupId ?: createEventGroup(event.creatorId, eventRequest, profilePic, groupExpiresAt)

        val linked = eventsLookupService.linkGroup(event.id, groupId, updatedBy = upsertingUser)
        if (linked == null) {
            // Another save gave the event a chat first (or the event is gone) - that one stays
            if (oldGroupId == null) groupService.deleteGroup(groupId, deletedBy = upsertingUser)
            return false
        }

        // The old chat may have been deleted between the check above and the link. deleteGroup
        // marks the group deleted before it looks for its event, so either it found and detached
        // the event, or the group shows as deleted here.
        if (oldGroupId != null && groupLookupService.getGroupById(oldGroupId) == null) {
            eventsLookupService.detachGroup(event.id, oldGroupId, previousGroupId = null, updatedBy = null)
            return false
        }

        val members = groupLookupService.getGroupMembers(groupId).map { it.userid }.toSet()

        val acceptedOutside = linked.participations
            .filter { it.status == EventParticipationStatus.ACCEPTED && it.userId != linked.creatorId && it.userId !in members }
            .map { it.userId }
            .distinct()
        acceptedOutside.forEach { userId ->
            try {
                groupService.addUserToGroup(groupId = groupId, memberId = userId)
            } catch (_: IllegalArgumentException) {
                // joined the chat at the same moment - already where they belong
            }
        }

        if (oldGroupId != null) {
            val acceptedIds = linked.participations
                .filter { it.status == EventParticipationStatus.ACCEPTED }
                .map { it.userId }
                .toSet()
            members.filter { it != linked.creatorId && it !in acceptedIds }.forEach { memberId ->
                if (linked.visibility == EventVisibility.INVITED_FRIENDS_ONLY) {
                    eventsLookupService.addInvitedUser(linked.id, memberId)
                }
                eventsLookupService.upsertParticipation(linked.id, memberId, EventParticipationStatus.ACCEPTED)
            }

            groupService.setGroupExpiresAt(groupId, groupExpiresAt)

            systemMessageService.groupEvent(
                groupId = groupId,
                eventType = SystemEventType.EVENT_GROUP_RELINKED,
                actorId = upsertingUser,
                text = linked.title,
            )
        }

        if (acceptedOutside.isNotEmpty()) {
            groupService.touchGroup(groupId)
            notificationService.notifyGroupUpdate(
                groupResponse = groupLookupService.getGroupAsGroupResponse(groupId),
                deleted = false
            )
        }

        return true
    }

    /**
     * The handled reminders an edit keeps: those of the new start (all others are stale once the
     * start moved). On top, a reminder that is new - a new lead time, a moved start or a new
     * event - and already past is marked handled, so it is never sent late. A reminder that was
     * set before and just became due stays open: the scheduler sends it on its next run.
     *
     * Re-reads the stored entries so a reminder the scheduler claimed while this edit ran is kept.
     */
    private fun sentRemindersAfterSave(existing: Event?, startDate: Instant, reminderMinutes: List<Int>, now: Instant): List<SentReminder> {
        val stored = existing?.let { eventsLookupService.findById(it.id)?.sentReminders ?: it.sentReminders }.orEmpty()
        val kept = stored.filter { it.startDate == startDate }.distinct()

        val alreadySet = existing?.takeIf { it.startDate == startDate }?.reminderMinutes.orEmpty().toSet()
        val skipped = reminderMinutes
            .filter { lead -> lead !in alreadySet && kept.none { it.leadMinutes == lead } }
            .filter { lead -> startDate - lead.minutes <= now }
            .map { SentReminder(leadMinutes = it, startDate = startDate) }

        return kept + skipped
    }

    /** Going users plus invited users who did not answer at all (no SEEN, no DISMISSED entry). */
    private fun changeAlertRecipients(event: Event): Set<ObjectId> {
        val answered = event.participations.map { it.userId }.toSet()
        val going = event.participations.filter { it.status == EventParticipationStatus.ACCEPTED }.map { it.userId }
        return (going + event.invitedUsers.filter { it !in answered }).toSet()
    }

    /** Going users plus the creator. */
    private fun reminderRecipients(event: Event): Set<ObjectId> =
        (event.participations.filter { it.status == EventParticipationStatus.ACCEPTED }.map { it.userId } + event.creatorId).toSet()

    /**
     * Sends every due reminder of the upcoming events, see [Event.reminderMinutes]. When several
     * reminders of one event are due at once (the server was down), only the one with the
     * shortest lead time is sent and the others are marked handled with it. An event with a group
     * chat also gets an [SystemEventType.EVENT_REMINDER] system message there, once per sent reminder.
     */
    fun sendDueReminders(now: Instant = Clock.System.now()) {
        eventsLookupService.getUpcomingEventsWithReminders(now).forEach { event ->
            runCatching {
                val due = event.reminderMinutes.filter { event.reminderDueAt(it) <= now && !event.isReminderHandled(it) }
                if (due.isEmpty()) return@forEach

                val lead = due.min()
                if (!eventsLookupService.claimReminders(event, claimedLead = lead, leadMinutes = due)) return@forEach

                notificationService.notifyEventReminder(
                    recipients = reminderRecipients(event),
                    eventId = event.id.toHexString(),
                    eventTitle = event.title,
                    startDate = event.startDate,
                    leadMinutes = lead,
                )

                // Seen by every group member; silent, the reminder push above is the notification
                event.groupId?.let { groupId ->
                    systemMessageService.groupEvent(
                        groupId = groupId,
                        eventType = SystemEventType.EVENT_REMINDER,
                        actorId = event.creatorId,
                        text = event.title,
                        timestamp = event.startDate.toEpochMilliseconds(),
                        leadMinutes = lead,
                    )
                }
            }.onFailure { e ->
                AppLogger.error("Event reminder for ${event.id.toHexString()} failed: ${e.message}", tag = "send_event_reminder_failed", error = e)
            }
        }
    }

    /**
     * Break the event <-> group link. Both flags false = detach only: the event survives with
     * groupId = null and the group survives (keeping its expiresAt). The event remembers that chat
     * as previousGroupId, so a later createGroup reconnects it. Each true flag deletes that side.
     */
    fun detachEvent(requestingUser: ObjectId, eventId: String, deleteGroup: Boolean, deleteEvent: Boolean) {
        val event = eventsLookupService.findById(eventId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found")

        requireOrLog(
            "detach_event_not_creator",
            requestingUser == event.creatorId,
            { "Unauthorized event action - user: ${userLookupService.getUsername(requestingUser)}, eventId: ${event.id.toHexString()}, creator: ${userLookupService.getUsername(event.creatorId)}" }
        ) { "You can not change this event, you are not the creator" }

        val groupId = event.groupId
        val now = Clock.System.now()

        if (deleteEvent) {
            eventsLookupService.deleteEvent(event)
            notificationService.notifyEventUpdate(
                eventResponse = event.toResponse(),
                newEntry = false,
                deleted = true
            )

            // Deleting an event that is over is cleanup, not a cancellation
            if (!event.hasEnded(now)) {
                notificationService.notifyEventChangeAlert(
                    recipients = changeAlertRecipients(event) - requestingUser,
                    eventId = event.id.toHexString(),
                    eventTitle = event.title,
                    changes = listOf(EventChangeKind.CANCELLED),
                    startDate = event.startDate,
                    endDate = event.closeDate ?: event.startDate,
                    actorId = requestingUser,
                )
                if (groupId != null && !deleteGroup) {
                    systemMessageService.groupEvent(
                        groupId = groupId,
                        eventType = SystemEventType.EVENT_CANCELLED,
                        actorId = requestingUser,
                        text = event.title,
                    )
                }
            }
        } else if (groupId != null) {
            // A kept chat is remembered (while the creator is still in it), so turning the group
            // on again in the event reconnects this chat instead of making a second one
            val keptChat = groupId.takeIf { !deleteGroup && groupLookupService.isUserInGroup(requestingUser, it) }
            eventsLookupService.detachGroup(
                eventId = event.id,
                groupId = groupId,
                previousGroupId = keptChat,
                updatedBy = requestingUser,
            )?.let { detached ->
                notificationService.notifyEventUpdate(
                    eventResponse = detached.toResponse(),
                    newEntry = false,
                    deleted = false
                )
                if (!deleteGroup) {
                    systemMessageService.groupEvent(
                        groupId = groupId,
                        eventType = SystemEventType.EVENT_DETACHED,
                        actorId = requestingUser,
                        text = event.title,
                    )
                }
            }
        }

        if (deleteGroup && groupId != null) {
            // The event side is already handled above, so the group must not cascade into it
            groupService.deleteGroup(groupId, deletedBy = requestingUser, deleteConnectedEvent = false)
        }
    }

    fun joinEvent(joiningUser: ObjectId, eventJoinRequest: EventJoinRequest): EventJoinResponse {

        val event = eventsLookupService.findById(eventJoinRequest.eventId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found")

        //Check if can access event
        requireOrLog(
            "join_event_no_access",
            canAccessEvent(requesterId = joiningUser, event = event),
            { "Unauthorized event action - user: ${userLookupService.getUsername(joiningUser)}, eventId: ${event.id.toHexString()}: Cannot access event" }
        ) { "You can not access this event" }

        val groupId = event.groupId
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "This event has no group to join")

        //Enforce the optional participant cap - re-joining an existing member must stay a no-op
        event.maxUsers?.let { max ->
            val members = groupLookupService.getGroupMembers(groupId)
            require(members.any { it.userid == joiningUser } || members.size < max) { "Event is full" }
        }

        //Add to group
        groupService.addUserToGroup(
            groupId = groupId,
            memberId = joiningUser
        )
        groupService.touchGroup(groupId)
        notificationService.notifyGroupUpdate(
            groupResponse = groupLookupService.getGroupAsGroupResponse(groupId),
            deleted = false
        )

        systemMessageService.groupEvent(
            groupId = groupId,
            eventType = SystemEventType.GROUP_MEMBER_JOINED_EVENT,
            actorId = joiningUser,
        )

        //Joining the group is the accept for a group event - the participation list must not disagree with it
        val updatedEvent = eventsLookupService.upsertParticipation(
            eventId = event.id,
            userId = joiningUser,
            status = EventParticipationStatus.ACCEPTED
        )
        val eventResponse = (updatedEvent ?: event).toResponse()

        if (updatedEvent != null) {
            notificationService.notifyEventUpdate(
                eventResponse = eventResponse,
                newEntry = false,
                deleted = false
            )
        }

        return EventJoinResponse(
            groupResponse = groupLookupService.getGroupAsGroupResponse(groupId),
            event = eventResponse
        )

    }



    /**
     * Records how [requestingUser] responded to an event: SEEN when they opened it, ACCEPTED or
     * DISMISSED when they answered it. Returns the current event either way, so a caller whose
     * write was a no-op (SEEN on an event they already responded to) still gets fresh state.
     */
    fun setParticipation(requestingUser: ObjectId, request: EventParticipationRequest): EventResponse {
        val event = eventsLookupService.findById(request.eventId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found")

        requireOrLog(
            "set_event_participation_no_access",
            canAccessEvent(requesterId = requestingUser, event = event),
            { "Unauthorized event action - user: ${userLookupService.getUsername(requestingUser)}, eventId: ${event.id.toHexString()}: Cannot access event" }
        ) { "You can not access this event" }

        //Being in the event group IS the accept, so the group member list decides for a group event:
        //a member can not dismiss it (they have to leave the group), and anything weaker than
        //ACCEPTED becomes ACCEPTED. That second rule also repairs members who joined before
        //participations existed - their first open writes the entry they should have had.
        val isGroupMember = event.groupId != null && groupLookupService.isUserInGroup(requestingUser, event.groupId)

        require(!(isGroupMember && request.status == EventParticipationStatus.DISMISSED)) {
            "You joined this event, leave the group chat first"
        }

        //Left as the requested status when the member is already marked ACCEPTED, so a repeated
        //SEEN stays the silent no-op it is for everyone else instead of rewriting the entry
        val alreadyAccepted = eventsLookupService.findParticipation(event, requestingUser)
            ?.status == EventParticipationStatus.ACCEPTED
        val status = if (isGroupMember && !alreadyAccepted) EventParticipationStatus.ACCEPTED else request.status

        //Groupless events have no member list, so their cap can only be enforced through the accepts
        if (status == EventParticipationStatus.ACCEPTED && event.groupId == null) {
            event.maxUsers?.let { max ->
                val accepted = event.participations.filter { it.status == EventParticipationStatus.ACCEPTED }
                require(accepted.any { it.userId == requestingUser } || accepted.size < max) { "Event is full" }
            }
        }

        //null = the write was a no-op (SEEN on an already answered event); nothing changed, nothing to push
        val updated = eventsLookupService.upsertParticipation(
            eventId = event.id,
            userId = requestingUser,
            status = status
        ) ?: return event.toResponse()

        val response = updated.toResponse()

        //updatedBy stays untouched - it means "last content editor", and a response is not an edit
        notificationService.notifyEventUpdate(
            eventResponse = response,
            newEntry = false,
            deleted = false
        )

        return response
    }


    private fun canAccessEvent(requesterId: ObjectId, event: Event): Boolean {
        //Get all this users friends for the privacy sync of the events
        val friends = friendsLookupService.getFriends(requesterId)
        return canAccessEvent(requesterId, event, friends)
    }

    private fun canAccessEvent(requesterId: ObjectId, event: Event, friends: List<ObjectId>): Boolean {
        if (event.creatorId == requesterId) return true
        return when (event.visibility) {
            EventVisibility.PUBLIC -> true //Public events all get synched
            EventVisibility.FRIENDS_ONLY -> event.creatorId in friends //Only the creator's friends can access
            EventVisibility.INVITED_FRIENDS_ONLY -> requesterId in event.invitedUsers //Only explicitly invited users can access
        }
    }





}