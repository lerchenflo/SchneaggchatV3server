package com.lerchenflo.schneaggchatv3server.notifications.firebase.model

import com.lerchenflo.schneaggchatv3server.util.Json

/** The flat string map both push services carry: every field of this notification plus `type`. */
@Suppress("UNCHECKED_CAST")
fun NotificationResponse.toPushData(): Map<String, String> {
    val raw = Json.mapper.convertValue(this, Map::class.java) as Map<String, Any?>? ?: emptyMap()

    val data = raw.mapValues { (_, value) ->
        when (value) {
            null -> ""
            is String -> value
            is Number, is Boolean -> value.toString()
            else -> Json.mapper.writeValueAsString(value) // nested objects travel as a JSON string
        }
    }

    return data + ("type" to pushTypeName())
}

private fun NotificationResponse.pushTypeName(): String = when (this) {
    is NotificationResponse.MessageNotificationResponse -> "message"
    is NotificationResponse.FriendRequestNotificationResponse -> "friend_request"
    is NotificationResponse.SystemNotificationResponse -> "system"
    is NotificationResponse.BirthdayNotificationResponse -> "birthday"
    is NotificationResponse.WakeNotificationResponse -> "wake"
    is NotificationResponse.EventNotificationResponse -> "event"
    is NotificationResponse.MapSightingNotificationResponse -> "map_sighting"
}
