package com.lerchenflo.schneaggchatv3server.authentication

import com.lerchenflo.schneaggchatv3server.authentication.model.LoginAlert
import com.lerchenflo.schneaggchatv3server.core.security.JwtService
import com.lerchenflo.schneaggchatv3server.notifications.NotificationService
import com.lerchenflo.schneaggchatv3server.repository.RefreshTokenRepository
import com.lerchenflo.schneaggchatv3server.user.UserLookupService
import com.lerchenflo.schneaggchatv3server.user.UserService
import com.lerchenflo.schneaggchatv3server.util.AppLogger
import com.lerchenflo.schneaggchatv3server.util.LogType
import com.lerchenflo.schneaggchatv3server.util.LoggingService
import org.bson.types.ObjectId
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.net.InetAddress
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
@Service
class EmailService(
    private val jwtService: JwtService,
    private val userService: UserService,
    private val userLookupService: UserLookupService,
    private val mailSender: JavaMailSender,
    private val loggingService: LoggingService,
    private val notificationService: NotificationService,
    private val refreshTokenRepository: RefreshTokenRepository,

    @Value("\${apns.debug}") private val apnsDebug: Boolean
    ) {

    private val baseUrl get() = if (apnsDebug)
        "https://schneaggchatv3test.lerchenflo.eu"
    else
        "https://schneaggchatv3.lerchenflo.eu"


    /**
     * Send a verification email to a client
     */
    fun sendVerificationEmail(userId: ObjectId) {

        val user = userLookupService.findById(userId) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "User $userId not found")

        if (user.emailVerifiedAt != null) {
            return //Email already verified
        }

        val lastLog = loggingService.getLastLogByLogtype(logType = LogType.EMAIL_VERIFICATION_EMAIL_SENT, userId = userId)
        val emailChanged = lastLog?.message != user.email
        if (!emailChanged && (lastLog.timestamp.plus(Duration.parse("5m")) > Clock.System.now())) {
            throw ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "You need to wait 5 minutes before sending the next mail")
        }

        val token = jwtService.generateEmailToken(userId.toHexString(), user.email)
        val verificationUrl = "$baseUrl/auth/verify_email?token=$token"

        val mail = SimpleMailMessage()
        mail.setTo(user.email)
        mail.subject = "Schneaggchat email verification"
        mail.text = "Click here to validate your email:\n$verificationUrl"
        try {
            mailSender.send(mail)
            loggingService.log(userId, LogType.EMAIL_VERIFICATION_EMAIL_SENT, user.email)
        } catch (e: Exception) {
            println("Mail not sent, error")
        }
    }

    /**
     * Client pressed on the link, verify
     */
    fun verifyEmailRequest(token: String) : Boolean {
        val tokenData = jwtService.validateEmailToken(token)
        if (tokenData == null) {
            AppLogger.warn("Email verification failed: invalid or expired token")
            return false
        }
        val (email, userId) = tokenData

        val user = userLookupService.findByEmail(email)
        if (user == null) {
            AppLogger.warn("Email verification failed: no user found for email $email (userId=$userId)")
            return false
        }

        if (user.id != userId) {
            AppLogger.warn("Email verification failed: token userId $userId does not match user ${user.id} for email $email")
            return false
        }

        val now = Clock.System.now()

        val updatedUser = user.copy(
            emailVerifiedAt = now,
            updatedAt = now,
        )
        userLookupService.save(updatedUser)
        notificationService.notifyUserUpdate(updatedUser, deleted = false)
        AppLogger.info("Email verified successfully for user ${user.username}: (${user.email})")
        return true
    }



    /**
     * Send a delete account email to a client
     */
    fun sendDelAccEmail(userId: ObjectId, email: String) {

        val lastemailsenttimestamp = getLastEmailTimestamp(userId, LogType.ACCOUNT_DELETION_EMAIL_SENT)
        if (lastemailsenttimestamp != null &&
            (lastemailsenttimestamp.plus(Duration.parse("15m")) > Clock.System.now())) {
            throw ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "You need to wait 15 minutes before sending the next mail")
        }

        val token = jwtService.generateDelAccEmailToken(userId.toHexString(), email)
        val verificationUrl = "$baseUrl/auth/delete_account?token=$token"

        val mail = SimpleMailMessage()
        mail.setTo(email)
        mail.subject = "Schneaggchat account deletion"
        mail.text = "Someone requested to delete your account. If this was not you, please ignore this email.\nIf you really want to delete your account, click the link below to confirm:\n$verificationUrl\n\nNote: You will need to confirm the deletion on the website before your account is permanently deleted."
        try {
            mailSender.send(mail)
            loggingService.log(userId, LogType.ACCOUNT_DELETION_EMAIL_SENT)
        } catch (e: Exception) {
            println("Mail not sent, error")
        }
    }

    /**
     * Generate confirmation token for account deletion (called from confirmation page)
     */
    fun generateDelAccConfirmToken(token: String): String? {
        val (email, userId) = jwtService.validateDelAccEmailToken(token) ?: return null
        
        val user = userLookupService.findByEmail(email) ?: return null
        if (user.id != userId) return null
        
        return jwtService.generateDelAccConfirmToken(userId.toHexString(), email)
    }

    /**
     * Actually delete the account after confirmation
     */
    fun confirmDeleteAccount(confirmToken: String): Boolean {
        val (email, userId) = jwtService.validateDelAccConfirmToken(confirmToken) ?: return false

        val user = userLookupService.findByEmail(email) ?: return false
        if (user.id != userId) return false
        
        userService.deleteAccount(user.id)
        println("Account with name ${user.username} has been deleted")

        return true
    }


    /**
     * Send a password reset email to a client (only if email is verified)
     */
    fun sendPasswordResetEmail(userId: ObjectId, email: String) {
        val user = userLookupService.findById(userId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")

        // Only send if email is verified
        if (user.emailVerifiedAt == null) {
            println("Password reset requested for unverified email $email")
            //return (Allow unverified email passwort resets)
        }

        val lastemailsenttimestamp = getLastEmailTimestamp(userId, LogType.PASSWORD_RESET_EMAIL_SENT)
        if (lastemailsenttimestamp != null &&
            (lastemailsenttimestamp.plus(Duration.parse("15m")) > Clock.System.now())) {
            throw ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "You need to wait 15 minutes before sending the next mail")
        }

        val token = jwtService.generatePasswordResetToken(userId.toHexString(), email)
        val resetUrl = "$baseUrl/auth/reset_password?token=$token"

        val mail = SimpleMailMessage()
        mail.setTo(email)
        mail.subject = "Schneaggchat password reset"
        mail.text = "Someone requested to reset your password. If this was not you, please ignore this email.\n\nYour Username: ${userLookupService.getUsername(userId)}\n\nTo reset your password, click the link below:\n$resetUrl\n\nThis link is valid for 1 hour."
        try {
            mailSender.send(mail)
            loggingService.log(userId, LogType.PASSWORD_RESET_EMAIL_SENT)
        } catch (e: Exception) {
            println("Mail not sent, error")
        }
    }

    /**
     * Reset the password using a valid token
     */
    fun resetPassword(token: String, newPassword: String): Boolean {
        val (email, userId) = jwtService.validatePasswordResetToken(token) ?: return false

        val user = userLookupService.findByEmail(email) ?: return false
        if (user.id != userId) return false

        userService.resetPassword(userId, newPassword)
        return true
    }


    /**
     * Security alert after every successful `/auth/login`: tells the account owner which device,
     * IP and client just signed in, plus every device currently holding a session, so
     * a stolen password is noticed early. Runs on the async executor so SMTP latency and the
     * reverse-DNS lookup never delay the login response; failures are logged and never surface
     * to the caller.
     *
     * Only verified addresses get it. Until a user proves the address is theirs, the mail would
     * hand their username, device name and IP to whoever actually owns that inbox.
     *
     * Throttled to one mail per [LOGIN_ALERT_MIN_INTERVAL] per account so someone holding the
     * password can't flood the owner's inbox by logging in repeatedly - the first alert is what
     * matters, and it always goes out. The throttle reads the gap to the previous `USER_LOGIN`
     * log ([LoginAlert.previousLoginAt]); sending is deliberately not logged itself.
     */
    @Async
    fun sendLoginAlertEmail(alert: LoginAlert) {
        val user = alert.user
        if (user.emailVerifiedAt == null) return

        val previousLogin = alert.previousLoginAt
        if (previousLogin != null && previousLogin.plus(LOGIN_ALERT_MIN_INTERVAL) > alert.loginTime) {
            return
        }

        val now = Clock.System.now()

        val device = describeDevice(alert.deviceName, alert.deviceType)
        val ip = alert.ip?.let { sanitize(it, 64) }?.ifBlank { null }
        val sessions = try {
            refreshTokenRepository.findByUserIdAndExpiresAtAfterOrderByCreatedAtDesc(user.id, now)
        } catch (e: Exception) {
            AppLogger.warn("Login alert: could not list sessions of ${user.username}: ${e.message}")
            emptyList()
        }

        val details = buildList {
            add("Account" to user.username)
            add("Time" to formatTime(alert.loginTime))
            add("Device" to "$device - ${if (alert.newDevice) "NEW device" else "previously used device"}")
            add("IP address" to (ip ?: "unknown"))
            ip?.let(::reverseDns)?.let { add("Hostname" to it) }
            alert.userAgent?.let { sanitize(it, 200) }?.ifBlank { null }?.let { add("Client" to it) }
            alert.acceptLanguage?.let { sanitize(it, 40) }?.ifBlank { null }?.let { add("Language" to it) }
        }
        val labelWidth = details.maxOf { it.first.length } + 1
        val detailBlock = details.joinToString("\n") { (label, value) -> "$label:".padEnd(labelWidth + 1) + value }

        val sessionBlock = if (sessions.isEmpty()) {
            "(no session list available)"
        } else {
            sessions.joinToString("\n") { row ->
                val rowDevice = describeDevice(row.deviceName ?: "", row.deviceType)
                "- $rowDevice, signed in since ${formatTime(row.createdAt)}"
            }
        }

        val mail = SimpleMailMessage()
        mail.setTo(user.email)
        mail.subject = "Schneaggchat: new login to your account"
        mail.text = """
            |Someone just logged in to your Schneaggchat account.
            |
            |$detailBlock
            |
            |Devices currently signed in to your account (including this one):
            |$sessionBlock
            |
            |If this was you, you can ignore this email.
            |
            |If this was NOT you, reset your password right away - that also logs out every device:
            |$baseUrl/reset_password.html
            """.trimMargin()
        try {
            mailSender.send(mail)
        } catch (e: Exception) {
            AppLogger.warn("Login alert mail to ${user.username} not sent: ${e.message}")
        }
    }

    /** "Pixel 7 (Android)". Device names are client-supplied, see [sanitize]. */
    private fun describeDevice(deviceName: String, deviceType: AuthController.DEVICETYPE?): String {
        val name = sanitize(deviceName, 80).ifBlank { "Unknown device" }
        val type = deviceType?.name?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "unknown type"
        return "$name ($type)"
    }

    /**
     * Client-supplied text goes into the mail body verbatim otherwise. Control characters (line
     * breaks above all) are stripped so a login with a crafted device name or User-Agent can't
     * inject extra lines - say, a phishing link - into the mail, and everything is length-capped.
     */
    private fun sanitize(value: String, maxLength: Int): String =
        value.replace(CONTROL_CHARS, " ").trim().take(maxLength)

    /**
     * Reverse DNS of the client address, which usually names the ISP ("...a1.net", "...drei.com")
     * and is far easier to judge than a bare IP. Null for private/loopback ranges and when no PTR
     * record exists. Blocking, but this runs on the async executor.
     */
    private fun reverseDns(ip: String): String? = try {
        val address = InetAddress.getByName(ip)
        if (address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress || address.isAnyLocalAddress) {
            null
        } else {
            address.canonicalHostName.takeIf { it != ip && it != address.hostAddress }
        }
    } catch (e: Exception) {
        null
    }

    private fun formatTime(instant: Instant): String =
        LOGIN_ALERT_TIME_FORMAT.format(java.time.Instant.ofEpochMilli(instant.toEpochMilliseconds()).atZone(LOGIN_ALERT_ZONE))

    fun getLastEmailTimestamp(userId: ObjectId, logType: LogType) : Instant? {
        return loggingService.getLastLogByLogtype(logType = logType, userId = userId)?.timestamp
    }

    companion object {
        /** Minimum gap between two login-alert mails to the same account. */
        private val LOGIN_ALERT_MIN_INTERVAL = Duration.parse("10m")
        private val CONTROL_CHARS = Regex("\\p{Cntrl}+")

        /** Users are in Austria and the server container runs in this zone too (see Dockerfile TZ). */
        private val LOGIN_ALERT_ZONE: ZoneId = ZoneId.of("Europe/Vienna")
        private val LOGIN_ALERT_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm z")
    }

}
