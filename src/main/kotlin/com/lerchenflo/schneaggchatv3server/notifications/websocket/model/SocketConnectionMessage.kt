package com.lerchenflo.schneaggchatv3server.notifications.websocket.model

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.lerchenflo.schneaggchatv3server.group.model.GroupResponse
import com.lerchenflo.schneaggchatv3server.message.messagemodel.MessageResponse
import com.lerchenflo.schneaggchatv3server.schneaggmap.model.MapEntryResponse
import com.lerchenflo.schneaggchatv3server.schneaggmap.userlocations.model.FriendLocationPayload
import com.lerchenflo.schneaggchatv3server.schneaggmap.userlocations.model.FriendLocationSnapshot
import com.lerchenflo.schneaggchatv3server.schneaggmap.userlocations.model.SnailTrailPointPayload
import com.lerchenflo.schneaggchatv3server.user.usermodel.UserResponse

@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "_class"
)
@JsonSubTypes(
    JsonSubTypes.Type(value = SocketConnectionMessage.MessageChange::class, name = "messagechange"),
    JsonSubTypes.Type(value = SocketConnectionMessage.UserChange::class, name = "userchange"),
    JsonSubTypes.Type(value = SocketConnectionMessage.GroupChange::class, name = "groupchange"),
    JsonSubTypes.Type(value = SocketConnectionMessage.FriendRequest::class, name = "friendrequest"),
    JsonSubTypes.Type(value = SocketConnectionMessage.MapChange::class, name = "mapchange"),
    JsonSubTypes.Type(value = SocketConnectionMessage.LocationUpdate::class, name = "locationupdate"),
    JsonSubTypes.Type(value = SocketConnectionMessage.FriendLocationChange::class, name = "friendlocationchange"),
    JsonSubTypes.Type(value = SocketConnectionMessage.FriendLocationsSnapshot::class, name = "friendlocationssnapshot"),
    JsonSubTypes.Type(value = SocketConnectionMessage.SnailTrailPointAdded::class, name = "snailtrailpointadded"),
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

    /**
     * INBOUND (client -> server): the sender's own current location. Only lat/long are mandatory;
     * the rest is optional driving telemetry. Routed in `SocketConnectionHandler.handleTextMessage`.
     */
    data class LocationUpdate(
        val lat: Double,
        val long: Double,
        val speed: Double? = null,
        val heading: Double? = null,
        val altitude: Double? = null,
        val batteryLevel: Int? = null,
    ) : SocketConnectionMessage

    /** OUTBOUND: a single friend's live position (~every 5s). Does NOT carry the snail trail. */
    data class FriendLocationChange(val friend: FriendLocationPayload) : SocketConnectionMessage

    /**
     * OUTBOUND: all friends' current positions + their full snail trails, pushed once when a client
     * connects (initial load).
     */
    data class FriendLocationsSnapshot(val friends: List<FriendLocationSnapshot>) : SocketConnectionMessage

    /**
     * OUTBOUND: one new snail-trail point for [userId], pushed at most once per minute when that
     * user's trail actually advances. The client appends it to that user's existing trail.
     */
    data class SnailTrailPointAdded(val userId: String, val point: SnailTrailPointPayload) : SocketConnectionMessage
}
