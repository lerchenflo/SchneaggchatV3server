package com.lerchenflo.schneaggchatv3server.message.messagemodel

import com.lerchenflo.schneaggchatv3server.group.groupextensions.model.GroupExtensionType
import org.bson.types.ObjectId

/**
 * Kind of server-authored event a `MessageType.SYSTEM` message describes - the in-chat equivalent
 * of WhatsApp's "X joined the group" lines. Written by
 * [com.lerchenflo.schneaggchatv3server.message.system.SystemMessageService].
 */
enum class SystemEventType {
    GROUP_CREATED,
    GROUP_MEMBER_ADDED,
    GROUP_MEMBER_JOINED_EVENT, // joined by themselves via an event, not added by an admin
    GROUP_MEMBER_REMOVED,   // kicked by an admin
    GROUP_MEMBER_LEFT,      // left voluntarily
    GROUP_ADMIN_GRANTED,
    GROUP_ADMIN_REVOKED,
    GROUP_NAME_CHANGED,
    GROUP_DESCRIPTION_CHANGED,
    GROUP_PICTURE_CHANGED,
    EVENT_CHANGED,      // an event's details changed - posted into the event's connected group chat
    EVENT_START_CHANGED,    // the event's start moved - timestamp is the new start
    EVENT_LOCATION_CHANGED, // the event's location was set, moved or removed
    EVENT_CANCELLED,        // the event was deleted while its group chat stays
    GROUP_EXTENSION_CREATED, // text = title; extensionId + extensionType set
    GROUP_EXTENSION_RENAMED, // text = new title, previousText = old title; extensionId + extensionType set
    GROUP_EXTENSION_DELETED, // text = title; extensionId + extensionType set
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
    val text: String? = null,          // new group name, wake reason, event or extension title
    val previousText: String? = null,  // old group name (GROUP_NAME_CHANGED), old extension title (GROUP_EXTENSION_RENAMED)
    val timestamp: Long? = null,       // epoch millis: the new start (EVENT_START_CHANGED only)
    val extensionId: ObjectId? = null,              // GROUP_EXTENSION_* only
    val extensionType: GroupExtensionType? = null,  // GROUP_EXTENSION_* only
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
    val timestamp: Long?,
    val extensionId: String?,
    val extensionType: GroupExtensionType?,
)

data class SystemEventParticipantResponse(
    val userId: String,
    val userName: String,
)
