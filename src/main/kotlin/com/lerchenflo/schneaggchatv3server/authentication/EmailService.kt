package com.lerchenflo.schneaggchatv3server.authentication

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
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
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

        val user = userLookupService.findByObjectId(userId) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "User $userId not found")

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
        AppLogger.info("Email verified successfully for user ${user.id} (${user.email})")
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
        val user = userLookupService.findByObjectId(userId)
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
        mail.text = "Someone requested to reset your password. If this was not you, please ignore this email.\nTo reset your password, click the link below:\n$resetUrl\n\nThis link is valid for 1 hour."
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


    fun getLastEmailTimestamp(userId: ObjectId, logType: LogType) : Instant? {
        return loggingService.getLastLogByLogtype(logType = logType, userId = userId)?.timestamp
    }

}