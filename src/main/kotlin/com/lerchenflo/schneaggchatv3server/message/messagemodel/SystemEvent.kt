package com.lerchenflo.schneaggchatv3server.message.messagemodel

import org.bson.types.ObjectId

/**
 * Kind of server-authored event a `MessageType.SYSTEM` message describes - the in-chat equivalent
 * of WhatsApp's "X joined the group" lines. Written by
 * [com.lerchenflo.schneaggchatv3server.message.system.SystemMessageService].
 */
enum class SystemEventType {
    GROUP_CREATED,
    GROUP_MEMBER_ADDED,
    GROUP_MEMBER_REMOVED,   // kicked by an admin
    GROUP_MEMBER_LEFT,      // left voluntarily
    GROUP_ADMIN_GRANTED,
    GROUP_ADMIN_REVOKED,
    GROUP_NAME_CHANGED,
    GROUP_DESCRIPTION_CHANGED,
    GROUP_PICTURE_CHANGED,
    EVENT_CHANGED,      // an event's details changed - posted into the event's connected group chat
    FRIENDSHIP_ACCEPTED,
    WAKE_SENT,
}

/**
 * Structured payload embedded on a `MessageType.SYSTEM` [Message]. The server does not render a
 * sentence - it hands the client [eventType] plus ids and name snapshots, and the client builds
 * the localized string from its own resources.
 *
 * Names are snapshotted (not just ids) for the same reason [com.lerchenflo.schneaggchatv3server.group.model.GroupMemberResponse.memberName]
 * is: a client must still be able to render "Flo removed Max" once Max is no longer in the group
 * (or synced locally) to resolve their name from.
 */
data class SystemEvent(
    val eventType: SystemEventType,
    val actorId: ObjectId,
    val actorName: String,
    val targets: List<SystemEventParticipant> = emptyList(),
    val text: String? = null,          // new group name, wake reason
    val previousText: String? = null,  // old group name (GROUP_NAME_CHANGED only)
)

data class SystemEventParticipant(
    val userId: ObjectId,
    val userName: String,
)

data class SystemEventResponse(
    val eventType: SystemEventType,
    val actorId: String,
    val actorName: String,
    val targets: List<SystemEventParticipantResponse>,
    val text: String?,
    val previousText: String?,
)

data class SystemEventParticipantResponse(
    val userId: String,
    val userName: String,
)

fun SystemEvent.toSystemEventResponse(): SystemEventResponse {
    return SystemEventResponse(
        eventType = this.eventType,
        actorId = this.actorId.toHexString(),
        actorName = this.actorName,
        targets = this.targets.map { it.toSystemEventParticipantResponse() },
        text = this.text,
        previousText = this.previousText,
    )
}

fun SystemEventParticipant.toSystemEventParticipantResponse(): SystemEventParticipantResponse {
    return SystemEventParticipantResponse(
        userId = this.userId.toHexString(),
        userName = this.userName,
    )
}
