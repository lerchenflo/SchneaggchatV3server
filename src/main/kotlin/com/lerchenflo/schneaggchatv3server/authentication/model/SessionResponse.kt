@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.authentication.model

import com.lerchenflo.schneaggchatv3server.authentication.AuthController
import kotlin.time.ExperimentalTime

/**
 * One logged-in device in the user's device list (`GET /sessions`). Never carries token data.
 */
data class SessionResponse(
    val id: String,
    /** Raw device name as the client sent it, including its device id suffix - the client trims it for display and matches it to find itself. */
    val deviceName: String?,
    val deviceType: AuthController.DEVICETYPE?,
    /** First login of this device, epoch millis. */
    val createdAt: Long,
    /** Last login or token refresh, epoch millis. Falls back to [createdAt] for rows predating the field. */
    val lastUsedAt: Long,
)

fun RefreshToken.toSessionResponse() = SessionResponse(
    id = id.toHexString(),
    deviceName = deviceName,
    deviceType = deviceType,
    createdAt = createdAt.toEpochMilliseconds(),
    lastUsedAt = (lastUsedAt ?: createdAt).toEpochMilliseconds(),
)
