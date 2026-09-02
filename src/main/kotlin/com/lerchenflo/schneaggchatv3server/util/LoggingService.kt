@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.util

import com.lerchenflo.schneaggchatv3server.authentication.AuthController
import com.lerchenflo.schneaggchatv3server.message.MessageLookupService
import com.lerchenflo.schneaggchatv3server.repository.LogRepository
import com.lerchenflo.schneaggchatv3server.repository.MapEntryRepository
import com.lerchenflo.schneaggchatv3server.repository.RefreshTokenRepository
import com.lerchenflo.schneaggchatv3server.repository.UserRepository
import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.TypeAlias
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.mapping.Document
import org.springframework.stereotype.Service
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

enum class LogType {
    USER_LOGIN,
    SERVER_START,
    MESSAGE_DELETED,
    POLL_OPTION_DELETED,
    GROUP_CREATED,
    GROUP_DELETED,
    FIREBASE_TOKEN_REGISTERED,
    APNS_TOKEN_REGISTERED,
    FRIEND_REQUEST_SENT,
    EXCEPTION_THROWN,

    //From other repos
    MESSAGE_SENT,
    ACCOUNT_CREATED,


    //Map
    MAP_ENTRY_CREATED,
    MAP_ENTRY_EDITED,
    MAP_ENTRY_DELETED,

    //Games
    GAME_SCORE_SUBMITTED,

    ACCOUNT_DELETION_EMAIL_SENT,
    EMAIL_VERIFICATION_EMAIL_SENT,
    PASSWORD_RESET_EMAIL_SENT,

    //Wake
    WAKE_SENT
}

@TypeAlias("log")
@Document("logs")
@CompoundIndex(name = "logtype_userid_timestamp_idx", def = "{'logType': 1, 'userId': 1, 'timestamp': -1}")
data class Log(
    @Id val id: ObjectId = ObjectId.get(),
    val userId: ObjectId?,
    val logType: LogType,
    val message : String? = null,
    val timestamp: Instant = Clock.System.now(),
)

@Service
class LoggingService(
    private val logRepository: LogRepository,
    private val messageLookupService: MessageLookupService,
    private val userRepository: UserRepository,
    private val mapEntryRepository: MapEntryRepository,
    private val refreshTokenRepository: RefreshTokenRepository,

    ) {

    init {
        log(
            userId = null,
            logType = LogType.SERVER_START,
        )
    }


    fun log(userId: ObjectId?, logType: LogType, message: String? = null) {
        logRepository.save(
            Log(
                userId = userId,
                logType = logType,
                message = message,
            )
        )
    }

    fun getStats() : Map<String, Long> {
        val stats = LogType.entries.associate { logType ->

            when (logType) {
                LogType.MESSAGE_SENT -> logType.name to messageLookupService.count()
                LogType.ACCOUNT_CREATED -> logType.name to userRepository.count()
                LogType.MAP_ENTRY_CREATED -> logType.name to mapEntryRepository.count()
                else -> logType.name to logRepository.countByLogType(logType)
            }
        }

        return stats
    }

    fun getLastLogByLogtype(logType: LogType, userId: ObjectId?): Log? {
        return logRepository.findFirstByLogTypeAndUserIdOrderByTimestampDesc(logType, userId)
    }

    /**
     * Active devices grouped by [AuthController.DEVICETYPE], keyed by enum name for the same
     * reason [getStats] is String-keyed - it goes straight into a Thymeleaf
     * `${deviceCounts['ANDROID']}` lookup. One unexpired session row = one logged-in device
     * (rotation is in place, login dedups per device - see `RefreshToken`). Tokens issued before
     * `deviceType` existed have it `null`; those are counted under "UNKNOWN" rather than
     * silently dropped.
     */
    fun getActiveDeviceCount(): Map<String, Long> {
        val now = Clock.System.now()

        val byType = AuthController.DEVICETYPE.entries.associate { deviceType ->
            deviceType.name to refreshTokenRepository.countByDeviceTypeAndExpiresAtAfter(deviceType, now)
        }

        return byType + ("UNKNOWN" to refreshTokenRepository.countByDeviceTypeIsNullAndExpiresAtAfter(now))
    }
}