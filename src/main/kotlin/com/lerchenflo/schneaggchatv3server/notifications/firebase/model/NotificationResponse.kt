package com.lerchenflo.schneaggchatv3server.notifications.firebase.model

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.lerchenflo.schneaggchatv3server.message.messagemodel.MessageType

@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "type"
)
@JsonSubTypes(
    JsonSubTypes.Type(value = NotificationResponse.MessageNotificationResponse::class, name = "message"),
    JsonSubTypes.Type(value = NotificationResponse.FriendRequestNotificationResponse::class, name = "friend_request"),
    JsonSubTypes.Type(value = NotificationResponse.SystemNotificationResponse::class, name = "system")
)

sealed interface NotificationResponse {

    data class MessageNotificationResponse(
        val msgId: String,
        val senderName: String,
        val groupMessage: Boolean,
        val messageType: MessageType,
        val groupName: String,
        val encodedContent: String
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
}