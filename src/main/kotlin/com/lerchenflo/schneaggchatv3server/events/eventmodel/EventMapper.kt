package com.lerchenflo.schneaggchatv3server.events.eventmodel

import org.bson.types.ObjectId

/** Every user an [EventResponse] names: the creator, the invited users and everyone who responded. */
fun Event.referencedUserIds(): Set<ObjectId> =
    setOf(creatorId) + invitedUsers + participations.map { it.userId }

/**
 * [userNames] maps user ids to usernames and should cover [referencedUserIds]. A missing invited
 * or responding user (deleted account) gets an empty `userName`; a missing creator "Unknown".
 */
fun Event.toResponse(userNames: Map<ObjectId, String>): EventResponse {
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
        invitedUserNames = invitedUsers.map { EventUserNameResponse(userId = it.toHexString(), userName = userNames[it].orEmpty()) },
        participations = participations.map { it.toResponse(userName = userNames[it.userId].orEmpty()) },
        visibility = visibility,
        maxUsers = maxUsers,
        groupDeleteDelay = groupDeleteDelay,
        reminderMinutes = reminderMinutes,
        createdAt = createdAt.toEpochMilliseconds(),
        updatedAt = updatedAt.toEpochMilliseconds(),
        updatedBy = updatedBy.toHexString(),
        creatorName = userNames[creatorId] ?: "Unknown",
    )
}

fun EventParticipation.toResponse(userName: String): EventParticipationResponse {
    return EventParticipationResponse(
        userId = userId.toHexString(),
        userName = userName,
        status = status,
        updatedAt = updatedAt.toEpochMilliseconds(),
    )
}
