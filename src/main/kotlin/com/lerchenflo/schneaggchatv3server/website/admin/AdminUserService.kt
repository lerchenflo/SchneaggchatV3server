@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.website.admin

import com.lerchenflo.schneaggchatv3server.notifications.websocket.SocketConnectionHandler
import com.lerchenflo.schneaggchatv3server.repository.RefreshTokenRepository
import com.lerchenflo.schneaggchatv3server.repository.UserRepository
import com.lerchenflo.schneaggchatv3server.user.usermodel.UserRole
import com.lerchenflo.schneaggchatv3server.util.AppLogger
import org.bson.types.ObjectId
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

data class AdminUserResponse(
    val id: String,
    val username: String,
    val createdAt: Long,
    val lastSeen: Long,
    val activeDevices: Int,
    val online: Boolean,
    val role: UserRole,
)

@Service
class AdminUserService(
    private val userRepository: UserRepository,
    private val refreshTokenRepository: RefreshTokenRepository,
    private val socketConnectionHandler: SocketConnectionHandler,
) {

    fun listUsers(): List<AdminUserResponse> {
        val users = userRepository.findAllByOrderByCreatedAtAsc()

        // One query for every live session row, grouped in memory - not a count query per user.
        val devicesByUser = refreshTokenRepository.findByExpiresAtAfter(Clock.System.now())
            .groupingBy { it.userId }
            .eachCount()

        val onlineUserIds = socketConnectionHandler.getConnectedUsers().map { it.userId }.toSet()

        return users.map { user ->
            AdminUserResponse(
                id = user.id.toHexString(),
                username = user.username,
                createdAt = user.createdAt.toEpochMilliseconds(),
                lastSeen = user.lastSeen.toEpochMilliseconds(),
                activeDevices = devicesByUser[user.id] ?: 0,
                online = user.id in onlineUserIds,
                role = user.role,
            )
        }
    }

    /**
     * Logs a user out everywhere: drops every refresh token (so no device can renew) and closes
     * their live sockets (which are only authenticated at connect time, so they would otherwise
     * stay open and fully functional).
     *
     * Their already-issued access token keeps working until it expires - this server has no
     * access-token revocation store - so REST calls can still succeed for up to that window.
     */
    fun forceLogout(userId: ObjectId) {
        val user = userRepository.findById(userId).orElse(null)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")

        refreshTokenRepository.deleteByUserId(userId)
        socketConnectionHandler.disconnectUser(userId)

        AppLogger.info("ADMIN: Forced logout of ${user.username} on all devices")
    }
}
