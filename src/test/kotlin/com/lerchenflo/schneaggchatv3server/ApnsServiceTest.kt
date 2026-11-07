@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server

import com.lerchenflo.schneaggchatv3server.notifications.apns.ApnsService
import com.lerchenflo.schneaggchatv3server.notifications.apns.apnsExpiration
import com.lerchenflo.schneaggchatv3server.notifications.firebase.model.NotificationResponse
import com.lerchenflo.schneaggchatv3server.notifications.push.DeviceDirectory
import com.lerchenflo.schneaggchatv3server.notifications.push.PushProvider
import com.lerchenflo.schneaggchatv3server.notifications.push.PushTarget
import com.lerchenflo.schneaggchatv3server.util.Json
import io.mockk.mockk
import io.mockk.verify
import org.bson.types.ObjectId
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.time.toJavaInstant

class ApnsServiceTest {

    private val now = Instant.fromEpochSeconds(1_800_000_000)
    private val deviceDirectory = mockk<DeviceDirectory>(relaxed = true)
    private val service = ApnsService(deviceDirectory, mockk(relaxed = true), "", "", "", false)
    private val target = PushTarget(ObjectId(), PushProvider.APNS, "token")

    @Test
    fun `default keeps a push stored for 4 weeks`() {
        assertEquals((now + 28.days).toJavaInstant(), apnsExpiration(null, now))
    }

    @Test
    fun `ttl sets the expiration`() {
        assertEquals((now + 1.hours).toJavaInstant(), apnsExpiration(3600, now))
    }

    @Test
    fun `ttl 0 stays now or never`() {
        assertNull(apnsExpiration(0, now))
    }

    @Test
    fun `payload has no interruption level and still reaches the extension`() {
        val payload = service.buildPayload(NotificationResponse.SystemNotificationResponse("title", "text"))
        val aps = Json.mapper.readTree(payload).get("aps")

        assertFalse(aps.has("interruption-level"))
        assertEquals(1, aps.get("mutable-content").asInt())
    }

    @Test
    fun `token goes when APNs says it is dead`() {
        listOf("Unregistered", "ExpiredToken", "BadDeviceToken", "DeviceTokenNotForTopic").forEach {
            service.onRejected(target, it)
        }

        verify(exactly = 4) { deviceDirectory.clearPushToken("token") }
    }

    @Test
    fun `token stays on a rejection that says nothing about it`() {
        listOf("TopicDisallowed", "TooManyRequests", "InternalServerError").forEach {
            service.onRejected(target, it)
        }

        verify(exactly = 0) { deviceDirectory.clearPushToken(any()) }
    }
}
