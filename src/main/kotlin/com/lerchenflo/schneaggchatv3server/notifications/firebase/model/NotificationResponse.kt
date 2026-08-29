package com.lerchenflo.schneaggchatv3server.notifications.firebase.model

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
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
    JsonSubTypes.Type(value = NotificationResponse.EventNotificationResponse::class, name = "event")
)

sealed interface NotificationResponse {

    data class MessageNotificationResponse(
        val msgId: String,
        val senderName: String,
        val groupMessage: Boolean,
        val messageType: MessageType,
        val groupName: String,
        val encodedContent: String,
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
}