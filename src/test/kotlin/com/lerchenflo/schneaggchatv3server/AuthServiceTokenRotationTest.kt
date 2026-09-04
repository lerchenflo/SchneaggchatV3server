@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server

import com.lerchenflo.schneaggchatv3server.authentication.AuthController
import com.lerchenflo.schneaggchatv3server.authentication.AuthService
import com.lerchenflo.schneaggchatv3server.authentication.model.RefreshToken
import com.lerchenflo.schneaggchatv3server.core.security.HashEncoder
import com.lerchenflo.schneaggchatv3server.core.security.JwtService
import com.lerchenflo.schneaggchatv3server.repository.RefreshTokenRepository
import com.lerchenflo.schneaggchatv3server.user.UserLookupService
import com.lerchenflo.schneaggchatv3server.user.usermodel.User
import com.lerchenflo.schneaggchatv3server.util.ImageManager
import com.lerchenflo.schneaggchatv3server.util.LoggingService
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.bson.types.ObjectId
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Update
import org.springframework.web.server.ResponseStatusException
import java.security.MessageDigest
import java.util.*
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Unit tests for the in-place refresh token rotation in [AuthService]: winner rotates the device's
 * session row atomically, a replaying client (lost response / lost race) recovers the current
 * token via `previousHashedToken`, everything else gets a 401. Uses a real [JwtService] so tokens
 * are actually generated and validated; Mongo access is mocked.
 */
class AuthServiceTokenRotationTest {

    private val jwtService = JwtService(
        jwtSecret = "unit-test-secret-with-at-least-32-bytes-of-entropy!",
        apnsDebug = false,
    )

    private val userLookupService = mockk<UserLookupService>()
    private val hashEncoder = mockk<HashEncoder>()
    private val refreshTokenRepository = mockk<RefreshTokenRepository>(relaxed = true)
    private val loggingService = mockk<LoggingService>(relaxed = true)
    private val imageManager = mockk<ImageManager>(relaxed = true)
    private val mongoTemplate = mockk<MongoTemplate>()

    private val authService = AuthService(
        jwtService = jwtService,
        userLookupService = userLookupService,
        hashEncoder = hashEncoder,
        refreshTokenRepository = refreshTokenRepository,
        loggingService = loggingService,
        imageManager = imageManager,
        mongoTemplate = mongoTemplate,
    )

    private val userId = ObjectId.get()
    private val user = mockk<User> {
        every { id } returns userId
        every { username } returns "testuser"
        every { hashedPassword } returns "hashed-pw"
    }

    private fun hash(token: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return Base64.getEncoder().encodeToString(digest.digest(token.encodeToByteArray()))
    }

