package com.lerchenflo.schneaggchatv3server.authentication.model

fun RefreshToken.toSessionResponse() = SessionResponse(
    id = id.toHexString(),
    deviceName = deviceName,
    deviceType = deviceType,
    createdAt = createdAt.toEpochMilliseconds(),
    lastUsedAt = (lastUsedAt ?: createdAt).toEpochMilliseconds(),
)
