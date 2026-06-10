package com.lerchenflo.schneaggchatv3server.notifications.websocket

import com.lerchenflo.schneaggchatv3server.core.security.JwtService
import com.lerchenflo.schneaggchatv3server.notifications.websocket.connectiontimelogger.ConnectionTimeLogger
import com.lerchenflo.schneaggchatv3server.notifications.websocket.model.SocketConnection
import com.lerchenflo.schneaggchatv3server.notifications.websocket.model.SocketConnectionMessage
import com.lerchenflo.schneaggchatv3server.user.UserLookupService
import com.lerchenflo.schneaggchatv3server.util.AppLogger
import com.lerchenflo.schneaggchatv3server.util.Json
import org.bson.types.ObjectId
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.TextWebSocketHandler
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Clock

@Component
class SocketConnectionHandler(
    private val jwtService: JwtService,
    private val userLookupService: UserLookupService,
    private val connectionTimeLogger: ConnectionTimeLogger
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

    override fun handleMessage(session: WebSocketSession, message: WebSocketMessage<*>) {
        super.handleMessage(session, message)

        val sendingSession = connections.find { it.sessionId == session.id }

        if (sendingSession != null) {
            AppLogger.info("Websocket message received from user ${userLookupService.findById(sendingSession.userId)}")
        }

        //TODO: Handle messages
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

        //Update session or create new (Multiple connections from the same userid are allowed
        synchronized(connections) {
            connections += SocketConnection(
                sessionId = session.id,
                userId = ObjectId(requestingUserId),
                session = session,
            )
        }

        //println("Total connections: ${connections.size}")

    }

    override fun afterConnectionClosed(session: WebSocketSession, status: CloseStatus) {
        super.afterConnectionClosed(session, status)

        //Remove session
        //println("Socket connection closed: $status")

        val connectionToRemove: SocketConnection?

        synchronized(connections) {

            connectionToRemove = connections.find { it.sessionId == session.id }

            connections.remove(connectionToRemove)
        }

        connectionToRemove?.let { //Should always happpen
            connectionTimeLogger.upsertEntry(
                userId = it.userId,
                startTime = it.startedAt,
                endTime = Clock.System.now()
            )
        }



        //println("Socket connection closed: $status. Remaining connections: ${connections.size}")

    }

}