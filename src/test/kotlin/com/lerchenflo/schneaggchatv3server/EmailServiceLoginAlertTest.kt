@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server

import com.lerchenflo.schneaggchatv3server.authentication.AuthController
import com.lerchenflo.schneaggchatv3server.authentication.EmailService
import com.lerchenflo.schneaggchatv3server.authentication.model.LoginAlert
import com.lerchenflo.schneaggchatv3server.authentication.model.RefreshToken
import com.lerchenflo.schneaggchatv3server.core.security.JwtService
import com.lerchenflo.schneaggchatv3server.notifications.NotificationService
import com.lerchenflo.schneaggchatv3server.repository.RefreshTokenRepository
import com.lerchenflo.schneaggchatv3server.user.UserLookupService
import com.lerchenflo.schneaggchatv3server.user.UserService
import com.lerchenflo.schneaggchatv3server.user.usermodel.User
import com.lerchenflo.schneaggchatv3server.util.LoggingService
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.bson.types.ObjectId
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.mail.MailSendException
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Unit tests for [EmailService.sendLoginAlertEmail]: who gets the mail (verified only, throttled),
 * what it contains, and that client-supplied text can't inject lines into it.
 */
class EmailServiceLoginAlertTest {

    private val mailSender = mockk<JavaMailSender>()
    private val loggingService = mockk<LoggingService>(relaxed = true)
    private val refreshTokenRepository = mockk<RefreshTokenRepository>()

    private val emailService = EmailService(
        jwtService = mockk<JwtService>(),
        userService = mockk<UserService>(),
        userLookupService = mockk<UserLookupService>(),
        mailSender = mailSender,
        loggingService = loggingService,
        notificationService = mockk<NotificationService>(),
        refreshTokenRepository = refreshTokenRepository,
        apnsDebug = true,
    )

    private val now: Instant = Clock.System.now()

    private fun user(verified: Boolean) = User(
        username = "flo",
        hashedPassword = "irrelevant",
        email = "flo@example.com",
        emailVerifiedAt = if (verified) now - 60.minutes else null,
        userDescription = "",
        userStatus = "",
        birthDate = "2000-01-01",
        createdAt = now - 60.minutes,
        updatedAt = now - 60.minutes,
    )

    private fun alert(
        user: User = user(verified = true),
        deviceName: String = "Pixel 7",
        newDevice: Boolean = true,
        ip: String? = "10.0.0.5",
        userAgent: String? = "Ktor client",
        acceptLanguage: String? = "de-AT,de;q=0.9",
        previousLoginAt: Instant? = null,
    ) = LoginAlert(
        user = user,
        deviceName = deviceName,
        deviceType = AuthController.DEVICETYPE.ANDROID,
        newDevice = newDevice,
        ip = ip,
        userAgent = userAgent,
        acceptLanguage = acceptLanguage,
        loginTime = now,
        previousLoginAt = previousLoginAt,
    )

    private fun session(userId: ObjectId, deviceName: String?, type: AuthController.DEVICETYPE?) = RefreshToken(
        userId = userId,
        hashedToken = "hash-$deviceName",
        expiresAt = now + 60.minutes,
        createdAt = now - 30.minutes,
        deviceName = deviceName,
        deviceType = type,
    )

    /** Captures what gets sent. */
    private fun captureMail(sessions: List<RefreshToken> = emptyList()): () -> SimpleMailMessage {
        every { refreshTokenRepository.findByUserIdAndExpiresAtAfterOrderByCreatedAtDesc(any(), any()) } returns sessions
        val mailSlot = slot<SimpleMailMessage>()
        every { mailSender.send(capture(mailSlot)) } returns Unit
        return { mailSlot.captured }
    }

