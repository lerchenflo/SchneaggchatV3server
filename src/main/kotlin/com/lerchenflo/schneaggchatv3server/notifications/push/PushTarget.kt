package com.lerchenflo.schneaggchatv3server.notifications.push

import org.bson.types.ObjectId

enum class PushProvider { FCM, APNS }

/** One push token of [userId], and the service that delivers to it. */
data class PushTarget(
    val userId: ObjectId,
    val provider: PushProvider,
    val token: String,
)

data class PushOptions(
    /** How long the push service keeps trying. Null keeps its default, 0 means now or never. */
    val ttlSeconds: Long? = null,
    val providers: Set<PushProvider> = PushProvider.entries.toSet(),
)
