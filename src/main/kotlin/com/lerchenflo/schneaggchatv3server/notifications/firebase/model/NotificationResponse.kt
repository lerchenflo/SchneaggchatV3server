package com.lerchenflo.schneaggchatv3server.notifications.firebase.model

import com.lerchenflo.schneaggchatv3server.schneaggmap.MapSightingType
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.lerchenflo.schneaggchatv3server.events.eventmodel.EventChangeKind
import com.lerchenflo.schneaggchatv3server.message.messagemodel.MessageType

@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "_class"
)
@JsonSubTypes(
    JsonSubTypes.Type(value = NotificationResponse.MessageNotificationResponse::class, name = "message"),
    JsonSubTypes.Type(value = NotificationResponse.FriendRequestNotificationResponse::class, name = "friend_request"),
    JsonSubTypes.Type(value = NotificationResponse.SystemNotificationResponse::class, name = "system"),
    JsonSubTypes.Type(value = NotificationResponse.BirthdayNotificationResponse::class, name = "birthday"),
    JsonSubTypes.Type(value = NotificationResponse.WakeNotificationResponse::class, name = "wake"),
    JsonSubTypes.Type(value = NotificationResponse.EventNotificationResponse::class, name = "event"),
    JsonSubTypes.Type(value = NotificationResponse.EventReminderNotificationResponse::class, name = "event_reminder"),
    JsonSubTypes.Type(value = NotificationResponse.EventUpdateNotificationResponse::class, name = "event_update"),
    JsonSubTypes.Type(value = NotificationResponse.MapSightingNotificationResponse::class, name = "map_sighting")
)

sealed interface NotificationResponse {

    data class MessageNotificationResponse(
        val msgId: String,
        val senderName: String,
        val groupMessage: Boolean,
        val messageType: MessageType,
        val groupName: String,
        //Message text for TEXT messages, the emoji for reactions. Sent as plaintext: the old
        //shared-key obfuscation was removed, real confidentiality comes with end-to-end encryption.
        val content: String,
        val senderId: String,
        val receiverId: String,
        val reaction: Boolean = false,
        //The chat this notification belongs to when it is a group message. Empty for 1:1
        //messages, where the chat is identified by senderId instead.
        val groupId: String = "",
        //Lets the client order a provisionally-upserted message correctly without waiting for sync.
        val sendDate: Long = 0L,
        val answerId: String = ""
    ) : NotificationResponse

    //Response for a friend request notification
    data class FriendRequestNotificationResponse(
        val requesterId: String,
        val requesterName: String,
        val accepted: Boolean
    ) : NotificationResponse

    //Response for a system notification
    data class SystemNotificationResponse(
        val title: String,
        val message: String
    ) : NotificationResponse

    data class BirthdayNotificationResponse(
        val birthdayUserId: String,
        val birthdayUserName: String,
        val ownBirthday: Boolean
    ) : NotificationResponse

    //Someone asked to wake this user. Android only - the receiving device plays an alarm.
    data class WakeNotificationResponse(
        val senderId: String,
        val senderName: String,
        val reason: String,
        //Empty when this was a 1:1 wake
        val groupId: String = "",
        val groupName: String = "",
        //How many other people got woken by the same request, so the receiver can see they are
        //not alone. Resolved before dispatch, so it counts devices we sent to - not devices
        //that actually rang.
        val wokenUserCount: Int = 1,
        val wokenDeviceCount: Int = 1
    ) : NotificationResponse

    //Someone created a new event that this user is invited to.
    data class EventNotificationResponse(
        val eventId: String,
        val eventTitle: String,
        val creatorId: String,
        val creatorName: String,
    ) : NotificationResponse

    //An event this user goes to starts in leadMinutes. startDate is epoch millis.
    data class EventReminderNotificationResponse(
        val eventId: String,
        val eventTitle: String,
        val startDate: Long,
        val leadMinutes: Int,
    ) : NotificationResponse

    //An event this user goes to or is invited to moved, got a new location or was cancelled.
    //startDate is the (new) start in epoch millis. In the push data map, changes travels as a
    //JSON array string, e.g. ["START","LOCATION"].
    data class EventUpdateNotificationResponse(
        val eventId: String,
        val eventTitle: String,
        val changes: List<EventChangeKind>,
        val startDate: Long,
        val actorName: String,
    ) : NotificationResponse

    //Police or a mobile radar was seen. Never carries who reported it.
    data class MapSightingNotificationResponse(
        val entryId: String,
        val entryName: String,
        val sightingType: MapSightingType,
    ) : NotificationResponse
}