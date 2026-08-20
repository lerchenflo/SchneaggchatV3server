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

    val groupId: ObjectId, //Group connected to this event
    val location: LatLong?, //Optional Location
    val startDate: Instant?,
    val closeDate: Instant?,

    val invitedUsers: List<ObjectId>,
    val acceptedUsers: List<ObjectId>,

    val public: Boolean,

    val createdAt: Instant,
    val updatedAt: Instant,
    val updatedBy: ObjectId,
)