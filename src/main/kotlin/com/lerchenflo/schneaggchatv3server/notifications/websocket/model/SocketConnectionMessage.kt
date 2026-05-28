package com.lerchenflo.schneaggchatv3server.notifications.websocket.model

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.lerchenflo.schneaggchatv3server.group.model.GroupResponse
import com.lerchenflo.schneaggchatv3server.message.messagemodel.MessageResponse
import com.lerchenflo.schneaggchatv3server.schneaggmap.model.MapEntryResponse
import com.lerchenflo.schneaggchatv3server.user.usermodel.UserResponse

@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "type"
)
@JsonSubTypes(
    JsonSubTypes.Type(value = SocketConnectionMessage.MessageChange::class, name = "messagechange"),
    JsonSubTypes.Type(value = SocketConnectionMessage.UserChange::class, name = "userchange"),
    JsonSubTypes.Type(value = SocketConnectionMessage.GroupChange::class, name = "groupchange"),
    JsonSubTypes.Type(value = SocketConnectionMessage.FriendRequest::class, name = "friendrequest"),
    JsonSubTypes.Type(value = SocketConnectionMessage.MapChange::class, name = "mapchange"),
)
sealed interface SocketConnectionMessage {

    data class MessageChange(val message: MessageResponse, val newMessage: Boolean, val deleted: Boolean) : SocketConnectionMessage

    data class UserChange(val user: UserResponse, val deleted: Boolean) : SocketConnectionMessage

    data class GroupChange(val group: GroupResponse, val deleted: Boolean) : SocketConnectionMessage

    data class FriendRequest(
        val requestingUser: String,
        val requestingUserName: String,
        val accepted: Boolean,
    ) : SocketConnectionMessage

    data class MapChange(val mapEntry: MapEntryResponse, val newEntry: Boolean, val deleted: Boolean) : SocketConnectionMessage
}
