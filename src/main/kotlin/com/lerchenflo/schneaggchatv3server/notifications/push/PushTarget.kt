package com.lerchenflo.schneaggchatv3server.notifications.push

import org.bson.types.ObjectId

enum class PushProvider { FCM, APNS }

/** One push token of [userId], and the service that delivers to it. */
data class PushTarget(
    val userId: ObjectId,
    val provider: PushProvider,
    val token: String,
    /** The device's session row. Null for a legacy token, which can not be matched to a device. */
    val deviceId: ObjectId? = null,
)

data class PushOptions(
    /** How long the push service keeps trying. Null keeps its default, 0 means now or never. */
    val ttlSeconds: Long? = null,
    val providers: Set<PushProvider> = PushProvider.entries.toSet(),
)
