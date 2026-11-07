@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.notifications.apns

import com.lerchenflo.schneaggchatv3server.monitoring.ServerMetrics
import com.eatthepath.pushy.apns.ApnsClient
import com.eatthepath.pushy.apns.ApnsClientBuilder
import com.eatthepath.pushy.apns.DeliveryPriority
import com.eatthepath.pushy.apns.PushType
import com.eatthepath.pushy.apns.auth.ApnsSigningKey
import com.eatthepath.pushy.apns.util.SimpleApnsPushNotification
import com.lerchenflo.schneaggchatv3server.notifications.firebase.model.NotificationResponse
import com.lerchenflo.schneaggchatv3server.notifications.firebase.model.toPushData
import com.lerchenflo.schneaggchatv3server.notifications.push.DeviceDirectory
import com.lerchenflo.schneaggchatv3server.notifications.push.PushOptions
import com.lerchenflo.schneaggchatv3server.notifications.push.PushProvider
import com.lerchenflo.schneaggchatv3server.notifications.push.PushSender
import com.lerchenflo.schneaggchatv3server.notifications.push.PushTarget
import com.lerchenflo.schneaggchatv3server.schneaggmap.MapSightingType
import com.lerchenflo.schneaggchatv3server.util.*
import jakarta.annotation.PreDestroy
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.io.File
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.time.toJavaInstant

//APNs allows up to 30 days. 4 weeks is also FCM's default, so iPhones and Android phones wait equally long.
private val DEFAULT_TTL = 28.days

/**
 * The `apns-expiration` of a push. Pushy sends null as 0, and at 0 APNs tries once and stores
 * nothing, so an iPhone that is offline in that moment never gets the push. Pushy forces
 * java.time here.
 */
internal fun apnsExpiration(ttlSeconds: Long?, now: Instant): java.time.Instant? {
    val ttl = ttlSeconds?.seconds ?: DEFAULT_TTL
    return (now + ttl).toJavaInstant().takeIf { ttl.isPositive() }
}

