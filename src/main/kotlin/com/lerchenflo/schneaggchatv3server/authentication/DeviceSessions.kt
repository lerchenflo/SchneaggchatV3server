package com.lerchenflo.schneaggchatv3server.authentication

import com.lerchenflo.schneaggchatv3server.authentication.model.RefreshToken
import com.lerchenflo.schneaggchatv3server.notifications.push.DeviceDirectory
import com.lerchenflo.schneaggchatv3server.notifications.websocket.SocketConnectionHandler
import com.lerchenflo.schneaggchatv3server.repository.RefreshTokenRepository
import org.bson.types.ObjectId
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.stereotype.Service
import kotlin.jvm.optionals.getOrNull
import kotlin.time.Clock

/**
 * Every way a device session ends goes through here, so an ended device stops getting anything at
 * once: its push target leaves with the row, and its live socket gets closed. The device keeps its
 * access token until it expires, so the socket handshake refuses a session that no longer exists.
 */
@Service
class DeviceSessions(
    private val refreshTokenRepository: RefreshTokenRepository,
    private val mongoTemplate: MongoTemplate,
    private val deviceDirectory: DeviceDirectory,
    private val sockets: SocketConnectionHandler,
) {

    /** @return false when [userId] has no session with this id */
    fun endDevice(userId: ObjectId, deviceId: ObjectId): Boolean {
        val row = refreshTokenRepository.findById(deviceId).getOrNull()?.takeIf { it.userId == userId }
            ?: return false

        refreshTokenRepository.delete(row)
        onEnded(listOf(row))
        return true
    }

    /**
     * Ends the session that holds [hashedToken]. Matches [RefreshToken.previousHashedToken] too, so a
     * client logging out with the token it presented right before a rotation still ends the session
     * instead of leaving the rotated row alive.
     */
    fun endByRefreshToken(userId: ObjectId, hashedToken: String): Long {
        val query = Query().addCriteria(
            Criteria.where("userId").`is`(userId).orOperator(
                Criteria.where("hashedToken").`is`(hashedToken),
                Criteria.where("previousHashedToken").`is`(hashedToken),
            )
        )

        val rows = mongoTemplate.findAllAndRemove(query, RefreshToken::class.java)
        onEnded(rows)
        return rows.size.toLong()
    }

    /** @return the number of sessions ended */
    fun endAll(userId: ObjectId): Long {
        val ended = refreshTokenRepository.deleteByUserId(userId)
        deviceDirectory.forgetLegacyTokensOf(userId)
        sockets.disconnectUser(userId)
        return ended
    }

    /**
     * The only token sweep: removes sessions whose sliding `expiresAt` passed, devices that stopped
     * refreshing for the whole refresh validity window.
     */
    fun endExpired() {
        val now = Clock.System.now()
        val rows = refreshTokenRepository.findByExpiresAtBefore(now)
        if (rows.isEmpty()) return

        refreshTokenRepository.deleteByExpiresAtBefore(now)
        onEnded(rows)
    }

    private fun onEnded(rows: List<RefreshToken>) {
        deviceDirectory.forgetEndedDevices(rows)
        rows.forEach { sockets.disconnectDevice(it.id) }
    }
}
