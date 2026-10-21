package com.lerchenflo.schneaggchatv3server.notifications.firebase

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.*
import com.lerchenflo.schneaggchatv3server.message.messagemodel.MessageType
import com.lerchenflo.schneaggchatv3server.notifications.firebase.model.FirebaseToken
import com.lerchenflo.schneaggchatv3server.notifications.firebase.model.NotificationResponse
import com.lerchenflo.schneaggchatv3server.repository.FirebaseTokenRepository
import com.lerchenflo.schneaggchatv3server.user.UserLookupService
import com.lerchenflo.schneaggchatv3server.util.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.bson.types.ObjectId
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import java.io.FileInputStream

@Service
class FirebaseService(
    private val tokenRepository: FirebaseTokenRepository,
    private val loggingService: LoggingService,
    private val userLookupService: UserLookupService,
) {

    init {
        run {
            val resourceName = "schneaggchatv3-firebase-admin.json"

            val credentialsStream = this::class.java.classLoader
                .getResourceAsStream(resourceName)
                ?: try {
                    // fallback to expected mounted path inside container
                    FileInputStream("/app/$resourceName")
                } catch (e: Exception) {
                    AppLogger.error("Firebase json not found in path /app/$resourceName")

                    return@run //Return to not crash the server docker
                }



            val options = FirebaseOptions.builder()
                .setCredentials(GoogleCredentials.fromStream(credentialsStream))
                .build()

            if (FirebaseApp.getApps().isEmpty()) {
                FirebaseApp.initializeApp(options)
                AppLogger.success("Firebase initialized successfully")
            } else {
                AppLogger.info("Firebase already initialized")
            }
        }

    }


    fun saveToken(userId: ObjectId, token: String) {
        try {
            // Check if token already exists for this user
            val existingToken = tokenRepository.findByUserIdAndToken(userId, token)

            if (existingToken != null) {
                //println("Firebase token exists, not updating")
                return
            }

            // Save new token
            tokenRepository.save(
                FirebaseToken(
                    userId = userId,
                    token = token
                )
            )

            //Save here if duplicate key exception occurs
            loggingService.log(
                userId = userId,
                logType = LogType.FIREBASE_TOKEN_REGISTERED,
            )

            //println("Firebasetoken saved successfully")
        } catch (e: DuplicateKeyException) {
            AppLogger.warn("[Firebase] Duplicate token already exists", user = userId)
        }
    }

    fun deleteToken(token: String) {
        tokenRepository.deleteByToken(token)
    }

    /**
     * Ownership checked delete for logout: [deleteToken] matches on the token alone, so calling it
     * with a client supplied token would let anyone unregister another device's push token.
     */
    fun deleteTokenForUser(userId: ObjectId, token: String) {
        tokenRepository.findByUserIdAndToken(userId, token)?.let { tokenRepository.delete(it) }
    }

    fun deleteAllTokensForUser(userId: ObjectId) {
        tokenRepository.deleteAll(tokenRepository.findAllByUserId(userId))
    }

    fun getTokensForUser(userId: ObjectId): List<FirebaseToken> {
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
        sendDate: Long = 0L,
        answerId: String? = null,
    ) {
        val senderName = userLookupService.getUsername(senderId)

        val tokens = getTokensForUser(receiverId)

        if (tokens.isEmpty()) {
            //AppLogger.debug("Firebase: no tokens for user ${userLookupService.getUsername(receiverId)} found")
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {

                // Build the NotificationResponse and delegate to generic sender
                val notification = NotificationResponse.MessageNotificationResponse(
                    msgId = msgId,
                    senderName = senderName,
                    messageType = messageType,
                    groupMessage = groupMessage,
                    groupName = groupName ?: "",
                    content = messageContent,
                    senderId = senderId.toHexString(),
                    receiverId = receiverId.toHexString(),
                    groupId = groupId?.toHexString() ?: "",
                    sendDate = sendDate,
                    answerId = answerId ?: "",
                )

                // Reuse the generic sender
                sendNotificationToUser(receiverId, notification)

            } catch (e: Exception) {
                AppLogger.error("[Firebase] Error in notification coroutine: ${e.message}", user = receiverId, error = e)
                loggingService.log(
                    userId = receiverId,
                    logType = LogType.EXCEPTION_THROWN,
                    message = "Firebase notification error: ${e.message}"
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

                val notification = NotificationResponse.MessageNotificationResponse(
                    msgId = msgId,
                    senderName = reactorName,
                    messageType = messageType,
                    groupMessage = groupMessage,
                    groupName = groupName ?: "",
                    content = reactionContent,
                    senderId = reactorId.toHexString(),
                    receiverId = receiverId.toHexString(),
                    reaction = true,
                    groupId = groupId?.toHexString() ?: "",
                )

                sendNotificationToUser(receiverId, notification)

            } catch (e: Exception) {
                AppLogger.error("[Firebase] Error in reaction notification coroutine: ${e.message}", user = receiverId, error = e)
                loggingService.log(
                    userId = receiverId,
                    logType = LogType.EXCEPTION_THROWN,
                    message = "Firebase reaction notification error: ${e.message}"
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
            //AppLogger.debug("Firebase: no tokens for user ${userLookupService.getUsername(receivingUserId)} found")
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {

                // Build the NotificationResponse and delegate to generic sender
                val notification = NotificationResponse.FriendRequestNotificationResponse(
                    requesterId = senderId.toHexString(),
                    requesterName = sendingUserName,
                    accepted = accepted,
                )


                // Reuse the generic sender
                sendNotificationToUser(receivingUserId, notification)

            } catch (e: Exception) {
                AppLogger.error("[Firebase] Error in notification coroutine: ${e.message}", user = receivingUserId, error = e)
                loggingService.log(
                    userId = senderId,
                    logType = LogType.EXCEPTION_THROWN,
                    message = "Firebase notification error: ${e.message}"
                )
            }
        }
    }



    fun sendNotificationToUser(userId: ObjectId, notification: NotificationResponse) {
        val tokens = getTokensForUser(userId)
        if (tokens.isEmpty()) {
            //AppLogger.debug("Firebase: no tokens for user ${userLookupService.getUsername(userId)} found")
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dataMap = notificationResponseToDataMap(notification)

                tokens.forEach { firebaseToken ->
                    if (firebaseToken.token.isEmpty()) return@forEach
                    try {
                        val message = constructMessage(
                            firebaseToken = firebaseToken.token,
                            data = dataMap
                        )
                        safeSend(message = message, firebaseToken = firebaseToken)
                    } catch (e: Exception) {
                        AppLogger.error("[Firebase] Error sending to token ${firebaseToken.token}: ${e.message}", user = userId, error = e)
                    }
                }
            } catch (e: Exception) {
                AppLogger.error("[Firebase] Error in notification coroutine: ${e.message}", user = userId, error = e)
                loggingService.log(
                    userId = userId,
                    logType = LogType.EXCEPTION_THROWN,
                    message = "Firebase notification error: ${e.message}"
                )
            }
        }
    }



    /**
     * Send a wake notification and report how many devices it was dispatched to.
     *
     * Unlike [sendNotificationToUser] the token count is resolved synchronously before the
     * coroutine is launched, because the sender is shown this number and fire-and-forget
     * dispatch makes per-token success unobservable. The returned count is therefore
     * "devices we sent to", not "devices that rang".
     *
     * Uses TTL 0: a wake that arrives after the phone comes back online is worse than useless.
     */
    fun sendWakeToUser(userId: ObjectId, notification: NotificationResponse.WakeNotificationResponse): Int {
        val tokens = getTokensForUser(userId).filter { it.token.isNotEmpty() }
        if (tokens.isEmpty()) return 0

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dataMap = notificationResponseToDataMap(notification)

                tokens.forEach { firebaseToken ->
                    try {
                        val message = constructMessage(
                            firebaseToken = firebaseToken.token,
                            data = dataMap,
                            timeToLiveSeconds = 0
                        )
                        safeSend(message = message, firebaseToken = firebaseToken)
                    } catch (e: Exception) {
                        AppLogger.error("[Firebase] Error sending wake to token ${firebaseToken.token}: ${e.message}", user = userId)
                    }
                }
            } catch (e: Exception) {
                AppLogger.error("[Firebase] Error in wake coroutine: ${e.message}", user = userId)
                loggingService.log(
                    userId = userId,
                    logType = LogType.EXCEPTION_THROWN,
                    message = "Firebase wake notification error: ${e.message}"
                )
            }
        }

        return tokens.size
    }


    private fun safeSend(message: Message, firebaseToken: FirebaseToken): Boolean {
        val token = firebaseToken.token
        try {
            val response = FirebaseMessaging.getInstance().send(message)
            return true

        } catch (e: FirebaseMessagingException) {
            val errorCode = e.messagingErrorCode
            //e.printStackTrace()
            val rawErrorCode = e.errorCode?.name // fallback: raw string like "registration-token-not-registered"

            // Known invalid-token raw error codes from Firebase
            val invalidTokenRawCodes = setOf(
                "REGISTRATION_TOKEN_NOT_REGISTERED",
                "INVALID_REGISTRATION_TOKEN",
                "MISMATCHED_CREDENTIAL",
            )

            return when (errorCode) {
                in setOf(
                    MessagingErrorCode.UNREGISTERED,
                    MessagingErrorCode.SENDER_ID_MISMATCH
                ) -> {
                    AppLogger.warn("[Firebase] Removing invalid token (Code: $errorCode): $token", user = firebaseToken.userId)
                    deleteToken(token)
                    false
                }

                MessagingErrorCode.INVALID_ARGUMENT if isInvalidTokenError(e) -> {
                    AppLogger.warn("[Firebase] Removing invalid token (Code: $errorCode): $token", user = firebaseToken.userId)
                    deleteToken(token)
                    false
                }

                // A bad payload, not a bad token - the token stays
                MessagingErrorCode.INVALID_ARGUMENT -> {
                    AppLogger.warn("[Firebase] Invalid argument, token kept (${e.message}): $token", user = firebaseToken.userId)
                    false
                }

                // FCM tried to reach APNs for this token, so it is an iOS FCM token from before the APNs split.
                // The iOS app is gone from the Firebase project, so these can never be delivered again. The
                // ApnsError detail proves the token went to Apple, so an Android token is never removed here.
                MessagingErrorCode.THIRD_PARTY_AUTH_ERROR if e.httpResponse?.content?.contains("ApnsError") == true -> {
                    AppLogger.warn("[Firebase] Removing leftover iOS token (Code: $errorCode): $token", user = firebaseToken.userId)
                    deleteToken(token)
                    false
                }

                // Without the APNs detail it is unclear which push service failed, so the token stays
                MessagingErrorCode.THIRD_PARTY_AUTH_ERROR -> {
                    AppLogger.warn("[Firebase] Third party auth error (Code: $errorCode, HTTP: ${e.httpResponse?.statusCode}): $token", user = firebaseToken.userId)
                    false
                }

                // Null error code — fall back to raw string check
                null if (rawErrorCode in invalidTokenRawCodes || (rawErrorCode == "INVALID_ARGUMENT" && isInvalidTokenError(
                    e
                ))) -> {
                    AppLogger.warn("[Firebase] Removing invalid token via raw error code '$rawErrorCode': $token", user = firebaseToken.userId)
                    deleteToken(token)
                    false
                }

                // Transient errors — don't remove token
                in setOf(
                    MessagingErrorCode.UNAVAILABLE,
                    MessagingErrorCode.INTERNAL,
                    MessagingErrorCode.QUOTA_EXCEEDED
                ) -> {
                    AppLogger.warn("[Firebase] Transient error for token: $token (Code: $errorCode)", user = firebaseToken.userId)
                    false
                }

                // Everything else (including null with unknown raw code) — log both codes
                else -> {
                    AppLogger.warn("[Firebase] Unhandled error for token: $token (Code: $errorCode, Raw: $rawErrorCode, HTTP: ${e.httpResponse?.statusCode})", user = firebaseToken.userId)
                    false
                }
            }
        } catch (e: Exception) {
            AppLogger.error("[Firebase] Unexpected exception: ${e.javaClass.simpleName}: ${e.message}", user = firebaseToken.userId, error = e)
            loggingService.log(userId = firebaseToken.userId, logType = LogType.EXCEPTION_THROWN)
            return false
        }
    }

    /**
     * Whether an INVALID_ARGUMENT error is about the registration token itself. FCM sends the same code
     * for a bad payload, and deleting a working token there stops all pushes to that phone until the app
     * uploads its token again. A bad token is named in the message ("The registration token is not a
     * valid FCM registration token") and as the field `message.token` in the error details.
     */
    private fun isInvalidTokenError(e: FirebaseMessagingException): Boolean =
        e.message?.contains("registration token", ignoreCase = true) == true ||
            e.httpResponse?.content?.contains("\"message.token\"") == true



    private fun notificationResponseToDataMap(notification: NotificationResponse): Map<String, String> {
        // convert to a raw Map<*,*>
        val raw = Json.mapper.convertValue(notification, Map::class.java) as Map<String, Any?>? ?: emptyMap()

        val result = raw.mapValues { (_, v) ->
            when (v) {
                null -> ""
                is String -> v
                is Number, is Boolean -> v.toString()
                else -> Json.mapper.writeValueAsString(v) // nested / complex objects -> JSON string
            }
        }.toMutableMap()

        // ensure "type" field (JsonTypeInfo property)
        val typeName = when (notification) {
            is NotificationResponse.MessageNotificationResponse -> "message"
            is NotificationResponse.FriendRequestNotificationResponse -> "friend_request"
            is NotificationResponse.SystemNotificationResponse -> "system"
            is NotificationResponse.BirthdayNotificationResponse -> "birthday"
            is NotificationResponse.WakeNotificationResponse -> "wake"
            is NotificationResponse.EventNotificationResponse -> "event"
        }
        result["type"] = typeName

        // FCM rejects data over 4 KB with INVALID_ARGUMENT and the push is lost. Long texts go out
        // without content: the client then shows "new messages" and the sync brings the full message.
        val dataBytes = result.entries.sumOf { it.key.toByteArray().size + it.value.toByteArray().size }
        if (dataBytes > MAX_DATA_BYTES && result.containsKey("content")) result["content"] = ""

        // cast to Map<String,String>
        return result.mapValues { it.value as String }
    }

    private fun constructMessage(firebaseToken: String, data: Map<String, String>, timeToLiveSeconds: Long? = null) : Message {
        return Message.builder()
            .setToken(firebaseToken)
            .putAllData(data)
            .setAndroidConfig(
                AndroidConfig.builder()
                    .setPriority(AndroidConfig.Priority.HIGH) //for immediate delivery: https://firebase.google.com/docs/cloud-messaging/android-message-priority?hl=de
                    //Only set when the caller cares - omitting it keeps FCM's default 4 week TTL.
                    .apply { timeToLiveSeconds?.let { setTtl(it * 1000) } }
                    .build()
            )
            //APNS CONFIG IS UNUSED, REPLACED BY APNS IMPLEMENTATION
            .setApnsConfig(
                /*
                Priority:
                Apple docs say 10 for immediate: //https://developer.apple.com/library/archive/documentation/NetworkingInternet/Conceptual/RemoteNotificationsPG/CommunicatingwithAPNs.html#//apple_ref/doc/uid/TP40008194-CH11-SW1
                Firebasee docs say 10 gets blocked: https://firebase.google.com/docs/cloud-messaging/customize-messages/setting-message-priority?hl=de

                solution: Alert, decrypt custom on recieving device (implement first)
                 */
                ApnsConfig.builder()
                    .putHeader("apns-priority", "5")
                    .setAps(
                        Aps.builder()
                            .setContentAvailable(true) //Allow background work on ios
                        .build())

                    .build()
            )
            .build()
    }

    private companion object {
        /** FCM's data limit is 4096 bytes; the rest is room for what FCM adds itself. */
        const val MAX_DATA_BYTES = 3800
    }
}