@Service
class ApnsService(
    private val deviceDirectory: DeviceDirectory,
    private val loggingService: LoggingService,
    @Value("\${apns.team-id}") private val teamId: String,
    @Value("\${apns.key-id}") private val keyId: String,
    @Value("\${apns.bundle-id}") private val bundleId: String,
    @Value("\${apns.debug}") private val apnsDebug: Boolean,
) : PushSender {

    override val provider = PushProvider.APNS

    private var apnsClient: ApnsClient? = null

    init {
        run {
            if (teamId.isBlank() || keyId.isBlank() || bundleId.isBlank()) {
                AppLogger.error("APNs config missing (APNS_TEAM_ID, APNS_KEY_ID or APNS_BUNDLE_ID not set)", tag = "init_apns_config_missing")
                return@run
            }

            val resourcePath = "/app/ApnsAuthKey.p8"


            val keyFile = File(resourcePath)
            if (!keyFile.exists()) {
                AppLogger.error("APNs .p8 key file not found at $resourcePath", tag = "init_apns_key_missing")
                return@run
            }

            try {
                val signingKey = ApnsSigningKey.loadFromPkcs8File(keyFile, teamId, keyId)
                apnsClient = ApnsClientBuilder()
                    .setApnsServer(
                        if (apnsDebug) ApnsClientBuilder.DEVELOPMENT_APNS_HOST
                        else ApnsClientBuilder.PRODUCTION_APNS_HOST
                    )
                    .setSigningKey(signingKey)
                    .build()
                AppLogger.success("APNs client initialized (sandbox=$apnsDebug)")
            } catch (e: Exception) {
                AppLogger.error("APNs client initialization failed: ${e.message}", tag = "init_apns_failed")
            }
        }
    }

    @PreDestroy
    fun shutdown() {
        apnsClient?.close()?.get()
    }


    override fun send(target: PushTarget, notification: NotificationResponse, options: PushOptions) {
        val client = apnsClient ?: run {
            AppLogger.debug("APNs: client not initialized, skipping notification")
            return
        }

        val push = SimpleApnsPushNotification(
            /* token = */ target.token,
            /* topic = */ bundleId,
            /* payload = */ buildPayload(notification),
            /* invalidationTime = */ apnsExpiration(options.ttlSeconds, Clock.System.now()),
            /* priority = */ DeliveryPriority.IMMEDIATE,
            /* pushType = */ PushType.ALERT,
        )

        // Async, so the handling of a rejected token does not run on Pushy's event loop
        client.sendNotification(push).whenCompleteAsync { response, cause ->
            if (cause != null) {
                onSendFailed(target, cause)
            } else if (response.isAccepted) {
                ServerMetrics.apnsSent.incrementAndGet()
            } else {
                onRejected(target, response.rejectionReason.orElse("unknown"))
            }
        }
    }

    internal fun onRejected(target: PushTarget, reason: String) {
        ServerMetrics.apnsFailed.incrementAndGet()
        AppLogger.error("[APNs] Error sending notification: $reason", user = target.userId, tag = "send_apns_failed")
        when (reason) {
            // Not TopicDisallowed: that is our APNs setup, and deleting working tokens would stop the pushes for good
            "Unregistered", "ExpiredToken", "BadDeviceToken", "DeviceTokenNotForTopic" -> deviceDirectory.clearPushToken(target.token)
            else -> AppLogger.warn("[APNs] Rejected: $reason for token ${target.token}", user = target.userId, tag = "send_apns_rejected")
        }
    }

    private fun onSendFailed(target: PushTarget, cause: Throwable) {
        ServerMetrics.apnsFailed.incrementAndGet()
        AppLogger.error("[APNs] Unexpected exception: ${cause.javaClass.simpleName}: ${cause.message}", user = target.userId, error = cause, tag = "send_apns_unexpected")
        loggingService.log(userId = target.userId, logType = LogType.EXCEPTION_THROWN)
    }


    internal fun buildPayload(notification: NotificationResponse): String {
        // Fallback alert shown when the Notification Service Extension times out or isn't available.
        // apns-priority: 10 (IMMEDIATE) is set via DeliveryPriority.IMMEDIATE in send.
        val fallbackTitle = when (notification) {
            is NotificationResponse.MessageNotificationResponse ->
                if (notification.groupMessage) notification.groupName.ifEmpty { notification.senderName }
                else notification.senderName
            is NotificationResponse.FriendRequestNotificationResponse -> "Schneaggchat"
            is NotificationResponse.SystemNotificationResponse -> notification.title
            is NotificationResponse.BirthdayNotificationResponse ->
                if (notification.ownBirthday) "Happy Birthday!" else "Schneaggchat"
            is NotificationResponse.WakeNotificationResponse -> notification.senderName
            is NotificationResponse.EventNotificationResponse -> notification.creatorName
            is NotificationResponse.MapSightingNotificationResponse -> notification.entryName
        }
        val fallbackBody = when (notification) {
            is NotificationResponse.MessageNotificationResponse ->
                if (notification.reaction)
                    "${notification.senderName} reacted to your message"
                else if (notification.groupMessage)
                    "New message in ${notification.groupName.ifEmpty { notification.senderName }}"
                else
                    "New message from ${notification.senderName}"
            is NotificationResponse.FriendRequestNotificationResponse ->
                if (notification.accepted) "Friend request accepted" else "New friend request"
            is NotificationResponse.SystemNotificationResponse -> notification.message
            is NotificationResponse.BirthdayNotificationResponse ->
                if (notification.ownBirthday) "Happy birthday to you!"
                else "${notification.birthdayUserName} has birthday today"
            is NotificationResponse.WakeNotificationResponse ->
                notification.reason.ifEmpty { "wants to wake you" }
            is NotificationResponse.EventNotificationResponse -> notification.eventTitle
            is NotificationResponse.MapSightingNotificationResponse -> when (notification.sightingType) {
                MapSightingType.POLICE -> "Police seen"
                MapSightingType.RADAR -> "Mobile radar seen"
            }
        }

        // No interruption-level, so iOS uses active: shown at once with sound, and Focus and the
        // scheduled summary stay the user's choice. Delivery depends on priority and expiration.
        val payload: Map<String, Any> = mapOf(
            "aps" to mapOf(
                "alert" to mapOf("title" to fallbackTitle, "body" to fallbackBody),
                "sound" to "default",
                "mutable-content" to 1,
                "content-available" to 1,
            )
        ) + notification.toPushData()
        return Json.mapper.writeValueAsString(payload)
    }
}
