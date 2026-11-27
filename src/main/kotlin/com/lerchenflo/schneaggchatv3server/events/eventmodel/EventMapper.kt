package com.lerchenflo.schneaggchatv3server.events.eventmodel

/** Users appear only by id; the client resolves the names (its user rows, else `/users/lookup`). */
fun Event.toResponse(): EventResponse {
    return EventResponse(
        id = id.toHexString(),
        creatorId = creatorId.toHexString(),
        type = type,
        title = title,
        description = description,
        groupId = groupId?.toHexString(),
        previousGroupId = previousGroupId?.toHexString(),
        location = location,
        startDate = startDate.toEpochMilliseconds(),
        closeDate = closeDate?.toEpochMilliseconds(),
        invitedUsers = invitedUsers.map { it.toHexString() },
        participations = participations.map { it.toResponse() },
        visibility = visibility,
        maxUsers = maxUsers,
        entryFeeCents = entryFeeCents,
        groupDeleteDelay = groupDeleteDelay,
        reminderMinutes = reminderMinutes,
        createdAt = createdAt.toEpochMilliseconds(),
        updatedAt = updatedAt.toEpochMilliseconds(),
        updatedBy = updatedBy.toHexString(),
    )
}

fun EventParticipation.toResponse(): EventParticipationResponse {
    return EventParticipationResponse(
        userId = userId.toHexString(),
        status = status,
        updatedAt = updatedAt.toEpochMilliseconds(),
    )
}
