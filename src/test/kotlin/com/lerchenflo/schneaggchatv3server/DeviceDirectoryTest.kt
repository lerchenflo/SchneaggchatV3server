package com.lerchenflo.schneaggchatv3server

import com.lerchenflo.schneaggchatv3server.authentication.AuthController
import com.lerchenflo.schneaggchatv3server.authentication.model.RefreshToken
import com.lerchenflo.schneaggchatv3server.notifications.apns.model.ApnsToken
import com.lerchenflo.schneaggchatv3server.notifications.firebase.model.FirebaseToken
import com.lerchenflo.schneaggchatv3server.notifications.push.DeviceDirectory
import com.lerchenflo.schneaggchatv3server.notifications.push.PushProvider
import com.lerchenflo.schneaggchatv3server.repository.ApnsTokenRepository
import com.lerchenflo.schneaggchatv3server.repository.FirebaseTokenRepository
import com.lerchenflo.schneaggchatv3server.repository.RefreshTokenRepository
import com.lerchenflo.schneaggchatv3server.util.LoggingService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bson.types.ObjectId
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.mongodb.core.MongoTemplate
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

class DeviceDirectoryTest {

    private val refreshTokens = mockk<RefreshTokenRepository>(relaxed = true)
    private val firebaseTokens = mockk<FirebaseTokenRepository>(relaxed = true)
    private val apnsTokens = mockk<ApnsTokenRepository>(relaxed = true)
    private val mongoTemplate = mockk<MongoTemplate>(relaxed = true)

    private val directory = DeviceDirectory(refreshTokens, firebaseTokens, apnsTokens, mongoTemplate, mockk<LoggingService>(relaxed = true))

    private val user = ObjectId()

    private fun row(pushToken: String? = null, owner: ObjectId = user) = RefreshToken(
        userId = owner,
        hashedToken = "hash",
        expiresAt = Clock.System.now(),
        deviceType = AuthController.DEVICETYPE.ANDROID,
        pushProvider = pushToken?.let { PushProvider.FCM },
        pushToken = pushToken,
    )

    @BeforeEach
    fun noLegacyTokens() {
        every { firebaseTokens.findAllByUserIdIn(any()) } returns emptyList()
        every { apnsTokens.findAllByUserIdIn(any()) } returns emptyList()
        every { refreshTokens.findByUserIdInAndPushTokenIsNotNull(any()) } returns emptyList()
        every { firebaseTokens.save(any<FirebaseToken>()) } answers { firstArg() }
    }

    @Test
    fun `a device token is a target with its device`() {
        val device = row(pushToken = "token")
        every { refreshTokens.findByUserIdInAndPushTokenIsNotNull(any()) } returns listOf(device)

        val target = directory.targetsOf(user).single()

        assertEquals(device.id, target.deviceId)
        assertEquals(PushProvider.FCM, target.provider)
    }

    @Test
    fun `a legacy token counts until a device holds the same token`() {
        every { firebaseTokens.findAllByUserIdIn(any()) } returns listOf(
            FirebaseToken(userId = user, token = "linked"),
            FirebaseToken(userId = user, token = "unlinked"),
        )
        every { apnsTokens.findAllByUserIdIn(any()) } returns listOf(ApnsToken(userId = user, token = "apple"))
        every { refreshTokens.findByUserIdInAndPushTokenIsNotNull(any()) } returns listOf(row(pushToken = "linked"))

        val targets = directory.targetsOf(user).associateBy { it.token }

        assertEquals(setOf("linked", "unlinked", "apple"), targets.keys)
        assertNull(targets.getValue("unlinked").deviceId)
        assertNull(targets.getValue("apple").deviceId)
        assertEquals(PushProvider.APNS, targets.getValue("apple").provider)
        assertTrue(targets.getValue("linked").deviceId != null)
    }

    @Test
    fun `an ended session can not register a token`() {
        val deviceId = ObjectId()
        every { refreshTokens.findById(deviceId) } returns Optional.empty()

        assertFalse(directory.registerPushToken(user, deviceId, PushProvider.FCM, "token"))

        verify(exactly = 0) { mongoTemplate.updateFirst(any(), any(), RefreshToken::class.java) }
        verify(exactly = 0) { firebaseTokens.save(any()) }
    }

    @Test
    fun `a session of another user can not register a token`() {
        val foreign = row(owner = ObjectId())
        every { refreshTokens.findById(foreign.id) } returns Optional.of(foreign)

        assertFalse(directory.registerPushToken(user, foreign.id, PushProvider.FCM, "token"))
    }

    @Test
    fun `registering moves the token away from other rows and users`() {
        val device = row()
        every { refreshTokens.findById(device.id) } returns Optional.of(device)
        every { firebaseTokens.findByUserIdAndToken(user, "token") } returns null

        assertTrue(directory.registerPushToken(user, device.id, PushProvider.FCM, "token"))

        verify(exactly = 1) { mongoTemplate.updateMulti(any(), any(), RefreshToken::class.java) }
        verify(exactly = 1) { mongoTemplate.updateFirst(any(), any(), RefreshToken::class.java) }
        verify(exactly = 1) { firebaseTokens.deleteByTokenAndUserIdNot("token", user) }
    }

    @Test
    fun `an unchanged token writes nothing on the row`() {
        val device = row(pushToken = "token")
        every { refreshTokens.findById(device.id) } returns Optional.of(device)
        every { firebaseTokens.findByUserIdAndToken(user, "token") } returns FirebaseToken(userId = user, token = "token")

        assertTrue(directory.registerPushToken(user, device.id, PushProvider.FCM, "token"))

        verify(exactly = 0) { mongoTemplate.updateFirst(any(), any(), RefreshToken::class.java) }
    }

    @Test
    fun `an access token without a device still registers the legacy token`() {
        every { firebaseTokens.findByUserIdAndToken(user, "token") } returns null

        assertTrue(directory.registerPushToken(user, deviceId = null, PushProvider.FCM, "token"))

        verify(exactly = 1) { firebaseTokens.save(any()) }
    }

    @Test
    fun `an ended device takes the legacy copy of its token with it`() {
        directory.forgetEndedDevices(listOf(row(pushToken = "token"), row()))

        verify(exactly = 1) { firebaseTokens.deleteByTokenIn(listOf("token")) }
        verify(exactly = 1) { apnsTokens.deleteByTokenIn(listOf("token")) }
    }
}
