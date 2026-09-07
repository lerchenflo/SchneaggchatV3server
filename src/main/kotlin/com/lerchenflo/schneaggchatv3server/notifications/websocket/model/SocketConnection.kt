package com.lerchenflo.schneaggchatv3server.notifications.websocket.model

import org.bson.types.ObjectId
import org.springframework.web.socket.WebSocketSession
import kotlin.time.Clock
import kotlin.time.Instant

data class SocketConnection (
    val sessionId: String,
    val userId: ObjectId,
    val session: WebSocketSession,

    val startedAt: Instant = Clock.System.now(),
) {
    /**
     * Last moment this session proved it is alive: its connect, or its latest pong to one of the
     * server's keepalive pings. SocketConnectionHandler.keepaliveSweep closes sessions that stay
     * silent for too long, since a vanished peer (radio off, device suspended) never sends a close.
     */
    @Volatile
    var lastPongAt: Instant = startedAt
}
