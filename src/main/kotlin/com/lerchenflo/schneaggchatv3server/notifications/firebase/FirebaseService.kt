package com.lerchenflo.schneaggchatv3server.notifications.firebase

import com.lerchenflo.schneaggchatv3server.monitoring.ServerMetrics
import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.*
import com.lerchenflo.schneaggchatv3server.notifications.firebase.model.NotificationResponse
import com.lerchenflo.schneaggchatv3server.notifications.firebase.model.toPushData
import com.lerchenflo.schneaggchatv3server.notifications.push.DeviceDirectory
import com.lerchenflo.schneaggchatv3server.notifications.push.PushOptions
import com.lerchenflo.schneaggchatv3server.notifications.push.PushProvider
import com.lerchenflo.schneaggchatv3server.notifications.push.PushSender
import com.lerchenflo.schneaggchatv3server.notifications.push.PushTarget
import com.lerchenflo.schneaggchatv3server.util.*
import org.springframework.stereotype.Service
import java.io.FileInputStream

@Service
class FirebaseService(
    private val deviceDirectory: DeviceDirectory,
    private val loggingService: LoggingService,
) : PushSender {

    override val provider = PushProvider.FCM

    init {
        run {
            val resourceName = "schneaggchatv3-firebase-admin.json"

            val credentialsStream = this::class.java.classLoader
                .getResourceAsStream(resourceName)
                ?: try {
                    // fallback to expected mounted path inside container
                    FileInputStream("/app/$resourceName")
                } catch (e: Exception) {
                    AppLogger.error("Firebase json not found in path /app/$resourceName", tag = "init_firebase_config_missing")

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


    override fun send(target: PushTarget, notification: NotificationResponse, options: PushOptions) {
        val data = withinDataLimit(notification.toPushData())
        safeSend(constructMessage(target.token, data, options.ttlSeconds), target)
    }

    /**
     * FCM rejects data over 4 KB with INVALID_ARGUMENT and the push is lost. Long texts go out
     * without content: the client then shows "new messages" and the sync brings the full message.
     */
    private fun withinDataLimit(data: Map<String, String>): Map<String, String> {
        val dataBytes = data.entries.sumOf { it.key.toByteArray().size + it.value.toByteArray().size }
        return if (dataBytes > MAX_DATA_BYTES && data.containsKey("content")) data + ("content" to "") else data
    }


    private fun safeSend(message: Message, target: PushTarget): Boolean {
        val token = target.token
        try {
            val response = FirebaseMessaging.getInstance().send(message)
            ServerMetrics.fcmSent.incrementAndGet()
            return true

        } catch (e: FirebaseMessagingException) {
            ServerMetrics.fcmFailed.incrementAndGet()
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
                    AppLogger.warn("[Firebase] Removing invalid token (Code: $errorCode): $token", user = target.userId)
                    deviceDirectory.clearPushToken(token)
                    false
                }

                MessagingErrorCode.INVALID_ARGUMENT if isInvalidTokenError(e) -> {
                    AppLogger.warn("[Firebase] Removing invalid token (Code: $errorCode): $token", user = target.userId)
                    deviceDirectory.clearPushToken(token)
                    false
                }

                // A bad payload, not a bad token - the token stays
                MessagingErrorCode.INVALID_ARGUMENT -> {
                    AppLogger.warn("[Firebase] Invalid argument, token kept (${e.message}): $token", user = target.userId)
                    false
                }

                // FCM tried to reach APNs for this token, so it is an iOS FCM token from before the APNs split.
                // The iOS app is gone from the Firebase project, so these can never be delivered again. The
                // ApnsError detail proves the token went to Apple, so an Android token is never removed here.
                MessagingErrorCode.THIRD_PARTY_AUTH_ERROR if e.httpResponse?.content?.contains("ApnsError") == true -> {
                    AppLogger.warn("[Firebase] Removing leftover iOS token (Code: $errorCode): $token", user = target.userId)
                    deviceDirectory.clearPushToken(token)
                    false
                }

                // Without the APNs detail it is unclear which push service failed, so the token stays
                MessagingErrorCode.THIRD_PARTY_AUTH_ERROR -> {
                    AppLogger.warn("[Firebase] Third party auth error (Code: $errorCode, HTTP: ${e.httpResponse?.statusCode}): $token", user = target.userId)
                    false
                }

                // Null error code — fall back to raw string check
                null if (rawErrorCode in invalidTokenRawCodes || (rawErrorCode == "INVALID_ARGUMENT" && isInvalidTokenError(
                    e
                ))) -> {
                    AppLogger.warn("[Firebase] Removing invalid token via raw error code '$rawErrorCode': $token", user = target.userId, tag = "remove_fcm_invalid_token")
                    deviceDirectory.clearPushToken(token)
                    false
                }

                // Transient errors — don't remove token
                in setOf(
                    MessagingErrorCode.UNAVAILABLE,
                    MessagingErrorCode.INTERNAL,
                    MessagingErrorCode.QUOTA_EXCEEDED
                ) -> {
                    AppLogger.warn("[Firebase] Transient error for token: $token (Code: $errorCode)", user = target.userId, tag = "send_fcm_transient_error")
                    false
                }

                // Everything else (including null with unknown raw code) — log both codes
                else -> {
                    AppLogger.warn("[Firebase] Unhandled error for token: $token (Code: $errorCode, Raw: $rawErrorCode, HTTP: ${e.httpResponse?.statusCode})", user = target.userId, tag = "send_fcm_unhandled_error")
                    false
                }
            }
        } catch (e: Exception) {
            AppLogger.error("[Firebase] Unexpected exception: ${e.javaClass.simpleName}: ${e.message}", user = target.userId, error = e, tag = "send_fcm_unexpected")
            ServerMetrics.fcmFailed.incrementAndGet()
            loggingService.log(userId = target.userId, logType = LogType.EXCEPTION_THROWN)
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
            .build()
    }

    private companion object {
        /** FCM's data limit is 4096 bytes; the rest is room for what FCM adds itself. */
        const val MAX_DATA_BYTES = 3800
    }
}