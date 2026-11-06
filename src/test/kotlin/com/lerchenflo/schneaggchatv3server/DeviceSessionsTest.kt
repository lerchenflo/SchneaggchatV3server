package com.lerchenflo.schneaggchatv3server

import com.lerchenflo.schneaggchatv3server.authentication.DeviceSessions
import com.lerchenflo.schneaggchatv3server.authentication.model.RefreshToken
import com.lerchenflo.schneaggchatv3server.notifications.push.DeviceDirectory
import com.lerchenflo.schneaggchatv3server.notifications.websocket.SocketConnectionHandler
import com.lerchenflo.schneaggchatv3server.repository.RefreshTokenRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bson.types.ObjectId
import org.junit.jupiter.api.Test
import org.springframework.data.mongodb.core.MongoTemplate
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock

class DeviceSessionsTest {

    private val refreshTokens = mockk<RefreshTokenRepository>(relaxed = true)
    private val mongoTemplate = mockk<MongoTemplate>(relaxed = true)
    private val deviceDirectory = mockk<DeviceDirectory>(relaxed = true)
    private val sockets = mockk<SocketConnectionHandler>(relaxed = true)

    private val sessions = DeviceSessions(refreshTokens, mongoTemplate, deviceDirectory, sockets)

    private val user = ObjectId()

    private fun row(owner: ObjectId = user) = RefreshToken(userId = owner, hashedToken = "hash", expiresAt = Clock.System.now())

    @Test
    fun `ending a device removes it and closes its socket`() {
        val device = row()
        every { refreshTokens.findById(device.id) } returns Optional.of(device)

        assertTrue(sessions.endDevice(user, device.id))

        verify(exactly = 1) { refreshTokens.delete(device) }
        verify(exactly = 1) { deviceDirectory.forgetEndedDevices(listOf(device)) }
        verify(exactly = 1) { sockets.disconnectDevice(device.id) }
    }

    @Test
    fun `a device of another user stays`() {
        val foreign = row(owner = ObjectId())
        every { refreshTokens.findById(foreign.id) } returns Optional.of(foreign)

        assertFalse(sessions.endDevice(user, foreign.id))

        verify(exactly = 0) { refreshTokens.delete(any()) }
        verify(exactly = 0) { sockets.disconnectDevice(any()) }
    }

    @Test
    fun `ending by refresh token closes the socket of the row it removed`() {
        val device = row()
        every { mongoTemplate.findAllAndRemove(any(), RefreshToken::class.java) } returns listOf(device)

        assertEquals(1L, sessions.endByRefreshToken(user, "hash"))

        verify(exactly = 1) { sockets.disconnectDevice(device.id) }
    }

    @Test
    fun `ending all sessions leaves no token and no socket`() {
        every { refreshTokens.deleteByUserId(user) } returns 3L

        assertEquals(3L, sessions.endAll(user))

        verify(exactly = 1) { deviceDirectory.forgetLegacyTokensOf(user) }
        verify(exactly = 1) { sockets.disconnectUser(user) }
    }

    @Test
    fun `an expired session ends like any other`() {
        val device = row()
        every { refreshTokens.findByExpiresAtBefore(any()) } returns listOf(device)

        sessions.endExpired()

        verify(exactly = 1) { refreshTokens.deleteByExpiresAtBefore(any()) }
        verify(exactly = 1) { sockets.disconnectDevice(device.id) }
    }

    @Test
    fun `nothing expired means no delete`() {
        every { refreshTokens.findByExpiresAtBefore(any()) } returns emptyList()

        sessions.endExpired()

        verify(exactly = 0) { refreshTokens.deleteByExpiresAtBefore(any()) }
    }
}
