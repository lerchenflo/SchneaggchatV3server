package com.lerchenflo.schneaggchatv3server

import com.lerchenflo.schneaggchatv3server.message.messagemodel.MessageType
import com.lerchenflo.schneaggchatv3server.notifications.firebase.model.NotificationResponse
import com.lerchenflo.schneaggchatv3server.notifications.firebase.model.toPushData
import com.lerchenflo.schneaggchatv3server.schneaggmap.MapSightingType
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class NotificationResponseMapperTest {

    @Test
    fun `every subtype gets its type name`() {
        val expected = mapOf(
            "map_sighting" to NotificationResponse.MapSightingNotificationResponse("e", "Bödele", MapSightingType.RADAR),
            "message" to NotificationResponse.MessageNotificationResponse(
                msgId = "m", senderName = "s", groupMessage = false, messageType = MessageType.TEXT,
                groupName = "", content = "hi", senderId = "a", receiverId = "b",
            ),
            "friend_request" to NotificationResponse.FriendRequestNotificationResponse("a", "name", accepted = true),
            "system" to NotificationResponse.SystemNotificationResponse("title", "text"),
            "birthday" to NotificationResponse.BirthdayNotificationResponse("a", "name", ownBirthday = false),
            "wake" to NotificationResponse.WakeNotificationResponse("a", "name", "reason"),
            "event" to NotificationResponse.EventNotificationResponse("e", "title", "a", "name"),
        )

        expected.forEach { (type, notification) ->
            assertEquals(type, notification.toPushData()["type"])
        }
    }

    @Test
    fun `numbers, booleans and enums travel as strings`() {
        val data = NotificationResponse.MessageNotificationResponse(
            msgId = "m", senderName = "s", groupMessage = true, messageType = MessageType.IMAGE,
            groupName = "g", content = "", senderId = "a", receiverId = "b", sendDate = 42L,
        ).toPushData()

        assertEquals("true", data["groupMessage"])
        assertEquals("42", data["sendDate"])
        assertEquals("IMAGE", data["messageType"])
        assertEquals("false", data["reaction"])
    }
}