    private fun sessionRow(
        hashedToken: String,
        previousHashedToken: String? = null,
        rawToken: String? = null,
    ) = RefreshToken(
        userId = userId,
        hashedToken = hashedToken,
        previousHashedToken = previousHashedToken,
        rawToken = rawToken,
        expiresAt = Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds() + 1000L * 60 * 60),
        deviceName = "Test device",
        deviceType = AuthController.DEVICETYPE.ANDROID,
    )

    @BeforeEach
    fun setupUser() {
        every { userLookupService.findById(userId.toHexString()) } returns user
        every { userLookupService.findByUsername("testuser") } returns user
    }

    // ------------------------------------------------------------------ refresh

    @Test
    @DisplayName("Refresh with the current token rotates the row in place and returns a new pair")
    fun refreshRotatesInPlace() {
        val oldToken = jwtService.generateRefreshToken(userId.toHexString())
        val updateSlot = slot<Update>()

        // findAndModify claims the row -> this caller is the winner
        every {
            mongoTemplate.findAndModify(any(), capture(updateSlot), any(), RefreshToken::class.java)
        } returns sessionRow(hashedToken = hash(oldToken))

        val pair = authService.refresh(oldToken, "Test device", AuthController.DEVICETYPE.ANDROID)

        assertTrue(jwtService.validateAccessToken(pair.accessToken))
        assertTrue(jwtService.validateRefreshToken(pair.refreshToken))
        assertNotEquals(oldToken, pair.refreshToken)

        // The rotation must move the presented hash into previousHashedToken and store the new
        // raw token on the same row
        val updated = updateSlot.captured.updateObject.get("\$set", org.bson.Document::class.java)
        assertEquals(hash(oldToken), updated.getString("previousHashedToken"))
        assertEquals(hash(pair.refreshToken), updated.getString("hashedToken"))
        assertEquals(pair.refreshToken, updated.getString("rawToken"))
    }

    @Test
    @DisplayName("Replaying an already-rotated token returns the stored current token instead of 401")
    fun refreshReplayRecoversCurrentToken() {
        val oldToken = jwtService.generateRefreshToken(userId.toHexString())
        val currentToken = jwtService.generateRefreshToken(userId.toHexString())

        // Row already rotated away from oldToken -> findAndModify matches nothing
        every {
            mongoTemplate.findAndModify(any(), any(), any(), RefreshToken::class.java)
        } returns null

        every {
            refreshTokenRepository.findByUserIdAndPreviousHashedToken(userId, hash(oldToken))
        } returns listOf(
            sessionRow(
                hashedToken = hash(currentToken),
                previousHashedToken = hash(oldToken),
                rawToken = currentToken,
            )
        )

        val pair = authService.refresh(oldToken, "Test device", AuthController.DEVICETYPE.ANDROID)

        // The client stuck on the old token gets the SAME current token back - replay is
        // idempotent, the chain is not advanced
        assertEquals(currentToken, pair.refreshToken)
        assertTrue(jwtService.validateAccessToken(pair.accessToken))
    }

    @Test
    @DisplayName("Refresh with a token no row knows (current or previous) is a 401")
    fun refreshUnknownTokenRejected() {
        val oldToken = jwtService.generateRefreshToken(userId.toHexString())

        every {
            mongoTemplate.findAndModify(any(), any(), any(), RefreshToken::class.java)
        } returns null
        every {
            refreshTokenRepository.findByUserIdAndPreviousHashedToken(userId, hash(oldToken))
        } returns emptyList()

        val ex = assertThrows<ResponseStatusException> {
            authService.refresh(oldToken, "Test device", AuthController.DEVICETYPE.ANDROID)
        }
        assertEquals(401, ex.statusCode.value())
    }

    @Test
    @DisplayName("Refresh with a garbage token is a 401 before any database access")
    fun refreshGarbageTokenRejected() {
        val ex = assertThrows<ResponseStatusException> {
            authService.refresh("not-a-jwt", "Test device", AuthController.DEVICETYPE.ANDROID)
        }
        assertEquals(401, ex.statusCode.value())
        verify(exactly = 0) { mongoTemplate.findAndModify(any(), any(), any(), RefreshToken::class.java) }
    }

    @Test
    @DisplayName("An access token can not be used as a refresh token")
    fun refreshRejectsAccessToken() {
        val accessToken = jwtService.generateAccessToken(userId.toHexString())

        val ex = assertThrows<ResponseStatusException> {
            authService.refresh(accessToken, "Test device", AuthController.DEVICETYPE.ANDROID)
        }
        assertEquals(401, ex.statusCode.value())
    }

    // ------------------------------------------------------------------ login

    @Test
    @DisplayName("Login with a known device rotates the existing session row instead of inserting")
    fun loginDedupsPerDevice() {
        every { hashEncoder.matches("pw", "hashed-pw") } returns true

        val existing = sessionRow(hashedToken = hash("some-old-token"))
        every {
            refreshTokenRepository.findFirstByUserIdAndDeviceNameAndDeviceTypeOrderByCreatedAtDesc(
                userId, "Test device", AuthController.DEVICETYPE.ANDROID
            )
        } returns existing
        every {
            mongoTemplate.findAndModify(any(), any(), any(), RefreshToken::class.java)
        } returns existing

        val pair = authService.login("testuser", "pw", "Test device", AuthController.DEVICETYPE.ANDROID)

        assertTrue(jwtService.validateRefreshToken(pair.refreshToken))
        // In-place rotation -> no second row for the same device
        verify(exactly = 0) { refreshTokenRepository.save(any()) }
    }

    @Test
    @DisplayName("Login with an unknown device inserts a new session row")
    fun loginNewDeviceInsertsRow() {
        every { hashEncoder.matches("pw", "hashed-pw") } returns true
        every {
            refreshTokenRepository.findFirstByUserIdAndDeviceNameAndDeviceTypeOrderByCreatedAtDesc(
                userId, "New device", AuthController.DEVICETYPE.IOS
            )
        } returns null

        val savedSlot = slot<RefreshToken>()
        every { refreshTokenRepository.save(capture(savedSlot)) } answers { savedSlot.captured }

        val pair = authService.login("testuser", "pw", "New device", AuthController.DEVICETYPE.IOS)

        assertEquals(hash(pair.refreshToken), savedSlot.captured.hashedToken)
        assertEquals(pair.refreshToken, savedSlot.captured.rawToken)
        assertEquals("New device", savedSlot.captured.deviceName)
        assertEquals(AuthController.DEVICETYPE.IOS, savedSlot.captured.deviceType)
        assertNull(savedSlot.captured.previousHashedToken)
    }

    @Test
    @DisplayName("Login with a blank device name never dedups")
    fun loginBlankDeviceNameAlwaysInserts() {
        every { hashEncoder.matches("pw", "hashed-pw") } returns true

        val savedSlot = slot<RefreshToken>()
        every { refreshTokenRepository.save(capture(savedSlot)) } answers { savedSlot.captured }

        authService.login("testuser", "pw", "", AuthController.DEVICETYPE.DESKTOP)

        verify(exactly = 0) {
            refreshTokenRepository.findFirstByUserIdAndDeviceNameAndDeviceTypeOrderByCreatedAtDesc(any(), any(), any())
        }
        verify(exactly = 1) { refreshTokenRepository.save(any()) }
    }

    @Test
    @DisplayName("Login falls back to inserting when the in-place rotation loses a race")
    fun loginRaceFallsBackToInsert() {
        every { hashEncoder.matches("pw", "hashed-pw") } returns true

        val existing = sessionRow(hashedToken = hash("some-old-token"))
        every {
            refreshTokenRepository.findFirstByUserIdAndDeviceNameAndDeviceTypeOrderByCreatedAtDesc(
                userId, "Test device", AuthController.DEVICETYPE.ANDROID
            )
        } returns existing
        // Concurrent refresh rotated the row between lookup and claim
        every {
            mongoTemplate.findAndModify(any(), any(), any(), RefreshToken::class.java)
        } returns null

        val savedSlot = slot<RefreshToken>()
        every { refreshTokenRepository.save(capture(savedSlot)) } answers { savedSlot.captured }

        val pair = authService.login("testuser", "pw", "Test device", AuthController.DEVICETYPE.ANDROID)

        assertEquals(hash(pair.refreshToken), savedSlot.captured.hashedToken)
    }
}
