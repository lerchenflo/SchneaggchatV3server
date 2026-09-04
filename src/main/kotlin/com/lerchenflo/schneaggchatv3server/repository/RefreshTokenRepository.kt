package com.lerchenflo.schneaggchatv3server.repository

import com.lerchenflo.schneaggchatv3server.authentication.AuthController
import com.lerchenflo.schneaggchatv3server.authentication.model.RefreshToken
import org.bson.types.ObjectId
import org.springframework.data.mongodb.repository.MongoRepository
import kotlin.time.Instant

interface RefreshTokenRepository: MongoRepository<RefreshToken, ObjectId> {

    /**
     * Replay recovery lookup: a client retrying with an already-rotated token matches the row
     * whose [RefreshToken.previousHashedToken] still holds that hash. Returns a list defensively
     * (legacy data could in theory hold duplicates); callers take the first match.
     */
    fun findByUserIdAndPreviousHashedToken(userId: ObjectId, previousHashedToken: String): List<RefreshToken>

    /**
     * Login dedup lookup: the existing session row for this physical device, newest first in
     * case legacy duplicates for the triple still exist (they age out via [deleteByExpiresAtBefore]).
     */
    fun findFirstByUserIdAndDeviceNameAndDeviceTypeOrderByCreatedAtDesc(
        userId: ObjectId,
        deviceName: String,
        deviceType: AuthController.DEVICETYPE
    ): RefreshToken?

    fun deleteByUserId(userId: ObjectId)

    /**
     * The only token sweep: removes device sessions whose sliding `expiresAt` passed — devices
     * that stopped refreshing for the whole refresh validity window. The
     * `@Indexed(expireAfter = "0s")` TTL index on [RefreshToken.expiresAt] can't do this — every
     * `kotlin.time.Instant` field in this app is stored as a `{epochSeconds, nanosecondsOfSecond}`
     * subdocument, not a BSON Date, so Mongo's TTL monitor silently skips it (see
     * `DatabaseCleanupTask`). Mirrors [UserLocationRepository.deleteByExpiresAtBefore], the same
     * already-working pattern.
     */
    fun deleteByExpiresAtBefore(time: Instant): Long

    /**
     * Active devices per type — with in-place rotation and login dedup there is exactly one
     * unexpired row per logged-in device, so this is an exact device count.
     */
    fun countByDeviceTypeAndExpiresAtAfter(deviceType: AuthController.DEVICETYPE, time: Instant): Long

    /**
     * Active devices whose token predates the [RefreshToken.deviceType] field.
     */
    fun countByDeviceTypeIsNullAndExpiresAtAfter(time: Instant): Long

    /**
     * Every unexpired session row, for the admin user list's per-user device count - one query
     * grouped in memory rather than a count query per listed user.
     */
    fun findByExpiresAtAfter(time: Instant): List<RefreshToken>

}
