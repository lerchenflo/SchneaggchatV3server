package com.lerchenflo.schneaggchatv3server.notifications.websocket

import com.lerchenflo.schneaggchatv3server.website.admin.AdminEventService
import com.lerchenflo.schneaggchatv3server.core.security.JwtService
import com.lerchenflo.schneaggchatv3server.notifications.NotificationService
import com.lerchenflo.schneaggchatv3server.notifications.websocket.connectiontimelogger.ConnectionTimeLogger
import com.lerchenflo.schneaggchatv3server.notifications.websocket.model.ConnectedUser
import com.lerchenflo.schneaggchatv3server.notifications.websocket.model.SocketConnection
import com.lerchenflo.schneaggchatv3server.notifications.websocket.model.SocketConnectionMessage
import com.lerchenflo.schneaggchatv3server.schneaggmap.model.LatLong
import com.lerchenflo.schneaggchatv3server.schneaggmap.userlocations.LocationTelemetry
import com.lerchenflo.schneaggchatv3server.schneaggmap.userlocations.UserLocationService
import com.lerchenflo.schneaggchatv3server.user.UserLookupService
import com.lerchenflo.schneaggchatv3server.util.AppLogger
import com.lerchenflo.schneaggchatv3server.util.Json
import org.bson.types.ObjectId
import org.springframework.context.annotation.Lazy
import org.springframework.http.HttpStatus
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.PingMessage
import org.springframework.web.socket.PongMessage
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator
import org.springframework.web.socket.handler.TextWebSocketHandler
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

