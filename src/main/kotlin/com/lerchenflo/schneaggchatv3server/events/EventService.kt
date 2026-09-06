@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.events

import com.google.protobuf.LazyStringArrayList.emptyList
import com.lerchenflo.schneaggchatv3server.events.eventmodel.Event
import com.lerchenflo.schneaggchatv3server.events.eventmodel.EventJoinRequest
import com.lerchenflo.schneaggchatv3server.events.eventmodel.EventJoinResponse
import com.lerchenflo.schneaggchatv3server.events.eventmodel.EventRequest
import com.lerchenflo.schneaggchatv3server.events.eventmodel.EventParticipation
import com.lerchenflo.schneaggchatv3server.events.eventmodel.EventParticipationRequest
import com.lerchenflo.schneaggchatv3server.events.eventmodel.EventParticipationStatus
import com.lerchenflo.schneaggchatv3server.events.eventmodel.EventResponse
import com.lerchenflo.schneaggchatv3server.events.eventmodel.EventSyncResponse
import com.lerchenflo.schneaggchatv3server.events.eventmodel.EventVisibility
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
import com.lerchenflo.schneaggchatv3server.util.requireOrLog
import org.bson.types.ObjectId
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException
import kotlin.time.Clock
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

        val serverEvents = eventsLookupService.getFutureEvents()

        //Filter for all events this user can sync
        val availableEvents = serverEvents.filter {
            canAccessEvent(requesterId = requesterId, event = it, friends = friends)
        }

        //Remove all events the user has but are not available anymore
        val availableEventIds = availableEvents.map { it.id.toHexString() }.toSet()
        val eventsToRemove = clientEvents
            .filter { it.key !in availableEventIds } //Remove all events i can not access anymore (expired or friend removed)

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

        val editorNames = userLookupService
            .findAllById(pagedEntries.map { it.creatorId }.distinct())
            .associate { it.id to it.username }

        val paged = pagedEntries.map {
            it.toResponse(creatorName = editorNames[it.creatorId] ?: "Unknown")
        }
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

        val groupId: ObjectId? = existing?.groupId
            ?: if (eventRequest.createGroup) {
                groupService.createGroup(
                    groupName = "event - " + eventRequest.title.trim().take(16),
                    members = emptyList<ObjectId>(), //only creator
                    creatorId = upsertingUser,
                    description = (eventRequest.description).take(200),
                    profilePic = profilePic,
                    createdFromEvent = true,
                    expiresAt = groupExpiresAt
                ).id
            } else null

        if (existing?.groupId != null) {
            val currentGroupExpiresAt = groupLookupService.getGroupById(existing.groupId)?.expiresAt
            if (currentGroupExpiresAt != null) {
                groupService.setGroupExpiresAt(existing.groupId, groupExpiresAt)
            }
        }

        val event = Event(
            id = existing?.id ?: ObjectId.get(),
            creatorId = existing?.creatorId ?: upsertingUser,
            type = eventRequest.type,
            title = eventRequest.title,
            description = eventRequest.description,
            groupId = groupId,
            location = eventRequest.location,
            startDate = startDate,
            closeDate = closeDate,
            invitedUsers = eventRequest.invitedUsers.map { ObjectId(it) },
            //An edit rebuilds the whole document, so the responses have to be carried over explicitly
            participations = existing?.participations
                ?: listOf(EventParticipation(userId = upsertingUser, status = EventParticipationStatus.ACCEPTED, updatedAt = now)),
            visibility = eventRequest.visibility,
            maxUsers = eventRequest.maxUsers,
            groupDeleteDelay = eventRequest.groupDeleteDelay,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
            updatedBy = upsertingUser,
        )

        val response = eventsLookupService.save(event).toResponse(creatorName = userLookupService.getUsername(upsertingUser))

        notificationService.notifyEventUpdate(
            eventResponse = response,
            newEntry = existing == null,
            deleted = false
        )

        // Only for an actual update on an event that already had a group - a brand-new group
        // (whether from a new event or a groupless event gaining one now) already gets its own
        // GROUP_CREATED system message from groupService.createGroup above.
        if (existing?.groupId != null) {
            systemMessageService.groupEvent(
                groupId = existing.groupId,
                eventType = SystemEventType.EVENT_CHANGED,
                actorId = upsertingUser,
                text = event.title,
            )
        }

        return response
    }

    /**
     * Break the event <-> group link. Both flags false = detach only: the event survives with
     * groupId = null and the group survives (keeping its expiresAt). Each true flag deletes that side.
     */
    fun detachEvent(requestingUser: ObjectId, eventId: String, deleteGroup: Boolean, deleteEvent: Boolean) {
        val event = eventsLookupService.findById(eventId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found")

        requireOrLog(
            requestingUser == event.creatorId,
            { "Unauthorized event action - user: ${userLookupService.getUsername(requestingUser)}, eventId: ${event.id.toHexString()}, creator: ${userLookupService.getUsername(event.creatorId)}" }
        ) { "You can not change this event, you are not the creator" }

        val groupId = event.groupId
        val creatorName = userLookupService.getUsername(event.creatorId)

        if (deleteEvent) {
            eventsLookupService.deleteEvent(event)
            notificationService.notifyEventUpdate(
                eventResponse = event.toResponse(creatorName = creatorName),
                newEntry = false,
                deleted = true
            )
        } else {
            val detached = eventsLookupService.save(
                event.copy(groupId = null, updatedAt = Clock.System.now(), updatedBy = requestingUser)
            )
            notificationService.notifyEventUpdate(
                eventResponse = detached.toResponse(creatorName = creatorName),
                newEntry = false,
                deleted = false
            )
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
        val eventResponse = (updatedEvent ?: event)
            .toResponse(creatorName = userLookupService.getUsername(event.creatorId))

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

        val creatorName = userLookupService.getUsername(event.creatorId)

        //null = the write was a no-op (SEEN on an already answered event); nothing changed, nothing to push
        val updated = eventsLookupService.upsertParticipation(
            eventId = event.id,
            userId = requestingUser,
            status = status
        ) ?: return event.toResponse(creatorName = creatorName)

        val response = updated.toResponse(creatorName = creatorName)

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