package com.lerchenflo.schneaggchatv3server.events.eventmodel

import com.lerchenflo.schneaggchatv3server.schneaggmap.model.LatLong
import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.TypeAlias
import org.springframework.data.mongodb.core.mapping.Document
import kotlin.time.Instant

@TypeAlias("event")
@Document("events")
data class Event(
    @Id val id: ObjectId = ObjectId.get(),

    val creatorId: ObjectId,

    val type: EventType,

    val title: String,
    val description: String,

    val groupId: ObjectId?, //Group connected to this event, null = event without a group chat
    val location: LatLong?, //Optional Location
    val startDate: Instant,
    val closeDate: Instant?,

    val invitedUsers: List<ObjectId>,

    /** One entry per user at most - see [EventParticipation]. Default keeps documents written before this field existed loadable. */
    val participations: List<EventParticipation> = emptyList(),

    val visibility: EventVisibility,

    val maxUsers: Int? = null, // Optional cap on how many people can join, null = unlimited

    val groupDeleteDelay: GroupDeleteDelay = GroupDeleteDelay.ONE_DAY, // default matches the previous hardcoded behavior, for events persisted before this field existed

    val createdAt: Instant,
    val updatedAt: Instant,
    val updatedBy: ObjectId,
)

/**
 * True once the event is over: past its close date, or - when it has none - past its start.
 * The server's single definition of "ended", mirrored by the client's `Event.hasEnded`. Keep in
 * sync with the query in [com.lerchenflo.schneaggchatv3server.events.EventsLookupService.getActiveEvents].
 */
fun Event.hasEnded(now: Instant): Boolean = (closeDate ?: startDate) <= now
