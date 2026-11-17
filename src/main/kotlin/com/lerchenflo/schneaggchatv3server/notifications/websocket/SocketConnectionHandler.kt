package com.lerchenflo.schneaggchatv3server.notifications.websocket

import com.lerchenflo.schneaggchatv3server.website.admin.AdminEventService
import com.lerchenflo.schneaggchatv3server.core.security.JwtService
import com.lerchenflo.schneaggchatv3server.notifications.NotificationService
import com.lerchenflo.schneaggchatv3server.notifications.websocket.connectiontimelogger.ConnectionTimeLogger
import com.lerchenflo.schneaggchatv3server.notifications.websocket.model.ConnectedUser
import com.lerchenflo.schneaggchatv3server.notifications.websocket.model.SocketConnection
import com.lerchenflo.schneaggchatv3server.notifications.websocket.model.SocketConnectionMessage
import com.lerchenflo.schneaggchatv3server.repository.RefreshTokenRepository
import com.lerchenflo.schneaggchatv3server.schneaggmap.model.LatLong
import com.lerchenflo.schneaggchatv3server.schneaggmap.userlocations.LocationTelemetry
import com.lerchenflo.schneaggchatv3server.schneaggmap.userlocations.UserLocationService
import com.lerchenflo.schneaggchatv3server.user.UserLookupService
import com.lerchenflo.schneaggchatv3server.util.AppLogger
import com.lerchenflo.schneaggchatv3server.util.Json
import org.bson.types.ObjectId
import org.springframework.context.annotation.Lazy
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
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
    private val pendingPushRegistry: PendingPushRegistry,
    private val refreshTokenRepository: RefreshTokenRepository,
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
                if (!e.isPeerGone()) AppLogger.error("Error force-closing socket: ${e.describe()}", user = userId, tag = "force_close_socket_failed")
            }
        }
    }

    /** Closes the sockets of one ended device. Bookkeeping as in [disconnectUser]. */
    fun disconnectDevice(deviceId: ObjectId) {
        connections.filter { it.deviceId == deviceId }.forEach { connection ->
            try {
                connection.session.close(CloseStatus.NORMAL)
            } catch (e: Exception) {
                if (!e.isPeerGone()) AppLogger.error("Error force-closing socket: ${e.describe()}", user = connection.userId, tag = "force_close_socket_failed")
            }
        }
    }

    /**
     * Every live session of [userId]: a user may be connected from several devices at once, and
     * after a reconnect a not-yet-reaped stale session may sit next to the live one.
     */
    fun sessionsOf(userId: ObjectId): List<SocketConnection> = connections.filter { it.userId == userId }

    /** @return false when the write failed. The session counts as dead then and gets closed. */
    fun write(connection: SocketConnection, json: String): Boolean {
        return try {
            connection.session.sendMessage(TextMessage(json))
            true
        } catch (e: Exception) {
            if (!e.isPeerGone()) {
                AppLogger.error("Error sending socket message (session ${connection.sessionId}): ${e.describe()}", user = connection.userId, tag = "send_socket_message_failed")
            }
            // Unregistering also releases this session's held pushes right away
            closeDeadConnection(connection)
            false
        }
    }

    override fun handleTextMessage(session: WebSocketSession, message: TextMessage) {
        val connection = connections.find { it.sessionId == session.id } ?: return
        val senderId = connection.userId

        val parsed = try {
            Json.mapper.readValue(message.payload, SocketConnectionMessage::class.java)
        } catch (e: Exception) {
            AppLogger.warn("Could not parse inbound socket message: ${e.message}", user = senderId, tag = "parse_socket_message_failed")
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
            is SocketConnectionMessage.MessageAck -> connection.deviceId?.let { pendingPushRegistry.ack(it, parsed.messageId) }
            // All other message types are server -> client only; ignore if received inbound.
            else -> AppLogger.warn("Ignoring unsupported inbound socket message type", user = senderId, tag = "handle_socket_unsupported_type")
        }
    }

    /** Answer to one of [keepaliveSweep]'s pings: the peer is alive. */
    override fun handlePongMessage(session: WebSocketSession, message: PongMessage) {
        connections.find { it.sessionId == session.id }?.lastPongAt = Clock.System.now()
    }

    override fun handleTransportError(session: WebSocketSession, exception: Throwable) {
        // The container closes the session after this and afterConnectionClosed does the bookkeeping.
        if (exception.isPeerGone()) return
        AppLogger.warn("Socket transport error on session ${session.id}: ${exception.describe()}", user = connections.find { it.sessionId == session.id }?.userId, tag = "socket_transport_error")
    }

    /**
     * Keepalive sweep. The server never learns on its own that a peer silently vanished (phone
     * radio already off when the app closed the socket, laptop lid closed, iOS suspended before
     * the close frame was flushed): TCP keeps accepting writes into its buffer for many minutes,
     * so [write] would keep "succeeding" against a dead socket, the push fallback would
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
            // A silent peer is the normal case for a vanished phone, so no log line here.
            if (now - connection.lastPongAt > PONG_TIMEOUT) {
                closeDeadConnection(connection)
                continue
            }
            try {
                connection.session.sendMessage(PingMessage())
            } catch (e: Exception) {
                if (!e.isPeerGone()) {
                    AppLogger.warn("Keepalive ping (session ${connection.sessionId}) failed: ${e.describe()}", user = connection.userId, tag = "send_socket_ping_failed")
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
            if (!e.isPeerGone()) AppLogger.error("Error closing dead socket: ${e.describe()}", user = connection.userId, tag = "close_dead_socket_failed")
        }
        unregister(connection.sessionId)
    }



    /** Closes a socket the server does not accept (1008, policy violation); the peer may already be gone. */
    private fun refuse(session: WebSocketSession, reason: String) {
        try {
            session.close(CloseStatus.POLICY_VIOLATION.withReason(reason))
        } catch (e: Exception) {
            if (!e.isPeerGone()) AppLogger.warn("Could not close refused socket: ${e.describe()}", tag = "auth_socket_close_failed")
        }
    }

    override fun afterConnectionEstablished(session: WebSocketSession) {
        super.afterConnectionEstablished(session)

        var requestingUserId : String? = null
        var deviceId : ObjectId? = null

        val authHeader = session.handshakeHeaders.get("Authorization")?.first()
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            if (jwtService.validateAccessToken(authHeader)) {
                requestingUserId = jwtService.getUserIdFromToken(authHeader)
                deviceId = jwtService.getSessionIdFromToken(authHeader)?.let { ObjectId(it) }
            }
        }


        // Refusals close the socket instead of throwing: an exception here is logged as an ERROR
        // with stack trace and closes with 1011 (server error), on every reconnect attempt.
        if (requestingUserId == null) {
            AppLogger.warn("Socket connection not authenticated", tag = "auth_socket_failed")
            refuse(session, "Not logged in")
            return
        }


        //An ended device keeps its access token for some minutes, but must not get back in
        val sessionEnded = deviceId?.let { !refreshTokenRepository.existsById(it) } ?: false
        if (sessionEnded) {
            AppLogger.warn("Socket refused, session $deviceId no longer exists", user = ObjectId(requestingUserId), tag = "auth_socket_session_ended")
            refuse(session, "Session ended")
            return
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
                deviceId = deviceId,
            )
        }

        //println("Total connections: ${connections.size}")

        // Initial load: push the connecting user the current locations of all friends they may see.
        userLocationService.sendInitialLocationSnapshot(userId, session.id)

        // Initial load: push the connecting user which of their friends are currently online.
        notificationService.sendInitialFriendOnlineSnapshot(userId, session.id)

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

        // Whatever this session had not confirmed yet will not be confirmed any more - push it
        pendingPushRegistry.onSessionClosed(sessionId)

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
