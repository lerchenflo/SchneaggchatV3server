package com.lerchenflo.schneaggchatv3server.notifications.firebase

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.*
import com.lerchenflo.schneaggchatv3server.core.security.JwtService
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
    private val jwtService: JwtService
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
            AppLogger.warn("Firebase Duplicate key already exists")
        }
    }

    fun deleteToken(token: String) {
        tokenRepository.deleteByToken(token)
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
    ) {
        val senderName = userLookupService.getUsername(senderId)

        val tokens = getTokensForUser(receiverId)

        if (tokens.isEmpty()) {
            //AppLogger.debug("Firebase: no tokens for user ${userLookupService.getUsername(receiverId)} found")
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val encodedContent = CryptoUtil.encrypt(messageContent, jwtService.getEncryptionKey())

                // Build the NotificationResponse and delegate to generic sender
                val notification = NotificationResponse.MessageNotificationResponse(
                    msgId = msgId,
                    senderName = senderName,
                    messageType = messageType,
                    groupMessage = groupMessage,
                    groupName = groupName ?: "",
                    encodedContent = encodedContent,
                    senderId = senderId.toHexString(),
                    receiverId = receiverId.toHexString()
                )

                // Reuse the generic sender
                sendNotificationToUser(receiverId, notification)

            } catch (e: Exception) {
                AppLogger.error("Error in Firebase notification coroutine: ${e.message}")
                e.printStackTrace()
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
                )

                sendNotificationToUser(receiverId, notification)

            } catch (e: Exception) {
                AppLogger.error("Error in Firebase reaction notification coroutine: ${e.message}")
                e.printStackTrace()
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
                    requesterId = receivingUserId.toHexString(),
                    requesterName = sendingUserName,
                    accepted = accepted,
                )


                // Reuse the generic sender
                sendNotificationToUser(receivingUserId, notification)

            } catch (e: Exception) {
                AppLogger.error("Error in Firebase notification coroutine: ${e.message}")
                e.printStackTrace()
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
                        safeSend(message = message, token = firebaseToken.token)
                    } catch (e: Exception) {
                        AppLogger.error("Error sending to token ${firebaseToken.token}: ${e.message}")
                        e.printStackTrace()
                    }
                }
            } catch (e: Exception) {
                AppLogger.error("Error in Firebase notification coroutine: ${e.message}")
                e.printStackTrace()
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
                        safeSend(message = message, token = firebaseToken.token)
                    } catch (e: Exception) {
                        AppLogger.error("Error sending wake to token ${firebaseToken.token}: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                AppLogger.error("Error in Firebase wake coroutine: ${e.message}")
                loggingService.log(
                    userId = userId,
                    logType = LogType.EXCEPTION_THROWN,
                    message = "Firebase wake notification error: ${e.message}"
                )
            }
        }

        return tokens.size
    }


    private fun safeSend(message: Message, token: String): Boolean {
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
                "INVALID_ARGUMENT",
            )

            return when (errorCode) {
                in setOf(
                    MessagingErrorCode.UNREGISTERED,
                    MessagingErrorCode.INVALID_ARGUMENT,
                    MessagingErrorCode.SENDER_ID_MISMATCH
                ) -> {
                    deleteToken(token)
                    false
                }

                // Null error code — fall back to raw string check
                null if rawErrorCode in invalidTokenRawCodes -> {
                    AppLogger.warn("[Firebase] Removing invalid token via raw error code '$rawErrorCode': $token")
                    deleteToken(token)
                    false
                }

                // Transient errors — don't remove token
                in setOf(
                    MessagingErrorCode.UNAVAILABLE,
                    MessagingErrorCode.INTERNAL,
                    MessagingErrorCode.QUOTA_EXCEEDED
                ) -> {
                    AppLogger.warn("[Firebase] Transient error for token: $token (Code: $errorCode)")
                    false
                }

                // Everything else (including null with unknown raw code) — log both codes
                else -> {
                    AppLogger.warn("[Firebase] Unhandled error for token: $token (Code: $errorCode, Raw: $rawErrorCode, HTTP: ${e.httpResponse?.statusCode})")
                    false
                }
            }
        } catch (e: Exception) {
            AppLogger.error("[Firebase] Unexpected exception: ${e.javaClass.simpleName}: ${e.message}")
            e.printStackTrace()
            loggingService.log(userId = null, logType = LogType.EXCEPTION_THROWN)
            return false
        }
    }



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
                            //.setBadge(1) //TODO: Get correct unread message count
                        .build())

                    .build()
            )
            .build()
    }


}