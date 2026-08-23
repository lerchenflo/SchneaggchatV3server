package com.lerchenflo.schneaggchatv3server.events.eventmodel

import com.lerchenflo.schneaggchatv3server.schneaggmap.model.LatLong

data class EventResponse(
    val id: String,
    val creatorId: String,
    val type: EventType,
    val title: String,
    val description: String,
    val groupId: String,
    val location: LatLong?,
    val startDate: Long,
    val closeDate: Long?,
    val invitedUsers: List<String>,
    val visibility: EventVisibility,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val updatedBy: String,

    val creatorName: String,
)


data class EventSyncResponse(
    val updatedEvents: List<EventResponse>,
    val deletedEvents: List<String>,
    val moreEntries: Boolean,
)

fun Event.toResponse(creatorName: String): EventResponse {
    return EventResponse(
        id = id.toHexString(),
        creatorId = creatorId.toHexString(),
        type = type,
        title = title,
        description = description,
        groupId = groupId.toHexString(),
        location = location,
        startDate = startDate.toEpochMilliseconds(),
        closeDate = closeDate?.toEpochMilliseconds(),
        invitedUsers = invitedUsers.map { it.toHexString() },
        visibility = visibility,
        createdAt = createdAt.toEpochMilliseconds(),
        updatedAt = updatedAt.toEpochMilliseconds(),
        updatedBy = updatedBy.toHexString(),
        creatorName = creatorName,
    )
}