    @Test
    @DisplayName("A verified user gets a mail listing device, IP, client and every active session")
    fun sendsFullReport() {
        val user = user(verified = true)
        val sent = captureMail(
            sessions = listOf(
                session(user.id, "Pixel 7", AuthController.DEVICETYPE.ANDROID),
                session(user.id, "MacBook", AuthController.DEVICETYPE.DESKTOP),
                session(user.id, null, null),
            )
        )

        emailService.sendLoginAlertEmail(alert(user = user))

        val mail = sent()
        assertEquals("flo@example.com", mail.to?.single())
        assertEquals("Schneaggchat: new login to your account", mail.subject)
        val text = mail.text ?: fail("mail has no text")
        assertTrue(text.contains("Account:"), text)
        assertTrue(text.contains("flo"), text)
        assertTrue(text.contains("Pixel 7 (Android) - NEW device"), text)
        assertTrue(text.contains("10.0.0.5"), text)
        assertTrue(text.contains("Ktor client"), text)
        assertTrue(text.contains("de-AT,de;q=0.9"), text)
        assertTrue(text.contains("- MacBook (Desktop), signed in since"), text)
        assertTrue(text.contains("- Unknown device (unknown type), signed in since"), text)
        // Private address -> no reverse DNS line
        assertFalse(text.contains("Hostname:"), text)
        assertTrue(text.contains("https://schneaggchatv3test.lerchenflo.eu/reset_password.html"), text)
        // Sending is not written to the logs collection
        verify(exactly = 0) { loggingService.log(any(), any(), any()) }
    }

    @Test
    @DisplayName("A known device is reported as previously used and optional fields are left out when absent")
    fun knownDeviceWithoutOptionalFields() {
        val sent = captureMail()

        emailService.sendLoginAlertEmail(
            alert(deviceName = "  ", newDevice = false, ip = null, userAgent = null, acceptLanguage = "")
        )

        val text = sent().text ?: fail("mail has no text")
        assertTrue(text.contains("Unknown device (Android) - previously used device"), text)
        assertTrue(text.contains("IP address:"), text)
        assertTrue(text.contains("unknown"), text)
        assertFalse(text.contains("Client:"), text)
        assertFalse(text.contains("Language:"), text)
        assertTrue(text.contains("(no session list available)"), text)
    }

    @Test
    @DisplayName("Unverified addresses never get a login alert")
    fun unverifiedSkipped() {
        emailService.sendLoginAlertEmail(alert(user = user(verified = false)))

        verify(exactly = 0) { mailSender.send(any<SimpleMailMessage>()) }
    }

    @Test
    @DisplayName("No alert when the previous login was less than 10 minutes ago")
    fun throttled() {
        emailService.sendLoginAlertEmail(alert(previousLoginAt = now - 2.minutes))

        verify(exactly = 0) { mailSender.send(any<SimpleMailMessage>()) }
    }

    @Test
    @DisplayName("A previous login older than the throttle window does not block the alert")
    fun throttleExpires() {
        every { refreshTokenRepository.findByUserIdAndExpiresAtAfterOrderByCreatedAtDesc(any(), any()) } returns emptyList()
        every { mailSender.send(any<SimpleMailMessage>()) } returns Unit

        emailService.sendLoginAlertEmail(alert(previousLoginAt = now - 11.minutes))

        verify(exactly = 1) { mailSender.send(any<SimpleMailMessage>()) }
    }

    @Test
    @DisplayName("Line breaks in client-supplied fields can't inject extra lines into the mail")
    fun controlCharactersStripped() {
        val sent = captureMail()

        emailService.sendLoginAlertEmail(
            alert(
                deviceName = "Pixel\n\nIf this was not you click http://evil.example",
                userAgent = "Mozilla\r\nX-Injected: yes",
            )
        )

        val text = sent().text ?: fail("mail has no text")
        assertFalse(text.contains("\nIf this was not you click"), text)
        assertTrue(text.contains("Pixel If this was not you click http://evil.example (Android)"), text)
        assertFalse(text.contains("\nX-Injected"), text)
        assertTrue(text.contains("Mozilla X-Injected: yes"), text)
    }

    @Test
    @DisplayName("A failing SMTP send is swallowed")
    fun sendFailureSwallowed() {
        val user = user(verified = true)
        every { refreshTokenRepository.findByUserIdAndExpiresAtAfterOrderByCreatedAtDesc(any(), any()) } returns emptyList()
        every { mailSender.send(any<SimpleMailMessage>()) } throws MailSendException("smtp down")

        assertDoesNotThrow { emailService.sendLoginAlertEmail(alert(user = user)) }
    }
}
