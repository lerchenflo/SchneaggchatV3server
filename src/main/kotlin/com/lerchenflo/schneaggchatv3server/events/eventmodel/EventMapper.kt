package com.lerchenflo.schneaggchatv3server.events.eventmodel

fun Event.toResponse(creatorName: String): EventResponse {
    return EventResponse(
        id = id.toHexString(),
        creatorId = creatorId.toHexString(),
        type = type,
        title = title,
        description = description,
        groupId = groupId?.toHexString(),
        location = location,
        startDate = startDate.toEpochMilliseconds(),
        closeDate = closeDate?.toEpochMilliseconds(),
        invitedUsers = invitedUsers.map { it.toHexString() },
        participations = participations.map { it.toResponse() },
        visibility = visibility,
        maxUsers = maxUsers,
        groupDeleteDelay = groupDeleteDelay,
        createdAt = createdAt.toEpochMilliseconds(),
        updatedAt = updatedAt.toEpochMilliseconds(),
        updatedBy = updatedBy.toHexString(),
        creatorName = creatorName,
    )
}

fun EventParticipation.toResponse(): EventParticipationResponse {
    return EventParticipationResponse(
        userId = userId.toHexString(),
        status = status,
        updatedAt = updatedAt.toEpochMilliseconds(),
    )
}
