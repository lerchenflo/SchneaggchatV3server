package com.lerchenflo.schneaggchatv3server.notifications.websocket.model

import org.bson.types.ObjectId
import kotlin.time.Instant

/** One connected user, aggregated across their (possibly multiple) [SocketConnection]s. */
data class ConnectedUser(
    val userId: ObjectId,
    val sessionCount: Int,
    val onlineSince: Instant, // earliest of this user's active sessions
)
