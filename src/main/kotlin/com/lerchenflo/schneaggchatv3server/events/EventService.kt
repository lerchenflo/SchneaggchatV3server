package com.lerchenflo.schneaggchatv3server.events

import com.google.protobuf.LazyStringArrayList.emptyList
import com.lerchenflo.schneaggchatv3server.events.eventmodel.Event
import com.lerchenflo.schneaggchatv3server.events.eventmodel.EventRequest
import com.lerchenflo.schneaggchatv3server.events.eventmodel.EventResponse
import com.lerchenflo.schneaggchatv3server.events.eventmodel.EventSyncResponse
import com.lerchenflo.schneaggchatv3server.events.eventmodel.toResponse
import com.lerchenflo.schneaggchatv3server.group.GroupService
import com.lerchenflo.schneaggchatv3server.notifications.NotificationService
import com.lerchenflo.schneaggchatv3server.user.UserLookupService
import com.lerchenflo.schneaggchatv3server.user.UserService.IdTimeStamp
import com.lerchenflo.schneaggchatv3server.user.friends.FriendsLookupService
import org.bson.types.ObjectId
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import kotlin.time.Clock
import kotlin.time.Instant

@Service
class EventService(
    private val eventsLookupService: EventsLookupService,
    private val userLookupService: UserLookupService,
    private val friendsLookupService: FriendsLookupService,
    private val groupService: GroupService,
    private val notificationService: NotificationService,
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
            it.public  || //Public events all get synched
                    it.creatorId in friends //Private events only from my friends
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


    fun upsertEvent(upsertingUser: ObjectId, eventRequest: EventRequest): EventResponse {
        val existing = eventRequest.eventId?.let { eventId ->
            eventsLookupService.findById(eventId)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Existing event not found")
        }

        if (existing != null) {
            require(upsertingUser == existing.creatorId) {"You can not edit this event, you are not the creator"}
        }

        val now = Clock.System.now()

        val groupId = existing?.groupId
            ?: groupService.createGroup(
                groupName = "event - " + eventRequest.title.trim().take(16),
                members = emptyList<ObjectId>(), //only creator
                creatorId = upsertingUser,
                description = (eventRequest.description).take(200),
                profilePic = null,
                createdFromEvent = true
            ).id

        val event = Event(
            id = existing?.id ?: ObjectId.get(),
            creatorId = existing?.creatorId ?: upsertingUser,
            type = eventRequest.type,
            title = eventRequest.title,
            description = eventRequest.description,
            groupId = groupId,
            location = eventRequest.location,
            startDate = Instant.fromEpochMilliseconds(eventRequest.startDate),
            closeDate = Instant.fromEpochMilliseconds(eventRequest.closeDate),
            invitedUsers = eventRequest.invitedUsers.map { ObjectId(it) },
            acceptedUsers = eventRequest.acceptedUsers.map { ObjectId(it) },
            public = eventRequest.public,
            createdAt = now,
            updatedAt = now,
            updatedBy = upsertingUser,
        )

        val response = eventsLookupService.save(event).toResponse(creatorName = userLookupService.getUsername(upsertingUser))

        notificationService.notifyEventUpdate(
            eventResponse = response,
            newEntry = true,
            deleted = false
        )

        return response
    }





}