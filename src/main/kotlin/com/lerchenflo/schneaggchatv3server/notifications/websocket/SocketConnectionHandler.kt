package com.lerchenflo.schneaggchatv3server.notifications.websocket

import com.lerchenflo.schneaggchatv3server.core.security.JwtService
import com.lerchenflo.schneaggchatv3server.notifications.NotificationService
import com.lerchenflo.schneaggchatv3server.notifications.websocket.connectiontimelogger.ConnectionTimeLogger
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
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.TextWebSocketHandler
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Clock

@Component
class SocketConnectionHandler(
    private val jwtService: JwtService,
    private val userLookupService: UserLookupService,
    private val connectionTimeLogger: ConnectionTimeLogger,
    // @Lazy breaks the cycle: UserLocationService -> NotificationService -> SocketConnectionHandler.
    @Lazy private val userLocationService: UserLocationService,
    // @Lazy breaks the cycle: NotificationService -> SocketConnectionHandler.
    @Lazy private val notificationService: NotificationService,
): TextWebSocketHandler() {

    var connections : CopyOnWriteArrayList<SocketConnection> = CopyOnWriteArrayList()


    fun isConnected(userId: ObjectId) : Boolean {
        return connections.find { it.userId == userId } != null
    }

    fun broadcast(message: SocketConnectionMessage, excludeUserId: ObjectId?) {
        val json = Json.mapper.writeValueAsString(message)
        for (connection in connections) {
            if (excludeUserId != null && connection.userId == excludeUserId) continue
            try {
                connection.session.sendMessage(TextMessage(json))
            } catch (e: Exception) {
                AppLogger.error("Error broadcasting to user ${connection.userId}: ${e.message}")
            }
        }
    }

    fun sendMessage(message: SocketConnectionMessage, receiverId: ObjectId) : Boolean {
        val userConnection = connections.find { it.userId == receiverId } ?: return false

        try {
            val jsonMessage = Json.mapper.writeValueAsString(message)
            userConnection.session.sendMessage(TextMessage(jsonMessage))
            return true
        } catch (e: Exception) {
            AppLogger.error("Error sending socket message to user $receiverId: ${e.message}")
            return false
        }
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

        //Update session or create new (Multiple connections from the same userid are allowed
        var wasOffline = false
        synchronized(connections) {
            wasOffline = connections.none { it.userId == userId }
            connections += SocketConnection(
                sessionId = session.id,
                userId = userId,
                session = session,
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
    }

    override fun afterConnectionClosed(session: WebSocketSession, status: CloseStatus) {
        super.afterConnectionClosed(session, status)

        //Remove session
        //println("Socket connection closed: $status")

        val connectionToRemove: SocketConnection?
        var isNowOffline = false

        synchronized(connections) {

            connectionToRemove = connections.find { it.sessionId == session.id }

            connections.remove(connectionToRemove)

            isNowOffline = connectionToRemove != null && connections.none { it.userId == connectionToRemove.userId }
        }

        connectionToRemove?.let { //Should always happpen
            connectionTimeLogger.upsertEntry(
                userId = it.userId,
                startTime = it.startedAt,
                endTime = Clock.System.now()
            )

            // That was the user's last active session -> they just went offline.
            if (isNowOffline) {
                val now = Clock.System.now()
                userLookupService.findById(it.userId)?.let { user ->
                    userLookupService.save(user.copy(lastSeen = now, updatedAt = now))
                }
                notificationService.notifyFriendOnlineStatusChange(it.userId, online = false, lastSeen = now)
            }
        }



        //println("Socket connection closed: $status. Remaining connections: ${connections.size}")

    }

}