@Component
class SocketConnectionHandler(
    private val jwtService: JwtService,
    private val userLookupService: UserLookupService,
    private val connectionTimeLogger: ConnectionTimeLogger,
    // @Lazy breaks the cycle: UserLocationService -> NotificationService -> SocketConnectionHandler.
    @Lazy private val userLocationService: UserLocationService,
    // @Lazy breaks the cycle: NotificationService -> SocketConnectionHandler.
    @Lazy private val notificationService: NotificationService,
    // @Lazy breaks the cycle: AdminEventService -> SocketConnectionHandler (for the initial snapshot on registration).
    @Lazy private val adminEventService: AdminEventService,
): TextWebSocketHandler() {

    companion object {
        /** How often every live session gets a keepalive ping. */
        const val PING_INTERVAL_MS = 30_000L

        /**
         * A session that has not answered any ping for this long is considered dead and closed.
         * 2.5 ping intervals: one lost pong is tolerated, two in a row are not.
         */
        val PONG_TIMEOUT = (PING_INTERVAL_MS * 5 / 2).milliseconds

        /** A single send that blocks longer than this means the peer stopped reading -> close it. */
        private const val SEND_TIME_LIMIT_MS = 10_000

        /** Outbound bytes allowed to pile up behind a slow send before the session is cut off. */
        private const val SEND_BUFFER_LIMIT_BYTES = 512 * 1024
    }

    var connections : CopyOnWriteArrayList<SocketConnection> = CopyOnWriteArrayList()


    fun isConnected(userId: ObjectId) : Boolean {
        return connections.find { it.userId == userId } != null
    }

    /** Connected users aggregated across their (possibly multiple) sessions. For the admin panel. */
    fun getConnectedUsers(): List<ConnectedUser> =
        connections.groupBy { it.userId }
            .map { (userId, sessions) -> ConnectedUser(userId, sessions.size, sessions.minOf { it.startedAt }) }

    /**
     * Force-closes every live socket of one user, for the admin panel's "log out everywhere".
     * Only closes - [afterConnectionClosed] does the bookkeeping (removing the connection, writing
     * the connection log, updating lastSeen, notifying friends) via the close callback.
     *
     * Note this ends the realtime channel only: the user's already-issued access token stays valid
     * until it expires, since this server has no access-token revocation store.
     */
    fun disconnectUser(userId: ObjectId) {
        connections.filter { it.userId == userId }.forEach { connection ->
            try {
                connection.session.close(CloseStatus.NORMAL)
            } catch (e: Exception) {
                if (!e.isPeerGone()) AppLogger.error("Error force-closing socket for user $userId: ${e.describe()}")
            }
        }
    }

    fun broadcast(message: SocketConnectionMessage, excludeUserId: ObjectId?) {
        val json = Json.mapper.writeValueAsString(message)
        for (connection in connections) {
            if (excludeUserId != null && connection.userId == excludeUserId) continue
            try {
                connection.session.sendMessage(TextMessage(json))
            } catch (e: Exception) {
                if (!e.isPeerGone()) AppLogger.error("Error broadcasting to user ${connection.userId}: ${e.describe()}")
                closeDeadConnection(connection)
            }
        }
    }

    /**
     * Push [message] to every live session of [receiverId] - a user may be connected from several
     * devices at once (desktop and phone), and after a reconnect a not-yet-reaped stale session may
     * sit next to the live one; sending to only the first match would starve the others while
     * still reporting success. A session whose send fails is treated as dead and closed.
     *
     * @return true if at least one session accepted the message, false if the user has no
     * connection at all - the caller then falls back to FCM/APNs.
     */
    fun sendMessage(message: SocketConnectionMessage, receiverId: ObjectId) : Boolean {
        val userConnections = connections.filter { it.userId == receiverId }
        if (userConnections.isEmpty()) return false

        val jsonMessage = Json.mapper.writeValueAsString(message)
        var delivered = false
        for (connection in userConnections) {
            try {
                connection.session.sendMessage(TextMessage(jsonMessage))
                delivered = true
            } catch (e: Exception) {
                if (!e.isPeerGone()) {
                    AppLogger.error("Error sending socket message to user $receiverId (session ${connection.sessionId}): ${e.describe()}")
                }
                closeDeadConnection(connection)
            }
        }
        return delivered
    }

    override fun handleTextMessage(session: WebSocketSession, message: TextMessage) {
        val senderId = connections.find { it.sessionId == session.id }?.userId ?: return

        val parsed = try {
            Json.mapper.readValue(message.payload, SocketConnectionMessage::class.java)
        } catch (e: Exception) {
            AppLogger.warn("Could not parse inbound socket message from user $senderId: ${e.message}")
            return
        }

        when (parsed) {
            is SocketConnectionMessage.LocationUpdate -> {
                userLocationService.handleInboundLocationUpdate(
                    userId = senderId,
                    location = LatLong(lat = parsed.lat, long = parsed.long),
                    telemetry = LocationTelemetry(
                        speed = parsed.speed,
                        heading = parsed.heading,
                        altitude = parsed.altitude,
                        batteryLevel = parsed.batteryLevel,
                    ),
                )
            }
            // All other message types are server -> client only; ignore if received inbound.
            else -> AppLogger.warn("Ignoring unsupported inbound socket message type from user $senderId")
        }
    }

    /** Answer to one of [keepaliveSweep]'s pings: the peer is alive. */
    override fun handlePongMessage(session: WebSocketSession, message: PongMessage) {
        connections.find { it.sessionId == session.id }?.lastPongAt = Clock.System.now()
    }

    override fun handleTransportError(session: WebSocketSession, exception: Throwable) {
        // The container closes the session after this and afterConnectionClosed does the bookkeeping.
        if (exception.isPeerGone()) return
        AppLogger.warn("Socket transport error on session ${session.id}: ${exception.describe()}")
    }

    /**
     * Keepalive sweep. The server never learns on its own that a peer silently vanished (phone
     * radio already off when the app closed the socket, laptop lid closed, iOS suspended before
     * the close frame was flushed): TCP keeps accepting writes into its buffer for many minutes,
     * so [sendMessage] would keep "succeeding" against a dead socket, the push fallback would
     * never kick in, and the user would stay "online" for their friends. So ping every session on
     * a fixed cadence and treat one that has not ponged within [PONG_TIMEOUT] as gone.
     *
     * Deliberately pong-based rather than Tomcat's session idle timeout: Tomcat counts our own
     * outgoing pings as activity, so an idle timeout would never fire for a dead-but-pinged peer.
     */
    @Scheduled(fixedDelay = PING_INTERVAL_MS)
    fun keepaliveSweep() {
        val now = Clock.System.now()
        for (connection in connections) {
            val silentFor = now - connection.lastPongAt
            if (silentFor > PONG_TIMEOUT) {
                AppLogger.warn("Socket of user ${connection.userId} (session ${connection.sessionId}) silent for $silentFor, closing as dead")
                closeDeadConnection(connection)
                continue
            }
            try {
                connection.session.sendMessage(PingMessage())
            } catch (e: Exception) {
                if (!e.isPeerGone()) {
                    AppLogger.warn("Keepalive ping to user ${connection.userId} (session ${connection.sessionId}) failed: ${e.describe()}")
                }
                closeDeadConnection(connection)
            }
        }
    }

    /**
     * Close a session we consider dead. A clean close reports back through [afterConnectionClosed];
     * a transport that is already gone may never deliver that callback, so the bookkeeping is run
     * explicitly as well ([unregister] is idempotent).
     */
    private fun closeDeadConnection(connection: SocketConnection) {
        try {
            connection.session.close(CloseStatus.SESSION_NOT_RELIABLE)
        } catch (e: Exception) {
            if (!e.isPeerGone()) AppLogger.error("Error closing dead socket of user ${connection.userId}: ${e.describe()}")
        }
        unregister(connection.sessionId)
    }



    override fun afterConnectionEstablished(session: WebSocketSession) {
        super.afterConnectionEstablished(session)

        var requestingUserId : String? = null

        val authHeader = session.handshakeHeaders.get("Authorization")?.first()
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            if (jwtService.validateAccessToken(authHeader)) {
                requestingUserId = jwtService.getUserIdFromToken(authHeader)

            }
        }


        requestingUserId ?: run {
                AppLogger.warn("Socket connection not authenticated")
                throw ResponseStatusException(
                    /* status = */ HttpStatus.FORBIDDEN,
                    /* reason = */ "Not logged in"
                )
            }


        //println("New socket connection from user ${userLookupService.getUsername(ObjectId(requestingUserId))} IP: ${session.handshakeHeaders["X-Real-IP"]}")

        val userId = ObjectId(requestingUserId)

        // Sends come from many threads (notifications, keepalive pings) and Tomcat rejects
        // overlapping writes on one session, so serialize them; the limits additionally cut off a
        // peer that stopped reading (the decorator closes it with SESSION_NOT_RELIABLE).
        val safeSession = ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS, SEND_BUFFER_LIMIT_BYTES)

        //Update session or create new (Multiple connections from the same userid are allowed
        var wasOffline = false
        synchronized(connections) {
            wasOffline = connections.none { it.userId == userId }
            connections += SocketConnection(
                sessionId = session.id,
                userId = userId,
                session = safeSession,
            )
        }

        //println("Total connections: ${connections.size}")

        // Initial load: push the connecting user the current locations of all friends they may see.
        userLocationService.sendInitialLocationSnapshot(userId)

        // Initial load: push the connecting user which of their friends are currently online.
        notificationService.sendInitialFriendOnlineSnapshot(userId)

        // This was the user's first active session -> they just went online.
        if (wasOffline) {
            notificationService.notifyFriendOnlineStatusChange(userId, online = true)
        }

        adminEventService.publishConnectedUsers()
    }

    override fun afterConnectionClosed(session: WebSocketSession, status: CloseStatus) {
        super.afterConnectionClosed(session, status)

        //println("Socket connection closed: $status")
        unregister(session.id)
    }

    /**
     * Remove a session from the registry and do the offline bookkeeping (connection log,
     * lastSeen, friend notification) if it was the user's last one. Idempotent: a session that
     * is already gone is a no-op, so the close callback and [closeDeadConnection] can both call it.
     */
    private fun unregister(sessionId: String) {
        val connectionToRemove: SocketConnection?
        var isNowOffline = false

        synchronized(connections) {

            connectionToRemove = connections.find { it.sessionId == sessionId }

            connections.remove(connectionToRemove)

            isNowOffline = connectionToRemove != null && connections.none { it.userId == connectionToRemove.userId }
        }

        connectionToRemove ?: return

        connectionTimeLogger.upsertEntry(
            userId = connectionToRemove.userId,
            startTime = connectionToRemove.startedAt,
            endTime = Clock.System.now()
        )

        // That was the user's last active session -> they just went offline.
        if (isNowOffline) {
            val now = Clock.System.now()
            userLookupService.findById(connectionToRemove.userId)?.let { user ->
                userLookupService.save(user.copy(lastSeen = now, updatedAt = now))
            }
            notificationService.notifyFriendOnlineStatusChange(connectionToRemove.userId, online = false, lastSeen = now)
        }

        adminEventService.publishConnectedUsers()

        //println("Socket connection closed: $status. Remaining connections: ${connections.size}")
    }

    /**
     * A peer that vanished (app backgrounded, radio off, network switch, browser tab closed) shows
     * up here in two shapes, both of them normal traffic rather than server faults: an [IOException]
     * from the dead TCP connection, and Tomcat's [IllegalStateException] ("Message will not be sent
     * because the WebSocket session has been closed") when a send races the close callback that
     * would have removed the session from [connections]. Both are handled by dropping the
     * connection, so logging them would only be noise. Either shape can arrive wrapped in a
     * container exception, so the whole cause chain is checked.
     */
    private fun Throwable.isPeerGone(): Boolean =
        generateSequence(this) { it.cause.takeIf { cause -> cause !== it } }
            .any { it is IOException || it is IllegalStateException }

    /** Many transport exceptions carry a null message, which alone says nothing - name the type too. */
    private fun Throwable.describe(): String = "${this::class.simpleName}: ${message ?: "no message"}"
}
