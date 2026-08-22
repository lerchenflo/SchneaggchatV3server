package com.lerchenflo.schneaggchatv3server.notifications.apns

import com.eatthepath.pushy.apns.ApnsClient
import com.eatthepath.pushy.apns.ApnsClientBuilder
import com.eatthepath.pushy.apns.DeliveryPriority
import com.eatthepath.pushy.apns.PushType
import com.eatthepath.pushy.apns.auth.ApnsSigningKey
import com.eatthepath.pushy.apns.util.SimpleApnsPushNotification
import com.lerchenflo.schneaggchatv3server.core.security.JwtService
import com.lerchenflo.schneaggchatv3server.message.messagemodel.MessageType
import com.lerchenflo.schneaggchatv3server.notifications.apns.model.ApnsToken
import com.lerchenflo.schneaggchatv3server.notifications.firebase.model.NotificationResponse
import com.lerchenflo.schneaggchatv3server.repository.ApnsTokenRepository
import com.lerchenflo.schneaggchatv3server.user.UserLookupService
import com.lerchenflo.schneaggchatv3server.util.*
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.bson.types.ObjectId
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import java.io.File

@Service
class ApnsService(
    private val tokenRepository: ApnsTokenRepository,
    private val loggingService: LoggingService,
    private val userLookupService: UserLookupService,
    private val jwtService: JwtService,
    @Value("\${apns.team-id}") private val teamId: String,
    @Value("\${apns.key-id}") private val keyId: String,
    @Value("\${apns.bundle-id}") private val bundleId: String,
    @Value("\${apns.debug}") private val apnsDebug: Boolean,
) {

    private var apnsClient: ApnsClient? = null

    init {
        run {
            if (teamId.isBlank() || keyId.isBlank() || bundleId.isBlank()) {
                AppLogger.error("APNs config missing (APNS_TEAM_ID, APNS_KEY_ID or APNS_BUNDLE_ID not set)")
                return@run
            }

            val resourcePath = "/app/ApnsAuthKey.p8"


            val keyFile = File(resourcePath)
            if (!keyFile.exists()) {
                AppLogger.error("APNs .p8 key file not found at $resourcePath")
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
                AppLogger.error("APNs client initialization failed: ${e.message}")
            }
        }
    }

    @PreDestroy
    fun shutdown() {
        apnsClient?.close()?.get()
    }


    fun saveToken(userId: ObjectId, token: String) {
        try {
            val existingToken = tokenRepository.findByUserIdAndToken(userId, token)
            if (existingToken != null) return

            tokenRepository.save(ApnsToken(userId = userId, token = token))

            loggingService.log(
                userId = userId,
                logType = LogType.APNS_TOKEN_REGISTERED,
            )
        } catch (e: DuplicateKeyException) {
            AppLogger.warn("APNs duplicate token already exists")
        }
    }

    fun deleteToken(token: String) {
        tokenRepository.deleteByToken(token)
    }

    fun getTokensForUser(userId: ObjectId): List<ApnsToken> {
        return tokenRepository.findAllByUserId(userId)
    }


    fun sendNewMessageNotificationToUser(
        senderId: ObjectId,
        receiverId: ObjectId,
        messageContent: String,
        msgId: String,
        groupMessage: Boolean,
        messageType: MessageType,
        groupName: String? = null,
        groupId: ObjectId? = null,
    ) {
        val senderName = userLookupService.getUsername(senderId)
        val tokens = getTokensForUser(receiverId)

        if (tokens.isEmpty()) {
            //AppLogger.debug("APNs: no tokens for user ${userLookupService.getUsername(receiverId)} found")
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val encodedContent = CryptoUtil.encrypt(messageContent, jwtService.getEncryptionKey())
                val notification = NotificationResponse.MessageNotificationResponse(
                    msgId = msgId,
                    senderName = senderName,
                    messageType = messageType,
                    groupMessage = groupMessage,
                    groupName = groupName ?: "",
                    encodedContent = encodedContent,
                    senderId = senderId.toHexString(),
                    receiverId = receiverId.toHexString(),
                    groupId = groupId?.toHexString() ?: "",
                )
                sendNotificationToUser(receiverId, notification)
            } catch (e: Exception) {
                AppLogger.error("Error in APNs notification coroutine: ${e.message}")
                e.printStackTrace()
                loggingService.log(
                    userId = receiverId,
                    logType = LogType.EXCEPTION_THROWN,
                    message = "APNs notification error: ${e.message}"
                )
            }
        }
    }


    fun sendReactionNotificationToUser(
        reactorId: ObjectId,
        receiverId: ObjectId,
        reactionContent: String,
        msgId: String,
        groupMessage: Boolean,
        messageType: MessageType,
        groupName: String? = null,
        groupId: ObjectId? = null,
    ) {
        val reactorName = userLookupService.getUsername(reactorId)
        val tokens = getTokensForUser(receiverId)

        if (tokens.isEmpty()) {
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val encodedContent = CryptoUtil.encrypt(reactionContent, jwtService.getEncryptionKey())
                val notification = NotificationResponse.MessageNotificationResponse(
                    msgId = msgId,
                    senderName = reactorName,
                    messageType = messageType,
                    groupMessage = groupMessage,
                    groupName = groupName ?: "",
                    encodedContent = encodedContent,
                    senderId = reactorId.toHexString(),
                    receiverId = receiverId.toHexString(),
                    reaction = true,
                    groupId = groupId?.toHexString() ?: "",
                )
                sendNotificationToUser(receiverId, notification)
            } catch (e: Exception) {
                AppLogger.error("Error in APNs reaction notification coroutine: ${e.message}")
                e.printStackTrace()
                loggingService.log(
                    userId = receiverId,
                    logType = LogType.EXCEPTION_THROWN,
                    message = "APNs reaction notification error: ${e.message}"
                )
            }
        }
    }


    fun sendFriendRequestNotificationToUser(
        senderId: ObjectId,
        receivingUserId: ObjectId,
        accepted: Boolean
    ) {
        val sendingUserName = userLookupService.getUsername(senderId)
        val tokens = getTokensForUser(receivingUserId)

        if (tokens.isEmpty()) {
            //AppLogger.debug("APNs: no tokens for user ${userLookupService.getUsername(receivingUserId)} found")
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val notification = NotificationResponse.FriendRequestNotificationResponse(
                    requesterId = receivingUserId.toHexString(),
                    requesterName = sendingUserName,
                    accepted = accepted,
                )
                sendNotificationToUser(receivingUserId, notification)
            } catch (e: Exception) {
                AppLogger.error("Error in APNs notification coroutine: ${e.message}")
                e.printStackTrace()
                loggingService.log(
                    userId = senderId,
                    logType = LogType.EXCEPTION_THROWN,
                    message = "APNs notification error: ${e.message}"
                )
            }
        }
    }


    fun sendNotificationToUser(userId: ObjectId, notification: NotificationResponse) {
        val client = apnsClient ?: run {
            AppLogger.debug("APNs: client not initialized, skipping notification")
            return
        }

        val tokens = getTokensForUser(userId)
        if (tokens.isEmpty()) {
            //AppLogger.debug("APNs: no tokens for user ${userLookupService.getUsername(userId)} found")
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val payload = buildPayload(notification)
                tokens.forEach { apnsToken ->
                    if (apnsToken.token.isEmpty()) return@forEach
                    try {
                        val push = SimpleApnsPushNotification(
                            /* token = */ apnsToken.token,
                            /* topic = */ bundleId,
                            /* payload = */ payload,
                            /* invalidationTime = */ null,
                            /* priority = */ DeliveryPriority.IMMEDIATE,
                            /* pushType = */ PushType.ALERT,
                        )
                        safeSend(client, push)
                    } catch (e: Exception) {
                        AppLogger.error("Error sending APNs to token ${apnsToken.token}: ${e.message}")
                        e.printStackTrace()
                    }
                }
            } catch (e: Exception) {
                AppLogger.error("Error in APNs notification coroutine: ${e.message}")
                e.printStackTrace()
                loggingService.log(
                    userId = userId,
                    logType = LogType.EXCEPTION_THROWN,
                    message = "APNs notification error: ${e.message}"
                )
            }
        }
    }


    private fun safeSend(client: ApnsClient, notification: SimpleApnsPushNotification): Boolean {
        return try {
            val response = client.sendNotification(notification).get()
            if (response.isAccepted) {
                true
            } else {
                val reason = response.rejectionReason.orElse("unknown")
                AppLogger.error("Error sending notification APNs: $reason")
                when (reason) {
                    "Unregistered", "BadDeviceToken", "DeviceTokenNotForTopic", "TopicDisallowed" -> {
                        deleteToken(notification.token)
                        false
                    }
                    else -> {
                        AppLogger.warn("[APNs] Rejected: $reason for token ${notification.token}")
                        false
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.error("[APNs] Unexpected exception: ${e.javaClass.simpleName}: ${e.message}")
            e.printStackTrace()
            loggingService.log(userId = null, logType = LogType.EXCEPTION_THROWN)
            false
        }
    }


    @Suppress("UNCHECKED_CAST")
    private fun buildPayload(notification: NotificationResponse): String {
        val raw = Json.mapper.convertValue(notification, Map::class.java) as Map<String, Any?>? ?: emptyMap()
        val data: MutableMap<String, Any> = raw.mapValues { (_, v) ->
            when (v) {
                null -> ""
                is String -> v
                is Number, is Boolean -> v.toString()
                else -> Json.mapper.writeValueAsString(v)
            }
        }.toMutableMap()

        val typeName = when (notification) {
            is NotificationResponse.MessageNotificationResponse -> "message"
            is NotificationResponse.FriendRequestNotificationResponse -> "friend_request"
            is NotificationResponse.SystemNotificationResponse -> "system"
            is NotificationResponse.BirthdayNotificationResponse -> "birthday"
            //Wake is an Android-only feature, so this branch exists purely to keep the when
            //exhaustive. If one ever reaches APNs it degrades to an ordinary notification.
            is NotificationResponse.WakeNotificationResponse -> "wake"
            is NotificationResponse.EventNotificationResponse -> "event"
        }
        data["type"] = typeName

        // Fallback alert shown when the Notification Service Extension times out or isn't available.
        // apns-priority: 10 (IMMEDIATE) is set via DeliveryPriority.IMMEDIATE in sendNotificationToUser.
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
        }

        val payload: Map<String, Any> = mapOf(
            "aps" to mapOf(
                "alert" to mapOf("title" to fallbackTitle, "body" to fallbackBody),
                "sound" to "default",
                "mutable-content" to 1,
                "content-available" to 1,
                "interruption-level" to "time-sensitive",
            )
        ) + data
        return Json.mapper.writeValueAsString(payload)
    }
}
