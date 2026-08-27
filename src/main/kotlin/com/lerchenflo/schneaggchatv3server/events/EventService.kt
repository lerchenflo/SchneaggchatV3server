@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.events

import com.google.protobuf.LazyStringArrayList.emptyList
import com.lerchenflo.schneaggchatv3server.events.eventmodel.Event
import com.lerchenflo.schneaggchatv3server.events.eventmodel.EventJoinRequest
import com.lerchenflo.schneaggchatv3server.events.eventmodel.EventJoinResponse
import com.lerchenflo.schneaggchatv3server.events.eventmodel.EventRequest
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

        val groupId = existing?.groupId
            ?: groupService.createGroup(
                groupName = "event - " + eventRequest.title.trim().take(16),
                members = emptyList<ObjectId>(), //only creator
                creatorId = upsertingUser,
                description = (eventRequest.description).take(200),
                profilePic = profilePic,
                createdFromEvent = true,
                expiresAt = groupExpiresAt
            ).id

        if (existing != null) {
            val currentGroupExpiresAt = groupLookupService.getGroupById(groupId)?.expiresAt
            if (currentGroupExpiresAt != null) {
                groupService.setGroupExpiresAt(groupId, groupExpiresAt)
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

        // Only for an actual update - a brand-new event's group already gets its own
        // GROUP_CREATED system message from groupService.createGroup above.
        if (existing != null) {
            systemMessageService.groupEvent(
                groupId = groupId,
                eventType = SystemEventType.EVENT_CHANGED,
                actorId = upsertingUser,
                text = event.title,
            )
        }

        return response
    }

    fun deleteEvent(requestingUser: ObjectId, eventId: String, deleteConnectedGroup: Boolean) {
        val event = eventsLookupService.findById(eventId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found")

        requireOrLog(
            requestingUser == event.creatorId,
            { "Unauthorized event action - user: ${userLookupService.getUsername(requestingUser)}, eventId: ${event.id.toHexString()}, creator: ${userLookupService.getUsername(event.creatorId)}" }
        ) { "You can not delete this event, you are not the creator" }

        eventsLookupService.deleteEvent(event)

        notificationService.notifyEventUpdate(
            eventResponse = event.toResponse(creatorName = userLookupService.getUsername(event.creatorId)),
            newEntry = false,
            deleted = true
        )

        if (deleteConnectedGroup) {
            groupService.deleteGroup(event.groupId, deletedBy = requestingUser)
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

        //Enforce the optional participant cap - re-joining an existing member must stay a no-op
        event.maxUsers?.let { max ->
            val members = groupLookupService.getGroupMembers(event.groupId)
            require(members.any { it.userid == joiningUser } || members.size < max) { "Event is full" }
        }

        //Add to group
        groupService.addUserToGroup(
            groupId = event.groupId,
            memberId = joiningUser
        )
        notificationService.notifyGroupUpdate(
            groupResponse = groupLookupService.getGroupAsGroupResponse(event.groupId),
            deleted = false
        )

        systemMessageService.groupEvent(
            groupId = event.groupId,
            eventType = SystemEventType.GROUP_MEMBER_JOINED_EVENT,
            actorId = joiningUser,
        )

        return EventJoinResponse(
            groupResponse = groupLookupService.getGroupAsGroupResponse(event.groupId)
        )

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